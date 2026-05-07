import com.jamesward.zio_mavencentral.MavenCentral
import com.jamesward.zio_mavencentral.MavenCentral.*
import dev.kreuzberg.htmltomarkdown.HtmlToMarkdown
import org.jsoup.Jsoup
import zio.cache.{Cache, ScopedCache, ScopedLookup}
import zio.direct.*
import zio.durationInt
import zio.http.{Client, URL}
import zio.prelude.data.Optional.AllValuesAreNullable
import zio.{Schedule, Scope, ZIO}

import java.io.File
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

object Extractor:
  case class TmpDir(dir: File)

  case class JavadocFileNotFound(groupArtifactVersion: GroupArtifactVersion, path: String)

  case class JavadocContentError(groupArtifactVersion: GroupArtifactVersion, path: String)

  case class LatestNotFound(groupArtifact: GroupArtifact)

  case class Content(link: String, external: Boolean, fqn: String, `type`: String, kind: String, extra: String)

  case class LatestCache(cache: Cache[GroupArtifact, GroupIdOrArtifactIdNotFoundError | LatestNotFound, Version])

  /**
   * Retries a ZIO effect on transient Maven Central errors (5xx).
   *
   * Retries up to 2 times with exponential backoff starting at 1 second,
   * but only when the error is a `MavenCentral.TemporaryServerError`.
   * Permanent errors (e.g. `NotFoundError`, 4xx) fail immediately.
   */
  extension [R, E, A](zio: ZIO[R, E, A])
    def retryOnServerError: ZIO[R, E, A] =
      zio.retry(
        Schedule.recurWhile[E]:
          case _: MavenCentral.TemporaryServerError => true
          case _ => false
        && Schedule.exponential(1.second) && Schedule.recurs(2)
      )

  /**
   * Extracted-javadoc cache backed by `zio.cache.ScopedCache`.
   *
   * Each cache entry owns a `Scope`. When the entry is evicted — for any
   * reason, including capacity overflow, TTL expiry, or explicit invalidation
   * — the scope is closed and the attached finalizer deletes the extracted
   * directory. Per-entry reference counting inside `ScopedCache` ensures the
   * directory is not deleted while a reader still holds a handle to it;
   * callers of `getDir` must therefore consume the result inside `ZIO.scoped`
   * (or pass along the `Scope` requirement).
   */
  case class JavadocCache(cache: ScopedCache[GroupArtifactVersion, NotFoundError, File]):
    def getDir(gav: GroupArtifactVersion): ZIO[Scope, NotFoundError, File] =
      cache.get(gav)

  case class SourcesCache(cache: ScopedCache[GroupArtifactVersion, NotFoundError, File]):
    def getDir(gav: GroupArtifactVersion): ZIO[Scope, NotFoundError, File] =
      cache.get(gav)

  // Recursively deletes a directory tree. Safe to call on a non-existent path.
  private def deleteDirBlocking(dir: File): Unit =
    if dir.isDirectory then
      val children = dir.listFiles
      if children != null then
        var i = 0
        while i < children.length do
          val c = children(i)
          if c != null then deleteDirBlocking(c)
          i += 1
    dir.delete()
    ()

  def gav(groupId: String, artifactId: String, version: String) =
    GroupArtifactVersion(GroupId(groupId), ArtifactId(artifactId), Version(version))

  def latest(groupArtifact: GroupArtifact): ZIO[Client & Scope, GroupIdOrArtifactIdNotFoundError | LatestNotFound, Version] =
    MavenCentral.latest(groupArtifact.groupId, groupArtifact.artifactId)
      .retryOnServerError
      .catchAll:
        case t: Throwable => ZIO.die(t)
        case groupIdOrArtifactIdNotFoundError: GroupIdOrArtifactIdNotFoundError => ZIO.fail(groupIdOrArtifactIdNotFoundError)
      .someOrFail(LatestNotFound(groupArtifact))

  /**
   * Scoped lookup used by the javadoc `ScopedCache`.
   *
   * Downloads and extracts the javadoc jar into `<tmpDir>/<gav>/` (unless the
   * directory is already present from a previous run), then registers a
   * finalizer that deletes the directory when the cache entry is evicted.
   *
   * `ScopedCache` deduplicates concurrent lookups for the same key via its
   * `Pending` state, so there is no need for an external FetchBlocker here.
   */
  def javadoc(groupArtifactVersion: GroupArtifactVersion):
      ZIO[Client & TmpDir & Scope, NotFoundError, File] =
    val javadocUriOrDie: ZIO[Client & Scope, NotFoundError, URL] =
      MavenCentral.javadocUri(groupArtifactVersion.groupId, groupArtifactVersion.artifactId, groupArtifactVersion.version)
        .retryOnServerError
        .catchAll:
          case t: Throwable => ZIO.die(t)
          case javadocNotFoundError: NotFoundError => ZIO.fail(javadocNotFoundError)

    defer:
      val tmpDir = ZIO.service[TmpDir].run
      val javadocDir = File(tmpDir.dir, groupArtifactVersion.toString)

      if !javadocDir.exists() then
        val javadocUrl = javadocUriOrDie.run
        ZIO.logInfo(s"Downloading javadoc: $javadocUrl").run
        val duration = MavenCentral.downloadAndExtractZip(javadocUrl, javadocDir)
          .orDie.timed.map(_._1).run
        ZIO.logInfo(s"Downloaded javadoc: $groupArtifactVersion duration=${duration.toMillis}ms").run

      // Cache-entry finalizer: when this entry is evicted (capacity, TTL, or
      // explicit invalidate) the scope closes and we delete the extracted dir.
      ZIO.addFinalizer(
        ZIO.logInfo(s"Evicting javadoc cache entry: $groupArtifactVersion") *>
          ZIO.attemptBlockingIO(deleteDirBlocking(javadocDir)).ignoreLogged
      ).run

      javadocDir

  def index(groupArtifactVersion: GroupArtifactVersion):
      ZIO[Client & TmpDir & JavadocCache & Scope, NotFoundError | JavadocFileNotFound | JavadocContentError, String] =
    javadocSymbolContents(groupArtifactVersion, "index.html")

  def sources(groupArtifactVersion: GroupArtifactVersion):
      ZIO[Client & TmpDir & Scope, NotFoundError, File] =
    val sourcesUriOrDie: ZIO[Client & Scope, NotFoundError, URL] =
      MavenCentral.sourcesUri(groupArtifactVersion.groupId, groupArtifactVersion.artifactId, groupArtifactVersion.version)
        .retryOnServerError
        .catchAll:
          case t: Throwable => ZIO.die(t)
          case sourcesNotFoundError: NotFoundError => ZIO.fail(sourcesNotFoundError)

    defer:
      val tmpDir = ZIO.service[TmpDir].run
      val sourcesDir = File(tmpDir.dir, s"${groupArtifactVersion.toString}-sources")

      if !sourcesDir.exists() then
        val sourcesUrl = sourcesUriOrDie.run
        ZIO.logInfo(s"Downloading sources: $sourcesUrl").run
        val duration = MavenCentral.downloadAndExtractZip(sourcesUrl, sourcesDir)
          .orDie.timed.map(_._1).run
        ZIO.logInfo(s"Downloaded sources: $groupArtifactVersion duration=${duration.toMillis}ms").run

      ZIO.addFinalizer(
        ZIO.logInfo(s"Evicting sources cache entry: $groupArtifactVersion") *>
          ZIO.attemptBlockingIO(deleteDirBlocking(sourcesDir)).ignoreLogged
      ).run

      sourcesDir

  def javadocFile(groupArtifactVersion: GroupArtifactVersion, javadocDir: File, path: String):
      ZIO[Any, JavadocFileNotFound, File] =

    val normalizedPath = path.takeWhile(_ != '#')

    val javadocFile = File(javadocDir, normalizedPath)

    if javadocFile.exists() && javadocFile.isFile then
      ZIO.succeed(javadocFile)
    else
      ZIO.fail(JavadocFileNotFound(groupArtifactVersion, path))

  def parseScaladoc(contents: String): Either[String, Set[Content]] =
    import zio.json.*

    case class ScaladocEntry(l: String, e: Boolean, i: String, n: String, t: String, d: String, k: String, x: String) derives JsonDecoder
    contents.fromJson[Set[ScaladocEntry]].map(_.map(e => Content(e.l, e.e, s"${e.d}.${e.n}", e.t, e.k, e.x)))

  def parseKotlindoc(contents: String): Either[String, Set[Content]] =
    import zio.json.*

    case class KotlindocEntry(name: String, description: String, location: String) derives JsonDecoder
    contents.fromJson[Set[KotlindocEntry]].map(_.map(e => Content(e.location, false, e.description, e.name.trim, "", "")))

  def bruteForce(baseDir: File): ZIO[Any, Nothing, Set[Content]] =
    ZIO.attemptBlockingIO:
      // todo: handle index.html & zio/stm/index.html
      Files.walk(baseDir.toPath).iterator().asScala
        .filter { path =>
          path.toString.endsWith(".html")
        }
        .map { path =>
          Content(
            baseDir.toPath.relativize(path).toString,
            false,
            path.getFileName.toString.stripSuffix(".html"),
            "",
            "",
            "",
          )
        }
        .toSet
    .orDie

  case class JavadocFormatFailure()

  // todo: handle order version of scaladoc (fun!)
  def javadocScalaFormat(groupArtifactVersion: GroupArtifactVersion, javadocDir: File):
      ZIO[Any, JavadocFormatFailure, Set[Content]] =
    javadocFile(groupArtifactVersion, javadocDir, "scripts/searchData.js")
      .flatMap: file =>
        ZIO.attemptBlockingIO(Files.readString(file.toPath).stripPrefix("pages = ").stripSuffix(";")).flatMap: contents =>
          ZIO.fromEither(parseScaladoc(contents))
      .orElseFail(JavadocFormatFailure())

  // todo: handle order version of dokka (fun!)
  def javadocKotlinFormat(groupArtifactVersion: GroupArtifactVersion, javadocDir: File):
      ZIO[Any, JavadocFormatFailure, Set[Content]] =
    javadocFile(groupArtifactVersion, javadocDir, "scripts/pages.json")
      .flatMap: file =>
        ZIO.attemptBlockingIO(Files.readString(file.toPath)).flatMap: contents =>
          ZIO.fromEither(parseKotlindoc(contents))
      .orElseFail(JavadocFormatFailure())

  // could be better based on index-all.html
  def javadocJavaFormat(groupArtifactVersion: GroupArtifactVersion, javadocDir: File):
      ZIO[Any, JavadocFormatFailure, Set[Content]] =
    javadocFile(groupArtifactVersion, javadocDir, "element-list")
      .orElseFail(JavadocFormatFailure())
      .flatMap: file =>
        ZIO.attemptBlockingIO:
          val lines = Files.readAllLines(file.toPath).asScala
          // extract module directories (lines like "module:org.webjars.locator_lite")
          val moduleDirs = lines.collect:
            case line if line.startsWith("module:") => line.stripPrefix("module:")
          // package entries are lines without "module:" prefix
          val packages = lines.filterNot(_.startsWith("module:")).filter(_.nonEmpty)

          def walkPackageDir(baseDir: File, pkg: String): Seq[Content] =
            val pkgDir = File(baseDir, pkg.replace('.', '/'))
            if pkgDir.isDirectory then
              val files = Files.walk(pkgDir.toPath).iterator().asScala
              files
                .map(javadocDir.toPath.relativize) // always relative to javadoc root
                .filter: file =>
                  file.toString.endsWith(".html") &&
                    !file.getFileName.toString.startsWith("package-") &&
                    !file.toString.contains("class-use")
                .map: file =>
                  val fqn = file.toString.stripSuffix(".html").replace('/', '.')
                  Content(file.toString, false, fqn, "", "", "")
                .toSeq
            else
              Seq.empty

          packages.flatMap: pkg =>
            // try root first, then under each module directory
            val fromRoot = walkPackageDir(javadocDir, pkg)
            if fromRoot.nonEmpty then fromRoot
            else moduleDirs.flatMap(mod => walkPackageDir(File(javadocDir, mod), pkg))
          .toSet
        .mapError(_ => JavadocFormatFailure())

  def javadocContents(groupArtifactVersion: GroupArtifactVersion):
      ZIO[JavadocCache & Client & TmpDir & Scope, MavenCentral.NotFoundError, Set[Content]] =
    defer:
      val javadocCache = ZIO.service[JavadocCache].run
      val javadocDir = javadocCache.getDir(groupArtifactVersion).run

      javadocScalaFormat(groupArtifactVersion, javadocDir)
        .orElse(javadocKotlinFormat(groupArtifactVersion, javadocDir))
        .orElse(javadocJavaFormat(groupArtifactVersion, javadocDir))
        .catchAll:
          case _: JavadocFormatFailure =>
            bruteForce(javadocDir)
          case e: NotFoundError =>
            ZIO.fail(e)
        .run

  def fileList(path: Path): ZIO[Any, Nothing, Set[String]] =
    ZIO.attemptBlockingIO:
      Files.walk(path).iterator().asScala
        .filter(_.toFile.isFile)
        .map(path.relativize(_).toString)
        .toSet
    .orDie

  def sourceContents(groupArtifactVersion: GroupArtifactVersion):
      ZIO[SourcesCache & Client & TmpDir & Scope, MavenCentral.NotFoundError, Set[String]] =
    defer:
      val sourcesCache = ZIO.service[SourcesCache].run
      val sourcesDir = sourcesCache.getDir(groupArtifactVersion).run
      fileList(sourcesDir.toPath).run


  def javaDocTextSymbolContents(contents: String): Option[String] =
    HtmlToMarkdown.convert(contents).content().toScala

  def scalaDocTextSymbolContents(contents: String): Option[String] =
    val document = Jsoup.parse(contents)
    val contentRoot = Option(document.selectFirst("#content > div"))
      .getOrElse(document.body())
    HtmlToMarkdown.convert(contentRoot.outerHtml()).content().toScala

  def javadocSymbolContents(groupArtifactVersion: GroupArtifactVersion, path: String):
      ZIO[JavadocCache & Client & TmpDir & Scope, NotFoundError | JavadocFileNotFound | JavadocContentError, String] =
    defer:
      val javadocCache = ZIO.service[JavadocCache].run
      val javadocDir = javadocCache.getDir(groupArtifactVersion).run

      javadocFile(groupArtifactVersion, javadocDir, path)
        .flatMap: file =>
          ZIO.attemptBlockingIO(Files.readString(file.toPath)).orDie.flatMap: contents =>
            val maybeResult =
              if contents.contains("Generated by javadoc") then
                javaDocTextSymbolContents(contents)
              else if contents.contains("<div id=\"content\" class=\"body-medium\"") then
                scalaDocTextSymbolContents(contents)
              else
                Some(contents)
            ZIO.fromOption(maybeResult).mapError(_ => JavadocContentError(groupArtifactVersion, path))
      .run

  def sourceFileContents(groupArtifactVersion: GroupArtifactVersion, path: String):
      ZIO[SourcesCache & Client & TmpDir & Scope, NotFoundError | JavadocFileNotFound, String] =
    defer:
      val sourcesCache = ZIO.service[SourcesCache].run
      val sourcesDir = sourcesCache.getDir(groupArtifactVersion).run

      javadocFile(groupArtifactVersion, sourcesDir, path)
        .flatMap: file =>
          ZIO.attemptBlockingIO(Files.readString(file.toPath)).orDie
        .run
