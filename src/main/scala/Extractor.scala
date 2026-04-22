import com.jamesward.zio_mavencentral.MavenCentral
import com.jamesward.zio_mavencentral.MavenCentral.*
import dev.kreuzberg.htmltomarkdown.HtmlToMarkdown
import org.jsoup.Jsoup
import zio.cache.Cache
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.http.{Client, URL}
import zio.prelude.data.Optional.AllValuesAreNullable
import zio.{Promise, Scope, ZIO}

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
  case class JavadocCache(cache: Cache[GroupArtifactVersion, NotFoundError, File])
  case class SourcesCache(cache: Cache[GroupArtifactVersion, NotFoundError, File])
  case class FetchBlocker(blocker: ConcurrentMap[GroupArtifactVersion, Promise[Nothing, Unit]])
  case class FetchSourcesBlocker(blocker: ConcurrentMap[GroupArtifactVersion, Promise[Nothing, Unit]])

  def gav(groupId: String, artifactId: String, version: String) =
    GroupArtifactVersion(GroupId(groupId), ArtifactId(artifactId), Version(version))

  def latest(groupArtifact: GroupArtifact): ZIO[Client & Scope, GroupIdOrArtifactIdNotFoundError | LatestNotFound, Version] =
    MavenCentral.latest(groupArtifact.groupId, groupArtifact.artifactId)
      .catchAll:
        case t: Throwable => ZIO.die(t)
        case groupIdOrArtifactIdNotFoundError: GroupIdOrArtifactIdNotFoundError => ZIO.fail(groupIdOrArtifactIdNotFoundError)
      .someOrFail(LatestNotFound(groupArtifact))

  def javadoc(groupArtifactVersion: GroupArtifactVersion):
      ZIO[Client & FetchBlocker & TmpDir & Scope, NotFoundError, File] =
    val javadocUriOrDie: ZIO[Client & Scope, NotFoundError, URL] = MavenCentral.javadocUri(groupArtifactVersion.groupId, groupArtifactVersion.artifactId, groupArtifactVersion.version).catchAll:
      case t: Throwable => ZIO.die(t)
      case javadocNotFoundError: NotFoundError => ZIO.fail(javadocNotFoundError)

    defer:
      val blocker = ZIO.service[FetchBlocker].run.blocker
      val tmpDir = ZIO.service[TmpDir].run
      val javadocDir = File(tmpDir.dir, groupArtifactVersion.toString)

      if !javadocDir.exists() then
        val javadocUrl = javadocUriOrDie.run
        val promise = Promise.make[Nothing, Unit].run
        val existing = blocker.putIfAbsent(groupArtifactVersion, promise).run
        existing match
          case Some(existingPromise) =>
            existingPromise.await.run
          case None =>
            ZIO.logInfo(s"Downloading javadoc: $javadocUrl").run
            val duration = MavenCentral.downloadAndExtractZipStreaming(javadocUrl, javadocDir)
              .ensuring(promise.succeed(()) *> blocker.remove(groupArtifactVersion))
              .orDie.timed.map(_._1).run
            ZIO.logInfo(s"Downloaded javadoc: $groupArtifactVersion duration=${duration.toMillis}ms").run

      javadocDir

  def index(groupArtifactVersion: GroupArtifactVersion):
      ZIO[Client & FetchBlocker & TmpDir & JavadocCache & Scope, NotFoundError | JavadocFileNotFound | JavadocContentError, String] =
    javadocSymbolContents(groupArtifactVersion, "index.html")

  def sources(groupArtifactVersion: GroupArtifactVersion):
      ZIO[Client & FetchSourcesBlocker & TmpDir & Scope, NotFoundError, File] =
    val sourcesUriOrDie: ZIO[Client & Scope, NotFoundError, URL] = MavenCentral.sourcesUri(groupArtifactVersion.groupId, groupArtifactVersion.artifactId, groupArtifactVersion.version).catchAll:
      case t: Throwable => ZIO.die(t)
      case sourcesNotFoundError: NotFoundError => ZIO.fail(sourcesNotFoundError)

    defer:
      val blocker = ZIO.service[FetchSourcesBlocker].run.blocker
      val tmpDir = ZIO.service[TmpDir].run
      val sourcesDir = File(tmpDir.dir, s"${groupArtifactVersion.toString}-sources")

      if !sourcesDir.exists() then
        val promise = Promise.make[Nothing, Unit].run
        val existing = blocker.putIfAbsent(groupArtifactVersion, promise).run
        existing match
          case Some(existingPromise) =>
            existingPromise.await.run
          case None =>
            val sourcesUrl = sourcesUriOrDie.run
            ZIO.logInfo(s"Downloading sources: $sourcesUrl").run
            val duration = MavenCentral.downloadAndExtractZipStreaming(sourcesUrl, sourcesDir)
              .ensuring(promise.succeed(()) *> blocker.remove(groupArtifactVersion))
              .orDie.timed.map(_._1).run
            ZIO.logInfo(s"Downloaded sources: $groupArtifactVersion duration=${duration.toMillis}ms").run

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
      .mapError(_ => JavadocFormatFailure())
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
      ZIO[JavadocCache & Client & FetchBlocker & Scope, MavenCentral.NotFoundError, Set[Content]] =
    defer:
      val javadocCache = ZIO.service[JavadocCache].run.cache
      val javadocDir = javadocCache.get(groupArtifactVersion).run

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
      ZIO[SourcesCache & Client & FetchSourcesBlocker & Scope, MavenCentral.NotFoundError, Set[String]] =
    defer:
      val sourcesCache = ZIO.service[SourcesCache].run.cache
      val sourcesDir = sourcesCache.get(groupArtifactVersion).run
      fileList(sourcesDir.toPath).run


  def javaDocTextSymbolContents(contents: String): Option[String] =
    Option(HtmlToMarkdown.convert(contents).content()) // .toScala

  def scalaDocTextSymbolContents(contents: String): Option[String] =
    val document = Jsoup.parse(contents)
    val contentRoot = Option(document.selectFirst("#content > div"))
      .getOrElse(document.body())
    Option(HtmlToMarkdown.convert(contentRoot.outerHtml()).content()) // .toScala

  def javadocSymbolContents(groupArtifactVersion: GroupArtifactVersion, path: String):
      ZIO[JavadocCache & Client & FetchBlocker & Scope, NotFoundError | JavadocFileNotFound | JavadocContentError, String] =
    defer:
      val javadocCache = ZIO.service[JavadocCache].run.cache
      val javadocDir = javadocCache.get(groupArtifactVersion).run

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
      ZIO[SourcesCache & Client & FetchSourcesBlocker & Scope, NotFoundError | JavadocFileNotFound, String] =
    defer:
      val sourcesCache = ZIO.service[SourcesCache].run.cache
      val sourcesDir = sourcesCache.get(groupArtifactVersion).run

      javadocFile(groupArtifactVersion, sourcesDir, path)
        .flatMap: file =>
          ZIO.attemptBlockingIO(Files.readString(file.toPath)).orDie
        .run
