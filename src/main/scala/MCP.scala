import SymbolSearch.SearchError
import com.jamesward.ziohttp.mcp.*
import com.jamesward.zio_mavencentral.MavenCentral.*
import zio.*
import zio.direct.*
import zio.schema.{DeriveSchema, Schema, derived}
import com.jamesward.ziohttp.mcp.OptBool.*

object MCP:

  // Schema instances for MavenCentral opaque types (for MCP tool input deserialization)
  given Schema[GroupId] = Schema.primitive[String].transform(GroupId(_), _.toString)
  given Schema[ArtifactId] = Schema.primitive[String].transform(ArtifactId(_), _.toString)
  given Schema[Version] = Schema.primitive[String].transform(Version(_), _.toString)
  given Schema[GroupArtifact] = DeriveSchema.gen[GroupArtifact]
  given Schema[GroupArtifactVersion] = DeriveSchema.gen[GroupArtifactVersion]
  given Schema[Extractor.Content] = DeriveSchema.gen[Extractor.Content]

  case class Symbol(query: String) derives Schema
  case class JavadocSymbol(groupId: GroupId, artifactId: ArtifactId, version: Version, link: String) derives Schema

  // McpError instances for domain error types
  given latestError: McpError[GroupIdOrArtifactIdNotFoundError | Extractor.LatestNotFound] with
    def message(e: GroupIdOrArtifactIdNotFoundError | Extractor.LatestNotFound): String = e.toString

  given notFoundError: McpError[NotFoundError] with
    def message(e: NotFoundError): String = e.toString

  given fileNotFoundError: McpError[NotFoundError | Extractor.JavadocFileNotFound] with
    def message(e: NotFoundError | Extractor.JavadocFileNotFound): String = e.toString

  given searchError: McpError[SearchError] with
    def message(e: SearchError): String = e.message

  given throwableError: McpError[Throwable] with
    def message(e: Throwable): String = e.getMessage

  val getLatestTool = McpTool("get_latest_version")
    .description("Gets the latest version of a given artifact")
    .annotations(readOnly = True, destructive = False, idempotent = True, openWorld = True)
    .handle: (input: GroupArtifact) =>
      ZIO.scoped:
        Extractor.latest(input)

  val getIndexTool = McpTool("get_index")
    .description("Gets the index from the javadocs for a given Maven Central library artifact - often the index provides helpful reference documentation")
    .annotations(readOnly = True, destructive = False, idempotent = True, openWorld = True)
    .handle: (input: GroupArtifactVersion) =>
      ZIO.scoped:
        defer:
          App.indexJavadocContents(input).run
          Extractor.index(input).run

  val getClassesTool = McpTool("get_javadoc_content_list")
    .description("Gets a list of the contents of a javadoc jar")
    .annotations(readOnly = True, destructive = False, idempotent = True, openWorld = True)
    .handle: (input: GroupArtifactVersion) =>
      ZIO.scoped:
        defer:
          App.indexJavadocContents(input).run
          Extractor.javadocContents(input).run

  val getSymbolContentsTool = McpTool("get_javadoc_symbol_contents")
    .description("Gets the contents of a javadoc symbol. Get the symbol link from the get_javadoc_content_list tool.")
    .annotations(readOnly = True, destructive = False, idempotent = True, openWorld = True)
    .handle: (input: JavadocSymbol) =>
      val groupArtifactVersion = GroupArtifactVersion(input.groupId, input.artifactId, input.version)
      ZIO.scoped:
        Extractor.javadocSymbolContents(groupArtifactVersion, input.link)

  val listSourceTool = McpTool("list_source_contents")
    .description("Gets a list of the contents of a source jar")
    .annotations(readOnly = True, destructive = False, idempotent = True, openWorld = True)
    .handle: (input: GroupArtifactVersion) =>
      ZIO.scoped:
        Extractor.sourceContents(input)

  val getSourceTool = McpTool("get_source_contents")
    .description("Gets the contents of a source file. Get the list of files from the list_source_contents tool.")
    .annotations(readOnly = True, destructive = False, idempotent = True, openWorld = True)
    .handle: (input: JavadocSymbol) =>
      val groupArtifactVersion = GroupArtifactVersion(input.groupId, input.artifactId, input.version)
      ZIO.scoped:
        Extractor.sourceFileContents(groupArtifactVersion, input.link)

  val searchArtifactsTool = McpTool("search_artifacts")
    .description("Searches indexed Maven Central library artifacts by partial group id or artifact id (case insensitive)")
    .annotations(readOnly = True, destructive = False, idempotent = True, openWorld = True)
    .handle: (input: Symbol) =>
      SymbolSearch.searchGroupArtifacts(input.query)

  val symbolToArtifactTool = McpTool("symbol_to_artifact")
    .description("Gets the group and artifact for a given symbol, class, or package. Symbol search is case sensitive.")
    .annotations(readOnly = True, destructive = False, idempotent = False, openWorld = True)
    .handle: (input: Symbol) =>
      ZIO.scoped:
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
