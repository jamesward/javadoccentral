import com.jamesward.zio_mavencentral.MavenCentral
import com.jamesward.zio_mavencentral.MavenCentral.*
import com.jamesward.zio_mavencentral.JarCache
import dev.kreuzberg.htmltomarkdown.HtmlToMarkdown
import org.jsoup.Jsoup
import zio.cache.Cache
import zio.direct.*
import zio.http.Client
import zio.prelude.data.Optional.AllValuesAreNullable
import zio.{IO, ZIO}

import java.nio.charset.StandardCharsets

/**
 * Javadoc/sources reading on top of `zio-mavencentral`'s [[JarCache]].
 *
 * The actual download/cache/random-access machinery lives in the library;
 * this file is the *content* layer — symbol-search index parsing, javadoc
 * vs. scaladoc vs. kotlindoc format detection, HTML → markdown for the
 * MCP path, etc. None of it cares about on-disk extraction anymore;
 * callers receive a `JarCache.JarHandle` and read entries directly.
 */
object Extractor:

  case class JavadocFileNotFound(groupArtifactVersion: GroupArtifactVersion, path: String)

  case class JavadocContentError(groupArtifactVersion: GroupArtifactVersion, path: String)

  /** Re-exported here for backward compatibility; lives in the library. */
  type LatestNotFound = MavenCentral.LatestNotFound
  val LatestNotFound = MavenCentral.LatestNotFound

  case class Content(link: String, external: Boolean, fqn: String, `type`: String, kind: String, extra: String)

  /** Caches `GroupArtifact -> latest Version` lookups so a single resolution
   *  is shared by concurrent callers and reused across requests for an hour. */
  case class LatestCache(cache: Cache[GroupArtifact, GroupIdOrArtifactIdNotFoundError | LatestNotFound, Version])

  /**
   * Wrapper around `JarCache` for javadoc jars. Exists so the ZIO
   * environment can distinguish javadoc vs. sources caches by type
   * (otherwise both would be `JarCache` and `ZIO.service[JarCache]`
   * would be ambiguous).
   */
  final class JavadocCache(val cache: JarCache):
    def get(gav: GroupArtifactVersion): ZIO[Client, NotFoundError, JarCache.JarHandle] =
      cache.get(gav)

  final class SourcesCache(val cache: JarCache):
    def get(gav: GroupArtifactVersion): ZIO[Client, NotFoundError, JarCache.JarHandle] =
      cache.get(gav)

  /** Re-exported library helper. Kept as a method here so existing call
   *  sites (`Extractor.latest`) compile unchanged. */
  def latest(groupArtifact: GroupArtifact): ZIO[Client, GroupIdOrArtifactIdNotFoundError | LatestNotFound, Version] =
    MavenCentral.latestOrFail(groupArtifact)

  /** Strip an `#anchor` fragment so it doesn't disturb jar-entry lookup. */
  private def normalizePath(path: String): String =
    path.takeWhile(_ != '#')

  /** Read a single jar entry as a UTF-8 string, mapping a missing entry
   *  to [[JavadocFileNotFound]]. */
  private def readEntryString(
    handle: JarCache.JarHandle,
    gav: GroupArtifactVersion,
    path: String,
  ): IO[JavadocFileNotFound, String] =
    handle.readEntryString(normalizePath(path))
      .mapError(_ => JavadocFileNotFound(gav, path))

  def parseScaladoc(contents: String): Either[String, Set[Content]] =
    import zio.json.*

    case class ScaladocEntry(l: String, e: Boolean, i: String, n: String, t: String, d: String, k: String, x: String) derives JsonDecoder
    contents.fromJson[Set[ScaladocEntry]].map(_.map(e => Content(e.l, e.e, s"${e.d}.${e.n}", e.t, e.k, e.x)))

  def parseKotlindoc(contents: String): Either[String, Set[Content]] =
    import zio.json.*

    case class KotlindocEntry(name: String, description: String, location: String) derives JsonDecoder
    contents.fromJson[Set[KotlindocEntry]].map(_.map(e => Content(e.location, false, e.description, e.name.trim, "", "")))

  /** Last-resort symbol set: every `.html` entry name in the jar. */
  def bruteForce(handle: JarCache.JarHandle): ZIO[Any, Nothing, Set[Content]] =
    handle.filterEntryNames(_.endsWith(".html")).map { names =>
      names.map { name =>
        val fileName = name.substring(name.lastIndexOf('/') + 1)
        Content(name, false, fileName.stripSuffix(".html"), "", "", "")
      }
    }

  case class JavadocFormatFailure()

  // todo: handle older versions of scaladoc
  def javadocScalaFormat(gav: GroupArtifactVersion, handle: JarCache.JarHandle):
      ZIO[Any, JavadocFormatFailure, Set[Content]] =
    handle.readEntryString("scripts/searchData.js")
      .mapError(_ => JavadocFormatFailure())
      .flatMap: raw =>
        val contents = raw.stripPrefix("pages = ").stripSuffix(";")
        ZIO.fromEither(parseScaladoc(contents)).orElseFail(JavadocFormatFailure())

  // todo: handle older versions of dokka
  def javadocKotlinFormat(gav: GroupArtifactVersion, handle: JarCache.JarHandle):
      ZIO[Any, JavadocFormatFailure, Set[Content]] =
    handle.readEntryString("scripts/pages.json")
      .mapError(_ => JavadocFormatFailure())
      .flatMap: contents =>
        ZIO.fromEither(parseKotlindoc(contents)).orElseFail(JavadocFormatFailure())

  // could be better based on index-all.html
  def javadocJavaFormat(gav: GroupArtifactVersion, handle: JarCache.JarHandle):
      ZIO[Any, JavadocFormatFailure, Set[Content]] =
    handle.readEntryString("element-list")
      .mapError(_ => JavadocFormatFailure())
      .flatMap: raw =>
        val lines = raw.linesIterator.toVector
        // Modular javadocs prefix package lists with "module:<modName>".
        val moduleDirs = lines.collect:
          case line if line.startsWith("module:") => line.stripPrefix("module:")
        val packages = lines.filterNot(_.startsWith("module:")).filter(_.nonEmpty)

        handle.entryNames.map: allEntries =>
          def entriesUnderPkg(prefix: String, pkg: String): Set[Content] =
            // Match jar entries whose path begins with `<prefix><pkg-as-path>/`,
            // are .html, and aren't package-* / class-use noise.
            val pkgPath = if prefix.isEmpty then pkg.replace('.', '/') + "/"
                          else prefix + "/" + pkg.replace('.', '/') + "/"
            allEntries.collect {
              case name if name.startsWith(pkgPath)
                && name.endsWith(".html")
                && !name.substring(pkgPath.length).startsWith("package-")
                && !name.contains("class-use")
                && !name.substring(pkgPath.length).contains("/") =>
                val fqn = name.stripSuffix(".html").replace('/', '.').stripPrefix(prefix.replace('/', '.'))
                  .stripPrefix(".")
                Content(name, false, fqn, "", "", "")
            }

          packages.flatMap: pkg =>
            val fromRoot = entriesUnderPkg("", pkg)
            if fromRoot.nonEmpty then fromRoot
            else moduleDirs.flatMap(mod => entriesUnderPkg(mod, pkg)).toSet
          .toSet

  def javadocContents(groupArtifactVersion: GroupArtifactVersion):
      ZIO[JavadocCache & Client, NotFoundError, Set[Content]] =
    defer:
      val cache  = ZIO.service[JavadocCache].run
      val handle = cache.get(groupArtifactVersion).run
      javadocScalaFormat(groupArtifactVersion, handle)
        .orElse(javadocKotlinFormat(groupArtifactVersion, handle))
        .orElse(javadocJavaFormat(groupArtifactVersion, handle))
        .catchAll:
          case _: JavadocFormatFailure => bruteForce(handle)
        .run

  def sourceContents(groupArtifactVersion: GroupArtifactVersion):
      ZIO[SourcesCache & Client, NotFoundError, Set[String]] =
    defer:
      val cache  = ZIO.service[SourcesCache].run
      val handle = cache.get(groupArtifactVersion).run
      // Mimic the previous Files.walk-of-regular-files semantics: ZipFile
      // entries that look like directories (trailing '/') are excluded.
      handle.filterEntryNames(name => !name.endsWith("/")).run

  def javaDocTextSymbolContents(contents: String): Option[String] =
    Option(HtmlToMarkdown.convert(contents).content())

  def scalaDocTextSymbolContents(contents: String): Option[String] =
    val document = Jsoup.parse(contents)
    val contentRoot = Option(document.selectFirst("#content > div"))
      .getOrElse(document.body())
    Option(HtmlToMarkdown.convert(contentRoot.outerHtml()).content())

  def javadocSymbolContents(groupArtifactVersion: GroupArtifactVersion, path: String):
      ZIO[JavadocCache & Client, NotFoundError | JavadocFileNotFound | JavadocContentError, String] =
    defer:
      val cache    = ZIO.service[JavadocCache].run
      val handle   = cache.get(groupArtifactVersion).run
      val contents = readEntryString(handle, groupArtifactVersion, path).run

      val maybeResult =
        if contents.contains("Generated by javadoc") then javaDocTextSymbolContents(contents)
        else if contents.contains("<div id=\"content\" class=\"body-medium\"") then scalaDocTextSymbolContents(contents)
        else Some(contents)

      ZIO.fromOption(maybeResult).mapError(_ => JavadocContentError(groupArtifactVersion, path)).run

  /** Raw bytes for a single jar entry, used by the HTTP file-serving path
   *  in `Web.scala` (response body comes from these bytes). */
  def javadocEntryBytes(groupArtifactVersion: GroupArtifactVersion, path: String):
      ZIO[JavadocCache & Client, NotFoundError | JavadocFileNotFound, Array[Byte]] =
    defer:
      val cache  = ZIO.service[JavadocCache].run
      val handle = cache.get(groupArtifactVersion).run
      handle.readEntry(normalizePath(path))
        .mapError(_ => JavadocFileNotFound(groupArtifactVersion, path))
        .run

  /** Direct access to a javadoc jar's handle. Used when callers need
   *  multiple entries / metadata. */
  def javadocJar(groupArtifactVersion: GroupArtifactVersion):
      ZIO[JavadocCache & Client, NotFoundError, JarCache.JarHandle] =
    ZIO.serviceWithZIO[JavadocCache](_.get(groupArtifactVersion))

  /** MCP convenience: read `index.html` from the javadoc jar as a markdown
   *  string. Equivalent to `javadocSymbolContents(gav, "index.html")`. */
  def index(groupArtifactVersion: GroupArtifactVersion):
      ZIO[JavadocCache & Client, NotFoundError | JavadocFileNotFound | JavadocContentError, String] =
    javadocSymbolContents(groupArtifactVersion, "index.html")

  def sourceFileContents(groupArtifactVersion: GroupArtifactVersion, path: String):
      ZIO[SourcesCache & Client, NotFoundError | JavadocFileNotFound, String] =
    defer:
      val cache  = ZIO.service[SourcesCache].run
      val handle = cache.get(groupArtifactVersion).run
      readEntryString(handle, groupArtifactVersion, path).run
