import SymbolSearch.SearchError
import com.jamesward.ziohttp.mcp.*
import com.jamesward.zio_mavencentral.MavenCentral.*
import com.jamesward.zio_mavencentral.JarCache
import zio.*
import zio.direct.*
import zio.schema.{Schema, derived}
import zio.schema.annotation.description
import com.jamesward.ziohttp.mcp.OptBool.*

object MCP:

  // Opaque-type + coordinate record schemas come from zio-mavencentral:
  // GroupId/ArtifactId/Version via MavenCentralSchemas, GroupArtifact/
  // GroupArtifactVersion (record, with field descriptions) via their companions.
  // Content (its own non-obvious fields) is a described record from Extractor.scala.
  import com.jamesward.zio_mavencentral.MavenCentralSchemas.given

  @description("A search query.")
  case class Symbol(
    @description("The text to search for — e.g. a class/type name, a package, or part of a groupId/artifactId.")
    query: String,
  ) derives Schema

  @description("A Maven Central artifact (groupId and artifactId) whose latest version to resolve.")
  case class LatestVersionQuery(
    @description("The Maven groupId, e.g. \"dev.zio\".")
    groupId: GroupId,
    @description("The Maven artifactId, e.g. \"zio_3\".")
    artifactId: ArtifactId,
    @description("Optional, default false. When true, also consider pre-releases (milestones, RCs, betas, " +
      "alphas, snapshots), e.g. to find a new API that only exists in a milestone. When false, returns the " +
      "latest release (or the newest pre-release if the artifact has no release at all).")
    includePreReleases: Option[Boolean] = None,
  ) derives Schema:
    def groupArtifact: GroupArtifact = GroupArtifact(groupId, artifactId)

  @description("A Maven Central artifact version whose documented symbols to list, optionally narrowed by name.")
  case class JavadocSymbolsQuery(
    @description("The Maven groupId, e.g. \"dev.zio\".")
    groupId: GroupId,
    @description("The Maven artifactId, e.g. \"zio_3\".")
    artifactId: ArtifactId,
    @description("The artifact version, e.g. \"2.1.9\".")
    version: Version,
    @description("Optional. Only return symbols whose fully-qualified name contains this text (case-insensitive; " +
      "several space-separated terms must all match), e.g. \"PolymorphicTypeValidator\" or \"judge Builder\". " +
      "Omit to list every symbol.")
    filter: Option[String] = None,
  ) derives Schema:
    def gav: GroupArtifactVersion = GroupArtifactVersion(groupId, artifactId, version)

  @description("A pointer to one documented page or source file inside an artifact's jar.")
  case class JavadocSymbol(
    @description("The Maven groupId, e.g. \"dev.zio\".")
    groupId: GroupId,
    @description("The Maven artifactId, e.g. \"zio_3\".")
    artifactId: ArtifactId,
    @description("The artifact version, e.g. \"2.1.9\".")
    version: Version,
    @description("The relative path/link within the jar, as returned by list_javadoc_symbols or list_source_files.")
    link: String,
  ) derives Schema

  // Every tool error is turned into an agent-facing message by ErrorMessages
  // (shared with the REST API), so the MCP surface only ever fails with a String.
  given McpError[String] with
    def message(e: String): String = e

  /**
   * Agent-facing error messages, shared by the MCP tools and the REST API. The
   * domain errors' `toString` (`GroupIdOrArtifactIdNotFoundError(com.example,nope)`)
   * says what failed but not why or what to do, so agents retried blindly. Each
   * message names the coordinates, the likely cause, and the tool to try next.
   */
  object ErrorMessages:
    enum Jar(val name: String, val listTool: String, val alternativeListTool: String):
      case Javadoc extends Jar("javadoc", "list_javadoc_symbols", "list_source_files")
      case Sources extends Jar("sources", "list_source_files", "list_javadoc_symbols")

    private def coordinates(gav: GroupArtifactVersion): String =
      s"${gav.groupId}:${gav.artifactId}:${gav.version}"

    def latest(e: GroupIdOrArtifactIdNotFoundError | Extractor.LatestNotFound): String = e match
      case e: GroupIdOrArtifactIdNotFoundError =>
        s"Maven Central has no artifact ${e.groupId}:${e.artifactId}. Check the spelling, or use " +
          "search_artifacts to find the exact groupId:artifactId (Scala artifactIds usually carry a " +
          "suffix such as _3 or _2.13)."
      case e: Extractor.LatestNotFound =>
        s"${e.groupArtifact.groupId}:${e.groupArtifact.artifactId} exists on Maven Central, but no " +
          "version could be resolved from its metadata. Use search_artifacts to check whether it moved " +
          "to another groupId."

    def jar(kind: Jar)(e: Extractor.JarError): String = e match
      case e: NotFoundError =>
        s"No ${kind.name} jar on Maven Central for ${e.groupId}:${e.artifactId}:${e.version}. Either " +
          "that version does not exist (use get_latest_version to get one that does) or the artifact " +
          s"does not publish a ${kind.name} jar (try ${kind.alternativeListTool} instead)."
      case e: JarCache.UpstreamCorruptError =>
        s"The ${kind.name} jar for ${coordinates(e.gav)} downloaded from Maven Central could not be " +
          s"read (${e.reason}). Retry later, or use another version."
      case e: JarCache.UpstreamUnavailableError =>
        s"Maven Central could not be reached to fetch the ${kind.name} jar for ${coordinates(e.gav)} " +
          s"(${e.reason}). This is a temporary upstream problem, not a problem with the request: retry " +
          "the same call shortly."

    def entry(kind: Jar)(e: Extractor.JarError | Extractor.JavadocFileNotFound | Extractor.JavadocContentError): String =
      e match
        case e: Extractor.JavadocFileNotFound =>
          s"The ${kind.name} jar for ${coordinates(e.groupArtifactVersion)} has no entry '${e.path}'. " +
            s"Pass a `link` exactly as returned by ${kind.listTool} for this same version."
        case e: Extractor.JavadocContentError =>
          s"The page '${e.path}' in the ${kind.name} jar for ${coordinates(e.groupArtifactVersion)} " +
            "could not be converted to text."
        case e: (NotFoundError | JarCache.UpstreamCorruptError | JarCache.UpstreamUnavailableError) => jar(kind)(e)

    def search(e: SearchError): String = e.message

    def artifactSearch(e: Throwable): String =
      s"The artifact search index is unavailable (${e.getMessage}). Retry later."

  // The info-level "tool call" log now comes from zio-http-mcp's own
  // `MCP tools/call` log (tool name + arguments as structured fields), so we no
  // longer emit one here — just app-side error/defect logging, with the params
  // for debugging context.
  private def logMcp[R, E, A](toolName: String, params: String)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    effect
      .tapError(e => ZIO.logAnnotate(
        LogAnnotation("tool", toolName),
        LogAnnotation("params", params),
        LogAnnotation("error", e.toString),
      )(ZIO.logWarning("MCP tool error")))
      .tapDefect(c => ZIO.logAnnotate(
        LogAnnotation("tool", toolName),
        LogAnnotation("params", params),
      )(ZIO.logErrorCause("MCP tool defect", c)))

  // Tool/operation descriptions — the single source of truth shared by both the
  // MCP tools below and the REST Endpoints in Api.scala.
  object Descriptions:
    val getLatest: String =
      "Resolves the latest published version of a Maven Central artifact (any " +
      "groupId:artifactId — Java, Kotlin, or Scala library). " +
      "Call this first when you only know the artifact but not the version: " +
      "the version it returns feeds into every other tool here that takes a " +
      "concrete version. By default it returns the latest release, sorted by " +
      "Maven version order and skipping pre-releases; set " +
      "`includePreReleases` to true to get the newest version of any kind " +
      "(milestone, RC, beta...), e.g. when a class or method you are looking " +
      "for is missing from the latest release. Works against the live Maven Central catalog — no " +
      "local install, build tool, or repository checkout required."

    val getIndex: String =
      "Fetches the rendered Javadoc/Scaladoc index page for a specific " +
      "Maven Central artifact version, converted to plain text/markdown. " +
      "Useful for orienting yourself in an unfamiliar library: it lists the " +
      "top-level packages, modules, and (for Scaladoc) often a curated " +
      "overview. Use this before drilling into specific symbols. " +
      "Works against the live Maven Central catalog — you do not need to " +
      "download the javadoc jar."

    val listJavadocSymbols: String =
      "Enumerates the public API a library documents — classes, " +
      "interfaces, traits, objects, enums, and annotations — for a Maven " +
      "Central artifact version. Each entry includes the fully-qualified " +
      "class name (`fqn`) and a `link` that can be passed to " +
      "get_javadoc_symbol to read the rendered API documentation. " +
      "Use this to answer 'which classes/types does library X have?' or " +
      "'find classes related to <topic>' — it is the fastest way to list " +
      "the API by name without reading code. Prefer this over " +
      "list_source_files whenever you only need class/type names. Pass " +
      "`filter` (e.g. a class name) to get only the matching entries: large " +
      "libraries list hundreds of symbols, so filtering saves a lot of " +
      "context when you are looking for one type. Only " +
      "if this returns NotFoundError because no javadoc jar was published, " +
      "fall back to list_source_files. Works against the live Maven " +
      "Central catalog with no local install, build, or checkout required."

    val getJavadocSymbol: String =
      "Reads one Javadoc/Scaladoc page from a Maven Central artifact, " +
      "already converted to plain text/markdown so you don't have to parse " +
      "HTML. Pass the `link` value returned by list_javadoc_symbols. " +
      "Navigation and links to external sites are stripped; the links that " +
      "remain point at other pages of the same jar and can be passed back " +
      "as `link` to follow them. " +
      "Use this when you need to know what a class, method, or field does " +
      "and what its parameters mean for any library on Maven Central — " +
      "without downloading or unzipping the javadoc jar yourself."

    val listSourceFiles: String =
      "Lists the raw source files (`.java`, `.kt`, and `.scala`) in a Maven " +
      "Central artifact version's `-sources.jar`. Each returned path can be " +
      "passed to get_source_file to read the actual source code. Use " +
      "this only when you need the implementation — for example, to trace " +
      "behavior or see how something is wired. To discover which classes " +
      "exist, or to find classes by topic or name, use " +
      "list_javadoc_symbols instead; do not use this tool just to " +
      "enumerate classes. Works against the live Maven Central catalog " +
      "with no local install, build, or checkout required."

    val getSourceFile: String =
      "Reads one source file from a Maven Central library's sources jar " +
      "(the `-sources.jar` artifact). Pass the `link` value returned by " +
      "list_source_files. " +
      "Use this whenever you need the exact source text of a JVM library " +
      "— tracing behavior into a dependency, confirming a public API's " +
      "implementation, finding a definition, or comparing two library " +
      "versions. Strongly preferred over locating the jar in a local " +
      "build cache and unzipping it: it works for any Maven Central " +
      "artifact, no local checkout or build needed."

    val searchArtifacts: String =
      "Finds Maven coordinates from part of a library name: searches the " +
      "javadocs.dev index of Maven Central artifacts for groupId:artifactId " +
      "values containing every word of the query (case-insensitive). " +
      "Use this when you know part of a library name (e.g. \"jackson\", " +
      "\"zio-http\", \"netty-codec\") and need the exact " +
      "groupId:artifactId coordinates to feed into the other tools. " +
      "Query with name fragments only — versions are not part of the " +
      "match. The same artifactId can be published under several groupIds " +
      "(e.g. jackson-databind: com.fasterxml.jackson.core for 2.x, " +
      "tools.jackson.core for 3.x), so check each candidate with " +
      "get_latest_version. To find the library that contains a class, use " +
      "symbol_to_artifact instead."

    val symbolToArtifact: String =
      "Finds which Maven artifact contains a given Java/Kotlin/Scala class: " +
      "resolves a class name, nested type (e.g. Outer.Builder), " +
      "fully-qualified type, or package name to the Maven Central artifacts " +
      "(groupId, artifactId) that publish it. Exact case is preferred; a " +
      "case-insensitive match is tried if that finds nothing. Several " +
      "artifacts can contain the same simple name (e.g. a Java and a Scala " +
      "library): check each result rather than assuming the first. " +
      "Use this when you have a symbol from a stack trace, an import " +
      "line, or an error message and you need to know which library to " +
      "look in. From there, chain into get_latest_version, then " +
      "list_source_files / list_javadoc_symbols to read the code " +
      "or docs."

  val getLatestTool = McpTool("get_latest_version")
    .description(Descriptions.getLatest)
    .annotations(title = Some("Get latest version"), readOnly = True, destructive = False, idempotent = True, openWorld = True)
    .handle: (input: LatestVersionQuery) =>
      logMcp("get_latest_version", input.toString):
        Extractor.latest(input.groupArtifact, input.includePreReleases.getOrElse(false))
      .mapError(ErrorMessages.latest)

  val getIndexTool = McpTool("get_javadoc_index")
    .description(Descriptions.getIndex)
    .annotations(title = Some("Get javadoc index page"), readOnly = True, destructive = False, idempotent = True, openWorld = True)
    .handle: (input: GroupArtifactVersion) =>
      logMcp("get_javadoc_index", input.toString):
        ZIO.scoped:
          defer:
            SymbolSearch.indexJavadocContents(input).run
            Extractor.index(input).run
      .mapError(ErrorMessages.entry(ErrorMessages.Jar.Javadoc))

  val listJavadocSymbolsTool = McpTool("list_javadoc_symbols")
    .description(Descriptions.listJavadocSymbols)
    .annotations(title = Some("List documented classes"), readOnly = True, destructive = False, idempotent = True, openWorld = True)
    .handle: (input: JavadocSymbolsQuery) =>
      logMcp("list_javadoc_symbols", input.toString):
        ZIO.scoped:
          defer:
            SymbolSearch.indexJavadocContents(input.gav).run
            Extractor.filterContents(Extractor.javadocContents(input.gav).run, input.filter)
      .mapError(ErrorMessages.jar(ErrorMessages.Jar.Javadoc))

  val getJavadocSymbolTool = McpTool("get_javadoc_symbol")
    .description(Descriptions.getJavadocSymbol)
    .annotations(title = Some("Read javadoc page"), readOnly = True, destructive = False, idempotent = True, openWorld = True)
    .handle: (input: JavadocSymbol) =>
      val groupArtifactVersion = GroupArtifactVersion(input.groupId, input.artifactId, input.version)
      logMcp("get_javadoc_symbol", input.toString):
        ZIO.scoped:
          Extractor.javadocSymbolContents(groupArtifactVersion, input.link, Extractor.LinkBase.Jar)
      .mapError(ErrorMessages.entry(ErrorMessages.Jar.Javadoc))

  val listSourceFilesTool = McpTool("list_source_files")
    .description(Descriptions.listSourceFiles)
    .annotations(title = Some("List source files"), readOnly = True, destructive = False, idempotent = True, openWorld = True)
    .handle: (input: GroupArtifactVersion) =>
      logMcp("list_source_files", input.toString):
        ZIO.scoped:
          Extractor.sourceContents(input)
      .mapError(ErrorMessages.jar(ErrorMessages.Jar.Sources))

  val getSourceFileTool = McpTool("get_source_file")
    .description(Descriptions.getSourceFile)
    .annotations(title = Some("Read source file"), readOnly = True, destructive = False, idempotent = True, openWorld = True)
    .handle: (input: JavadocSymbol) =>
      val groupArtifactVersion = GroupArtifactVersion(input.groupId, input.artifactId, input.version)
      logMcp("get_source_file", input.toString):
        ZIO.scoped:
          Extractor.sourceFileContents(groupArtifactVersion, input.link)
      .mapError(ErrorMessages.entry(ErrorMessages.Jar.Sources))

  val searchArtifactsTool = McpTool("search_artifacts")
    .description(Descriptions.searchArtifacts)
    .annotations(title = Some("Search artifacts by name"), readOnly = True, destructive = False, idempotent = True, openWorld = True)
    .handle: (input: Symbol) =>
      logMcp("search_artifacts", input.query):
        SymbolSearch.searchGroupArtifacts(input.query)
      .mapError(ErrorMessages.artifactSearch)

  val symbolToArtifactTool = McpTool("symbol_to_artifact")
    .description(Descriptions.symbolToArtifact)
    .annotations(title = Some("Find artifact for a class"), readOnly = True, destructive = False, idempotent = False, openWorld = True)
    .handle: (input: Symbol) =>
      logMcp("symbol_to_artifact", input.query):
        // todo: rate limit
        SymbolSearch.search(input.query)
      .mapError(ErrorMessages.search)

  val mcpServer = McpServer("javadocs.dev", "0.0.2")
    .tool(getLatestTool)
    .tool(getIndexTool)
    .tool(listJavadocSymbolsTool)
    .tool(getJavadocSymbolTool)
    .tool(getSourceFileTool)
    .tool(listSourceFilesTool)
    .tool(searchArtifactsTool)
    .tool(symbolToArtifactTool)
