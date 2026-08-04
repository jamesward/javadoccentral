import com.jamesward.zio_mavencentral.MavenCentral.{GroupId, ArtifactId, Version, GroupArtifact, GroupArtifactVersion}
import com.jamesward.zio_mavencentral.MavenCentralSchemas.given
import zio.*
import zio.direct.*
import zio.http.{Version as _, *}
import zio.http.codec.Doc
import zio.http.codec.PathCodec.path
import zio.http.endpoint.Endpoint
import zio.http.endpoint.openapi.{OpenAPIGen, SwaggerUI}
import zio.schema.{Schema, derived}

/**
 * A read-only REST surface (`GET` + query params) that mirrors the MCP tools in
 * [[MCP]]. It reuses the very same input/output types + `Schema`s and the shared
 * `MCP.Descriptions`, so the generated OpenAPI stays in lockstep with the MCP
 * tool schemas. OpenAPI is generated from the endpoints (`/openapi.json`) and
 * rendered by SwaggerUI at `/api/doc`.
 */
object Api:

  case class ApiError(message: String) derives Schema

  // Every domain error becomes a 404 ApiError (the resource/artifact/doc could
  // not be produced); unexpected defects still surface as 500 via the framework.
  private val toApiError: Any => ApiError = e => ApiError(e.toString)

  // ---- Endpoints (metadata only; implemented into Routes below) ----

  val latestEndpoint =
    Endpoint(Method.GET / "api" / "latest-version")
      .query[GroupId]("groupId")
      .query[ArtifactId]("artifactId")
      .out[Version]
      .outError[ApiError](Status.NotFound)
      .??(Doc.p(MCP.Descriptions.getLatest))

  val indexEndpoint =
    Endpoint(Method.GET / "api" / "javadoc-index")
      .query[GroupId]("groupId")
      .query[ArtifactId]("artifactId")
      .query[Version]("version")
      .out[String]
      .outError[ApiError](Status.NotFound)
      .??(Doc.p(MCP.Descriptions.getIndex))

  val listJavadocSymbolsEndpoint =
    Endpoint(Method.GET / "api" / "list-javadoc-symbols")
      .query[GroupId]("groupId")
      .query[ArtifactId]("artifactId")
      .query[Version]("version")
      .out[Set[Extractor.Content]]
      .outError[ApiError](Status.NotFound)
      .??(Doc.p(MCP.Descriptions.listJavadocSymbols))

  val javadocSymbolEndpoint =
    Endpoint(Method.GET / "api" / "javadoc-symbol")
      .query[GroupId]("groupId")
      .query[ArtifactId]("artifactId")
      .query[Version]("version")
      .query[String]("link")
      .out[String]
      .outError[ApiError](Status.NotFound)
      .??(Doc.p(MCP.Descriptions.getJavadocSymbol))

  val listSourceFilesEndpoint =
    Endpoint(Method.GET / "api" / "list-source-files")
      .query[GroupId]("groupId")
      .query[ArtifactId]("artifactId")
      .query[Version]("version")
      .out[Set[String]]
      .outError[ApiError](Status.NotFound)
      .??(Doc.p(MCP.Descriptions.listSourceFiles))

  val sourceFileEndpoint =
    Endpoint(Method.GET / "api" / "source-file")
      .query[GroupId]("groupId")
      .query[ArtifactId]("artifactId")
      .query[Version]("version")
      .query[String]("link")
      .out[String]
      .outError[ApiError](Status.NotFound)
      .??(Doc.p(MCP.Descriptions.getSourceFile))

  val searchArtifactsEndpoint =
    Endpoint(Method.GET / "api" / "search-artifacts")
      .query[String]("query")
      .out[Set[GroupArtifact]]
      .outError[ApiError](Status.NotFound)
      .??(Doc.p(MCP.Descriptions.searchArtifacts))

  val symbolToArtifactEndpoint =
    Endpoint(Method.GET / "api" / "symbol-to-artifact")
      .query[String]("query")
      .out[Set[GroupArtifact]]
      .outError[ApiError](Status.NotFound)
      .??(Doc.p(MCP.Descriptions.symbolToArtifact))

  val endpoints = List(
    latestEndpoint, indexEndpoint, listJavadocSymbolsEndpoint, javadocSymbolEndpoint,
    listSourceFilesEndpoint, sourceFileEndpoint, searchArtifactsEndpoint, symbolToArtifactEndpoint,
  )

  // ---- Implementations (call the same Extractor/SymbolSearch logic as MCP) ----

  private val latestRoute =
    latestEndpoint.implement: (input: (GroupId, ArtifactId)) =>
      val (g, a) = input
      Extractor.latest(GroupArtifact(g, a)).mapError(toApiError)

  private val indexRoute =
    indexEndpoint.implement: (input: (GroupId, ArtifactId, Version)) =>
      val (g, a, v) = input
      val gav = GroupArtifactVersion(g, a, v)
      ZIO.scoped(defer:
        SymbolSearch.indexJavadocContents(gav).run
        Extractor.index(gav).run
      ).mapError(toApiError)

  private val listJavadocSymbolsRoute =
    listJavadocSymbolsEndpoint.implement: (input: (GroupId, ArtifactId, Version)) =>
      val (g, a, v) = input
      val gav = GroupArtifactVersion(g, a, v)
      ZIO.scoped(defer:
        SymbolSearch.indexJavadocContents(gav).run
        Extractor.javadocContents(gav).run
      ).mapError(toApiError)

  private val javadocSymbolRoute =
    javadocSymbolEndpoint.implement: (input: (GroupId, ArtifactId, Version, String)) =>
      val (g, a, v, link) = input
      ZIO.scoped(Extractor.javadocSymbolContents(GroupArtifactVersion(g, a, v), link)).mapError(toApiError)

  private val listSourceFilesRoute =
    listSourceFilesEndpoint.implement: (input: (GroupId, ArtifactId, Version)) =>
      val (g, a, v) = input
      ZIO.scoped(Extractor.sourceContents(GroupArtifactVersion(g, a, v))).mapError(toApiError)

  private val sourceFileRoute =
    sourceFileEndpoint.implement: (input: (GroupId, ArtifactId, Version, String)) =>
      val (g, a, v, link) = input
      ZIO.scoped(Extractor.sourceFileContents(GroupArtifactVersion(g, a, v), link)).mapError(toApiError)

  private val searchArtifactsRoute =
    searchArtifactsEndpoint.implement: (query: String) =>
      SymbolSearch.searchGroupArtifacts(query).mapError(toApiError)

  private val symbolToArtifactRoute =
    symbolToArtifactEndpoint.implement: (query: String) =>
      SymbolSearch.search(query).mapError(toApiError)

  // ---- OpenAPI + SwaggerUI ----

  val openApi = OpenAPIGen.fromEndpoints(
    title = "javadocs.dev API",
    version = "0.0.2",
    latestEndpoint, indexEndpoint, listJavadocSymbolsEndpoint, javadocSymbolEndpoint,
    listSourceFilesEndpoint, sourceFileEndpoint, searchArtifactsEndpoint, symbolToArtifactEndpoint,
  )

  private val docRoutes: Routes[Any, Response] =
    Routes(
      Method.GET / "openapi.json" -> Handler.fromResponse(Response.json(openApi.toJsonPretty)),
    ) ++ SwaggerUI.routes("api" / "doc", openApi)

  val routes =
    Routes(
      latestRoute, indexRoute, listJavadocSymbolsRoute, javadocSymbolRoute,
      listSourceFilesRoute, sourceFileRoute, searchArtifactsRoute, symbolToArtifactRoute,
    ) ++ docRoutes
