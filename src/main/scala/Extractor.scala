import com.jamesward.zio_mavencentral.MavenCentral
import com.jamesward.zio_mavencentral.MavenCentral.*
import com.jamesward.zio_mavencentral.JarCache
import dev.kreuzberg.htmltomarkdown.HtmlToMarkdown
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import scala.jdk.CollectionConverters.*
import zio.cache.Cache
import zio.direct.*
import zio.http.Client
import zio.prelude.data.Optional.AllValuesAreNullable
import zio.{IO, ZIO}
import zio.schema.{Schema, derived}
import zio.schema.annotation.description

import MavenCentral.MavenCentralRepo

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

  type JarError = JarCache.GetError

  case class JavadocFileNotFound(groupArtifactVersion: GroupArtifactVersion, path: String)

  case class JavadocContentError(groupArtifactVersion: GroupArtifactVersion, path: String)

  /** Re-exported here for backward compatibility; lives in the library. */
  type LatestNotFound = MavenCentral.LatestNotFound
  val LatestNotFound = MavenCentral.LatestNotFound

  @description("One documented entry (class, object, method, package, or resource) from a library's Javadoc/Scaladoc jar.")
  case class Content(
    @description("Path/link of this entry within the javadoc jar. Pass it as `link` to get_javadoc_symbol to read the rendered page.")
    link: String,
    @description("True if the entry links to an external site rather than a page inside this jar.")
    external: Boolean,
    @description("Fully-qualified name of the documented symbol.")
    fqn: String,
    @description("The symbol's declared type/signature when the doc format provides one; may be empty.")
    `type`: String,
    @description("The kind of symbol, e.g. \"class\", \"object\", \"def\"; may be empty.")
    kind: String,
    @description("Extra doc-format-specific metadata; may be empty.")
    extra: String,
  ) derives Schema

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
    def get(gav: GroupArtifactVersion): ZIO[Client & MavenCentralRepo, JarError, JarCache.JarHandle] =
      cache.get(gav)

  final class SourcesCache(val cache: JarCache):
    def get(gav: GroupArtifactVersion): ZIO[Client & MavenCentralRepo, JarError, JarCache.JarHandle] =
      cache.get(gav)

  /** Re-exported library helper. Kept as a method here so existing call
   *  sites (`Extractor.latest`) compile unchanged. */
  def latest(groupArtifact: GroupArtifact, includePreReleases: Boolean = false): ZIO[MavenCentralRepo, GroupIdOrArtifactIdNotFoundError | LatestNotFound, Version] =
    MavenCentral.latestOrFail(groupArtifact, includePreReleases)

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
  //
  // Modern javadocs (Java 9+) ship an `element-list` (module-aware). Older,
  // frames-based javadocs (Java 8 and earlier — e.g. jackson-databind, still
  // built with an old doclet) instead ship a `package-list` with the exact
  // same one-package-per-line format (minus `module:` prefixes). Read whichever
  // exists so old javadocs get real FQNs instead of falling through to
  // `bruteForce` (which only knows the simple class name).
  def javadocJavaFormat(gav: GroupArtifactVersion, handle: JarCache.JarHandle):
      ZIO[Any, JavadocFormatFailure, Set[Content]] =
    handle.readEntryString("element-list")
      .orElse(handle.readEntryString("package-list"))
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
      ZIO[JavadocCache & Client & MavenCentralRepo, JarError, Set[Content]] =
    defer:
      val cache  = ZIO.service[JavadocCache].run
      val handle = cache.get(groupArtifactVersion).run
      javadocScalaFormat(groupArtifactVersion, handle)
        .orElse(javadocKotlinFormat(groupArtifactVersion, handle))
        .orElse(javadocJavaFormat(groupArtifactVersion, handle))
        .catchAll:
          case _: JavadocFormatFailure => bruteForce(handle)
        .run

  /**
   * Narrows a symbol listing to entries whose fully-qualified name contains every
   * whitespace-separated term of `nameFilter` (case-insensitive). A large library
   * lists hundreds of classes (jackson-databind: ~700), which is most of an agent's
   * context budget when it only wants one type; no filter (or a blank one) keeps all.
   */
  private val term = "\\S+".r

  def filterContents(contents: Set[Content], nameFilter: Option[String]): Set[Content] =
    val terms: List[String] = nameFilter match
      // An iterator of matches, not `split(...).toList`: this file imports zio-prelude's
      // AllValuesAreNullable, under which an Array silently converts to a one-element
      // Optional, so `array.toList` became List(array) and nothing ever matched.
      case Some(text) => term.findAllIn(text.toLowerCase).toList
      case None       => Nil
    if terms.isEmpty then contents
    else contents.filter(content => matchesAll(content.fqn, terms))

  private def matchesAll(fqn: String, terms: List[String]): Boolean =
    val lower = fqn.toLowerCase.nn
    terms.forall(term => lower.contains(term))

  def sourceContents(groupArtifactVersion: GroupArtifactVersion):
      ZIO[SourcesCache & Client & MavenCentralRepo, JarError, Set[String]] =
    defer:
      val cache  = ZIO.service[SourcesCache].run
      val handle = cache.get(groupArtifactVersion).run
      // Mimic the previous Files.walk-of-regular-files semantics: ZipFile
      // entries that look like directories (trailing '/') are excluded.
      handle.filterEntryNames(name => !name.endsWith("/")).run

  /** How the links that survive in a page converted to markdown are written. */
  enum LinkBase:
    /** Relative to the page, as in the original HTML: right when the markdown is served at the page's own URL. */
    case Page
    /** Resolved to jar entry paths, so an agent can pass one straight back as a `link`. */
    case Jar

  // Navigation bars, skip links, scripts and table-tab widgets that javadoc wraps around
  // the documentation (old frames-era class names and modern ones). For an agent they are
  // pure noise: "JavaScript is disabled", Prev/Next/Frames links, "All Methods | Static…".
  private val javadocChrome = List(
    "script", "noscript", "header", "nav", "footer",
    ".topNav", ".subNav", ".bottomNav", ".skipNav", ".fixedNav", ".legalCopy", ".tableTab", ".tabEnd",
    ".top-nav", ".sub-nav", ".bottom-nav", ".skip-nav", ".flex-header", ".legal-copy", ".table-tab",
  ).mkString(", ")

  private val scheme = "^[A-Za-z][A-Za-z0-9+.-]*:".r
  private val pathSegment = "[^/]+".r

  /** Collapses `.` / `..` segments; `None` if the path climbs above the jar root. */
  private def normalizeSegments(path: String): Option[String] =
    pathSegment.findAllIn(path).foldLeft(Option(List.empty[String])):
      case (None, _)               => None
      case (Some(acc), ".")        => Some(acc)
      case (Some(Nil), "..")       => None
      case (Some(_ :: rest), "..") => Some(rest)
      case (Some(acc), segment)    => Some(segment :: acc)
    .map(_.reverse.mkString("/"))

  /**
   * Resolves `href`, as written in the page at jar path `pagePath`, to the jar entry it
   * points at (keeping any `#anchor`). `None` for links that are useless to a reader of the
   * markdown: external sites (e.g. the JDK docs behind every `String`), `javascript:`,
   * anchors within the page itself, and anything outside the jar.
   */
  def resolveLink(pagePath: String, href: String): Option[String] =
    val (target, anchor) = href.span(_ != '#')
    if target.isEmpty || target.startsWith("/") || scheme.findFirstIn(target).isDefined then None
    else
      val page = normalizePath(pagePath)
      val dir  = page.substring(0, page.lastIndexOf('/') + 1)
      normalizeSegments(dir + target).filter(_ != page).map(_ + anchor)

  /** Drops link titles ("class or interface in java.lang") and unwraps links [[resolveLink]]
   *  rejects, leaving their text; with [[LinkBase.Jar]] the rest are rewritten to jar paths. */
  private def simplifyLinks(root: Element, pagePath: String, linkBase: LinkBase): Unit =
    root.select("a").asScala.foreach: link =>
      resolveLink(pagePath, link.attr("href")) match
        case None => link.unwrap()
        case Some(target) =>
          link.removeAttr("title")
          linkBase match
            case LinkBase.Jar  => link.attr("href", target)
            case LinkBase.Page => ()

  def javaDocTextSymbolContents(contents: String, pagePath: String, linkBase: LinkBase): Option[String] =
    val document = Jsoup.parse(contents)
    document.select(javadocChrome).remove()
    simplifyLinks(document.body(), pagePath, linkBase)
    Option(HtmlToMarkdown.convert(document.outerHtml()).content())

  def scalaDocTextSymbolContents(contents: String, pagePath: String, linkBase: LinkBase): Option[String] =
    val document = Jsoup.parse(contents)
    val contentRoot = Option(document.selectFirst("#content > div"))
      .getOrElse(document.body())
    simplifyLinks(contentRoot, pagePath, linkBase)
    Option(HtmlToMarkdown.convert(contentRoot.outerHtml()).content())

  def javadocSymbolContents(groupArtifactVersion: GroupArtifactVersion, path: String, linkBase: LinkBase):
      ZIO[JavadocCache & Client & MavenCentralRepo, JarError | JavadocFileNotFound | JavadocContentError, String] =
    defer:
      val cache    = ZIO.service[JavadocCache].run
      val handle   = cache.get(groupArtifactVersion).run
      val contents = readEntryString(handle, groupArtifactVersion, path).run

      val maybeResult =
        if contents.contains("Generated by javadoc") then javaDocTextSymbolContents(contents, path, linkBase)
        else if contents.contains("<div id=\"content\" class=\"body-medium\"") then scalaDocTextSymbolContents(contents, path, linkBase)
        else Some(contents)

      ZIO.fromOption(maybeResult).mapError(_ => JavadocContentError(groupArtifactVersion, path)).run

  /** Raw bytes for a single jar entry, used by the HTTP file-serving path
   *  in `Web.scala` (response body comes from these bytes). */
  def javadocEntryBytes(groupArtifactVersion: GroupArtifactVersion, path: String):
      ZIO[JavadocCache & Client & MavenCentralRepo, JarError | JavadocFileNotFound, Array[Byte]] =
    defer:
      val cache  = ZIO.service[JavadocCache].run
      val handle = cache.get(groupArtifactVersion).run
      handle.readEntry(normalizePath(path))
        .mapError(_ => JavadocFileNotFound(groupArtifactVersion, path))
        .run

  /** Direct access to a javadoc jar's handle. Used when callers need
   *  multiple entries / metadata. */
  def javadocJar(groupArtifactVersion: GroupArtifactVersion):
      ZIO[JavadocCache & Client & MavenCentralRepo, JarError, JarCache.JarHandle] =
    ZIO.serviceWithZIO[JavadocCache](_.get(groupArtifactVersion))

  /**
   * The page an `index.html` stands in for, when it has no content of its own:
   * frames-era javadoc (Java 8 and earlier) makes it a frameset whose `classFrame` holds
   * the overview, and modern javadoc of a single package or module makes it a
   * `Refresh` redirect. Converting either verbatim gives an agent only a "Frame Alert"
   * or a bare link.
   */
  def indexTarget(indexHtml: String): Option[String] =
    val document = Jsoup.parse(indexHtml)
    val framed = Option(document.selectFirst("frame[name=classFrame]")).map(_.attr("src"))
    val redirected = Option(document.selectFirst("meta[http-equiv~=(?i)refresh]"))
      .map(_.attr("content").dropWhile(_ != ';').drop(1).trim.replaceFirst("(?i)^url=", ""))
    framed.orElse(redirected).flatMap(target => resolveLink("index.html", target))

  /** MCP convenience: the javadoc jar's index page as markdown, following
   *  [[indexTarget]] to the real overview when `index.html` is only a shell. */
  def index(groupArtifactVersion: GroupArtifactVersion):
      ZIO[JavadocCache & Client & MavenCentralRepo, JarError | JavadocFileNotFound | JavadocContentError, String] =
    defer:
      val handle    = javadocJar(groupArtifactVersion).run
      val indexHtml = readEntryString(handle, groupArtifactVersion, "index.html").run
      val page      = indexTarget(indexHtml).getOrElse("index.html")
      javadocSymbolContents(groupArtifactVersion, page, LinkBase.Jar).run

  def sourceFileContents(groupArtifactVersion: GroupArtifactVersion, path: String):
      ZIO[SourcesCache & Client & MavenCentralRepo, JarError | JavadocFileNotFound, String] =
    defer:
      val cache  = ZIO.service[SourcesCache].run
      val handle = cache.get(groupArtifactVersion).run
      readEntryString(handle, groupArtifactVersion, path).run
