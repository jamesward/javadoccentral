import SymbolSearch.SearchError
import com.jamesward.ziohttp.mcp.*
import com.jamesward.zio_mavencentral.MavenCentral.*
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

  @description("A pointer to one documented page or source file inside an artifact's jar.")
  case class JavadocSymbol(
    @description("The Maven groupId, e.g. \"dev.zio\".")
    groupId: GroupId,
    @description("The Maven artifactId, e.g. \"zio_3\".")
    artifactId: ArtifactId,
    @description("The artifact version, e.g. \"2.1.9\".")
    version: Version,
    @description("The relative path/link within the jar, as returned by get_javadoc_content_list or list_source_contents.")
    link: String,
  ) derives Schema

  // McpError instances for domain error types
  given latestError: McpError[GroupIdOrArtifactIdNotFoundError | Extractor.LatestNotFound] with
    def message(e: GroupIdOrArtifactIdNotFoundError | Extractor.LatestNotFound): String = e.toString

  given notFoundError: McpError[Extractor.JarError] with
    def message(e: Extractor.JarError): String = e.toString

  given fileNotFoundError: McpError[Extractor.JarError | Extractor.JavadocFileNotFound] with
    def message(e: Extractor.JarError | Extractor.JavadocFileNotFound): String = e.toString

  given fileNotFoundOrContentError: McpError[Extractor.JarError | Extractor.JavadocFileNotFound | Extractor.JavadocContentError] with
    def message(e: Extractor.JarError | Extractor.JavadocFileNotFound | Extractor.JavadocContentError): String = e.toString

  given searchError: McpError[SearchError] with
    def message(e: SearchError): String = e.message

  given throwableError: McpError[Throwable] with
    def message(e: Throwable): String = e.getMessage

  private def logMcp[R, E, A](toolName: String, params: String)(effect: ZIO[R, E, A]): ZIO[R, E, A] =
    defer:
      ZIO.logInfo(s"MCP tool call: tool=$toolName params=$params").run
      effect
        .tapError(e => ZIO.logWarning(s"MCP tool error: tool=$toolName error=$e"))
        .tapDefect(c => ZIO.logError(s"MCP tool defect: tool=$toolName cause=${c.prettyPrint}"))
        .run

  // Tool/operation descriptions — the single source of truth shared by both the
  // MCP tools below and the REST Endpoints in Api.scala.
  object Descriptions:
    val getLatest: String =
      "Resolves the latest published version of a Maven Central artifact (any " +
      "groupId:artifactId — Java, Kotlin, or Scala library). " +
      "Call this first when you only know the artifact but not the version: " +
      "the version it returns feeds into every other tool here that takes a " +
      "concrete version. Works against the live Maven Central catalog — no " +
      "local install, build tool, or repository checkout required."

    val getIndex: String =
      "Fetches the rendered Javadoc/Scaladoc index page for a specific " +
      "Maven Central artifact version, converted to plain text/markdown. " +
      "Useful for orienting yourself in an unfamiliar library: it lists the " +
      "top-level packages, modules, and (for Scaladoc) often a curated " +
      "overview. Use this before drilling into specific symbols. " +
      "Works against the live Maven Central catalog — you do not need to " +
      "download the javadoc jar."

    val getClasses: String =
      "Lists every entry in the Javadoc/Scaladoc jar of a Maven Central " +
      "artifact version (HTML pages for classes/methods/packages, plus " +
      "search-index files and resources). Each returned `link` can be " +
      "passed to get_javadoc_symbol_contents to read the rendered API doc " +
      "as text/markdown. " +
      "Use this when you need to discover what a library documents and " +
      "then read those docs without leaving the agent loop. " +
      "If this returns NotFoundError (some libraries don't publish a " +
      "javadoc jar — e.g. some ZIO releases) fall back to " +
      "list_source_contents to read the raw source instead."

    val getSymbolContents: String =
      "Reads one Javadoc/Scaladoc page from a Maven Central artifact, " +
      "already converted to plain text/markdown so you don't have to parse " +
      "HTML. Pass the `link` value returned by get_javadoc_content_list. " +
      "Use this when you need to know what a class, method, or field does " +
      "and what its parameters mean for any library on Maven Central — " +
      "without downloading or unzipping the javadoc jar yourself."

    val listSource: String =
      "Lists every file inside the **sources jar** (the `-sources.jar` " +
      "publishers attach alongside the binary) of a Maven Central artifact " +
      "version. Each returned path can be fed to get_source_contents to " +
      "read the file. " +
      "Prefer this any time you would otherwise locate a `-sources.jar` " +
      "in your local Coursier/Ivy/Maven cache and `unzip` it: this tool " +
      "works directly against Maven Central, requires no local install or " +
      "build, and works for libraries you've never depended on. " +
      "Use it whenever you need to read the actual source of a JVM library " +
      "(Java, Kotlin, Scala) — for example to understand an implementation " +
      "detail, find where a method is defined, see how a feature is wired " +
      "internally, or work with a library that doesn't publish javadocs."

    val getSource: String =
      "Reads one source file from a Maven Central library's sources jar " +
      "(the `-sources.jar` artifact). Pass the `link` value returned by " +
      "list_source_contents. " +
      "Use this whenever you need the exact source text of a JVM library " +
      "— tracing behavior into a dependency, confirming a public API's " +
      "implementation, finding a definition, or comparing two library " +
      "versions. Strongly preferred over locating the jar in a local " +
      "build cache and unzipping it: it works for any Maven Central " +
      "artifact, no local checkout or build needed."

    val searchArtifacts: String =
      "Searches the indexed Maven Central catalog for artifacts whose " +
      "groupId or artifactId contains a substring (case-insensitive). " +
      "Use this when you know part of a library name (e.g. \"jackson\", " +
      "\"zio-http\", \"netty-codec\") and need the exact " +
      "groupId:artifactId coordinates to feed into the other tools. " +
      "Pair with get_latest_version once you've picked an artifact."

    val symbolToArtifact: String =
      "Resolves a class name, fully-qualified type, or package name to " +
      "the Maven Central artifact (groupId, artifactId) that publishes " +
      "it. Case-sensitive. " +
      "Use this when you have a symbol from a stack trace, an import " +
      "line, or an error message and you need to know which library to " +
      "look in. From there, chain into get_latest_version, then " +
      "list_source_contents / get_javadoc_content_list to read the code " +
      "or docs."

  val getLatestTool = McpTool("get_latest_version")
    .description(Descriptions.getLatest)
    .annotations(readOnly = True, destructive = False, idempotent = True, openWorld = True)
    .handle: (input: GroupArtifact) =>
      logMcp("get_latest_version", input.toString):
        Extractor.latest(input)

  val getIndexTool = McpTool("get_javadoc_index")
    .description(Descriptions.getIndex)
    .annotations(readOnly = True, destructive = False, idempotent = True, openWorld = True)
    .handle: (input: GroupArtifactVersion) =>
      logMcp("get_javadoc_index", input.toString):
        ZIO.scoped:
          defer:
            SymbolSearch.indexJavadocContents(input).run
            Extractor.index(input).run

  val getClassesTool = McpTool("get_javadoc_content_list")
    .description(Descriptions.getClasses)
    .annotations(readOnly = True, destructive = False, idempotent = True, openWorld = True)
    .handle: (input: GroupArtifactVersion) =>
      logMcp("get_javadoc_content_list", input.toString):
        ZIO.scoped:
          defer:
            SymbolSearch.indexJavadocContents(input).run
            Extractor.javadocContents(input).run

  val getSymbolContentsTool = McpTool("get_javadoc_symbol_contents")
    .description(Descriptions.getSymbolContents)
    .annotations(readOnly = True, destructive = False, idempotent = True, openWorld = True)
    .handle: (input: JavadocSymbol) =>
      val groupArtifactVersion = GroupArtifactVersion(input.groupId, input.artifactId, input.version)
      logMcp("get_javadoc_symbol_contents", input.toString):
        ZIO.scoped:
          Extractor.javadocSymbolContents(groupArtifactVersion, input.link)

  val listSourceTool = McpTool("list_source_contents")
    .description(Descriptions.listSource)
    .annotations(readOnly = True, destructive = False, idempotent = True, openWorld = True)
    .handle: (input: GroupArtifactVersion) =>
      logMcp("list_source_contents", input.toString):
        ZIO.scoped:
          Extractor.sourceContents(input)

  val getSourceTool = McpTool("get_source_contents")
    .description(Descriptions.getSource)
    .annotations(readOnly = True, destructive = False, idempotent = True, openWorld = True)
    .handle: (input: JavadocSymbol) =>
      val groupArtifactVersion = GroupArtifactVersion(input.groupId, input.artifactId, input.version)
      logMcp("get_source_contents", input.toString):
        ZIO.scoped:
          Extractor.sourceFileContents(groupArtifactVersion, input.link)

  val searchArtifactsTool = McpTool("search_artifacts")
    .description(Descriptions.searchArtifacts)
    .annotations(readOnly = True, destructive = False, idempotent = True, openWorld = True)
    .handle: (input: Symbol) =>
      logMcp("search_artifacts", input.query):
        SymbolSearch.searchGroupArtifacts(input.query)

  val symbolToArtifactTool = McpTool("symbol_to_artifact")
    .description(Descriptions.symbolToArtifact)
    .annotations(readOnly = True, destructive = False, idempotent = False, openWorld = True)
    .handle: (input: Symbol) =>
      logMcp("symbol_to_artifact", input.query):
        // todo: rate limit
        SymbolSearch.search(input.query)

  val mcpServer = McpServer("javadocs.dev", "0.0.2")
    .tool(getLatestTool)
    .tool(getIndexTool)
    .tool(getClassesTool)
    .tool(getSymbolContentsTool)
    .tool(getSourceTool)
    .tool(listSourceTool)
    .tool(searchArtifactsTool)
    .tool(symbolToArtifactTool)
