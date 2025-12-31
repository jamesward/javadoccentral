import com.jamesward.zio_mavencentral.MavenCentral
import sttp.tapir.server.ziohttp.ZioHttpInterpreter
import zio.*
import zio.cache.{Cache, Lookup}
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.http.*
import zio.http.codec.PathCodec
import zio.http.template.Template
import zio.redis.{CodecSupplier, Redis, RedisConfig}

import java.net.URI
import java.nio.file.Files
import scala.annotation.unused


object App extends ZIOAppDefault:
  given CanEqual[Path, Path] = CanEqual.derived
  given CanEqual[Method, Method] = CanEqual.derived

  def withGroupId(groupId: MavenCentral.GroupId, @unused request: Request): Handler[Client, Nothing, (MavenCentral.GroupId, Request), Response] =
    Handler.fromZIO:
      ZIO.scoped:
        MavenCentral.searchArtifacts(groupId)
    .flatMap: artifacts =>
      Handler.template("javadocs.dev")(UI.needArtifactId(groupId, artifacts))
    .catchAll: _ =>
      Handler.template("javadocs.dev")(UI.invalidGroupId(groupId))

  def withArtifactId(groupId: MavenCentral.GroupId, artifactId: MavenCentral.ArtifactId, @unused request: Request): Handler[Client, Nothing, (MavenCentral.GroupId, MavenCentral.ArtifactId, Request), Response] =
    Handler.fromZIO:
      ZIO.scoped:
        defer:
          val isArtifact = MavenCentral.isArtifact(groupId, artifactId).run
          Option.when(isArtifact):
            MavenCentral.searchVersions(groupId, artifactId).run
    .flatMap: maybeVersions =>
      maybeVersions.fold:
        // if not an artifact, append to the groupId
        Response.redirect(URL(Path.root / (groupId.toString + "." + artifactId.toString))).toHandler
      .apply: versions =>
        Handler.template("javadocs.dev")(UI.needVersion(groupId, artifactId, versions))
    .catchAll: e =>
      // invalid groupId or artifactId
      Handler.fromZIO:
        ZIO.scoped:
          MavenCentral.searchArtifacts(groupId)
      .flatMap: artifactIds =>
        Response.html(Template.container("javadocs.dev")(UI.needArtifactId(groupId, artifactIds)), Status.NotFound).toHandler // todo: message
      .orElse:
        Response.redirect(URL(Path.root / groupId.toString)).toHandler  // todo: message

  def indexJavadocContents(groupArtifactVersion: MavenCentral.GroupArtifactVersion):
    ZIO[Client & Extractor.FetchBlocker & Extractor.JavadocCache & Redis & Scope, Nothing, Unit] =

    val getContentsAndUpdateIndex = for
      contents <- Extractor.javadocContents(groupArtifactVersion).debug
      _ <- SymbolSearch.update(groupArtifactVersion.noVersion, contents).debug
    yield ()

    getContentsAndUpdateIndex.forkDaemon.unit

  def withVersion(groupId: MavenCentral.GroupId, artifactId: MavenCentral.ArtifactId, version: MavenCentral.Version, @unused request: Request):
      Handler[Extractor.LatestCache & Client & Extractor.JavadocCache & Redis & Extractor.FetchBlocker & Extractor.TmpDir, Nothing, (MavenCentral.GroupId, MavenCentral.ArtifactId, MavenCentral.Version, Path, Request), Response] =
    val groupArtifactVersion = MavenCentral.GroupArtifactVersion(groupId, artifactId, version)

    Handler.fromZIO:
      ZIO.scoped:
        if groupArtifactVersion.version == MavenCentral.Version.latest then
          ZIO.serviceWithZIO[Extractor.LatestCache](_.get(groupArtifactVersion.noVersion)).map: latest =>
            groupArtifactVersion.noVersion / latest
          .orElseSucceed(groupArtifactVersion.noVersion.toPath).map: path =>
            Response.redirect(URL(path))
        else
          defer:
            val javadocDir = ZIO.serviceWithZIO[Extractor.JavadocCache](_.get(groupArtifactVersion)).run
            Extractor.javadocFile(groupArtifactVersion, javadocDir, "index.html").run
            Response.redirect(URL(groupArtifactVersion.toPath / "index.html"))
    .catchAll:
      case _: MavenCentral.JavadocNotFoundError | _: Extractor.JavadocFileNotFound =>
        Handler.fromZIO:
          ZIO.scoped:
            MavenCentral.searchVersions(groupId, artifactId)
        .flatMap: versions =>
          Response.html(Template.container("javadocs.dev")(UI.noJavadoc(groupId, artifactId, versions, version)), Status.NotFound).toHandler // todo: message
        .orElse:
          Response.redirect(URL(groupId / artifactId)).toHandler // todo: message

  // have to convert the failures to an exception for fromFileZIO
  case class JavadocException(error: MavenCentral.JavadocNotFoundError | Extractor.JavadocFileNotFound) extends Exception

  def withFile(groupId: MavenCentral.GroupId, artifactId: MavenCentral.ArtifactId, version: MavenCentral.Version, file: Path, @unused request: Request):
      Handler[Extractor.FetchBlocker & Client & Extractor.TmpDir & Redis & Extractor.JavadocCache, Nothing, (MavenCentral.GroupId, MavenCentral.ArtifactId, MavenCentral.Version, Path, Request), Response] =
    val groupArtifactVersion = MavenCentral.GroupArtifactVersion(groupId, artifactId, version)

    Handler.fromFileZIO:
      ZIO.scoped:
        defer:
          val javadocDir = Extractor.javadoc(groupArtifactVersion).run
          ZIO.when(file.toString == "index.html")(indexJavadocContents(groupArtifactVersion)).run
          Extractor.javadocFile(groupArtifactVersion, javadocDir, file.toString).run
        .catchAll(e => ZIO.fail(JavadocException(e)))
    .catchAll:
      case JavadocException(e: MavenCentral.JavadocNotFoundError) =>
        Response.redirect(URL(groupArtifactVersion.toPath)).toHandler
      case JavadocException(e: Extractor.JavadocFileNotFound) =>
        Response.notFound((groupArtifactVersion.toPath ++ file).toString).toHandler

  private def groupIdExtractor(groupId: String): Either[String, MavenCentral.GroupId] =
    if groupId == "mcp" then
      Left("mcp")
    else
      MavenCentral.GroupId.unapply(groupId).toRight(groupId)

  private val groupId: PathCodec[MavenCentral.GroupId] =
    string("groupId").transformOrFailLeft(groupIdExtractor)(_.toString)

  private def artifactIdExtractor(artifactId: String) = MavenCentral.ArtifactId.unapply(artifactId).toRight(artifactId)
  private val artifactId: PathCodec[MavenCentral.ArtifactId] =
    string("artifactId").transformOrFailLeft(artifactIdExtractor)(_.toString)

  private def versionExtractor(version: String) = MavenCentral.Version.unapply(version).toRight(version)
  private val version: PathCodec[MavenCentral.Version] =
    string("version").transformOrFailLeft(versionExtractor)(_.toString)

  def withVersionAndFile(groupId: MavenCentral.GroupId, artifactId: MavenCentral.ArtifactId, version: MavenCentral.Version, path: Path, request: Request):
      Handler[Extractor.FetchBlocker & Client & Extractor.LatestCache & Extractor.JavadocCache & Extractor.TmpDir & Redis, Nothing, (MavenCentral.GroupId, MavenCentral.ArtifactId, MavenCentral.Version, Path, Request), Response] = {
    if (path.isEmpty)
      withVersion(groupId, artifactId, version, request)
    else
      withFile(groupId, artifactId, version, path, request)
  }

  def index(request: Request): Handler[Redis, Nothing, Request, Response] =
    request.queryParameters.map.keys.headOption.fold(Handler.template("javadocs.dev")(UI.index)):
      query =>
        Handler.fromZIO:
          SymbolSearch.search(query).tapError:
            e =>
              ZIO.logError(e.getMessage)
        .flatMap:
          results =>
            Handler.template("javadocs.dev")(UI.symbolSearchResults(query, results))
        .catchAll:
          _ =>
            // todo: convey error
            Handler.template("javadocs.dev")(UI.symbolSearchResults(query, Set.empty))

  val app: Routes[Extractor.LatestCache & Extractor.JavadocCache & Extractor.FetchBlocker & Extractor.TmpDir & Client & Redis, Response] =
    val mcpRoutes = ZioHttpInterpreter().toHttp(MCP.mcpServerEndpoint)

    val appRoutes = Routes[Extractor.LatestCache & Extractor.JavadocCache & Extractor.FetchBlocker & Extractor.TmpDir & Client & Redis, Nothing](
      Method.GET / "" -> Handler.fromFunctionHandler[Request](index),
      Method.GET / "favicon.ico" -> Handler.notFound,
      Method.GET / "robots.txt" -> Handler.notFound,
      Method.GET / ".well-known" / trailing -> Handler.notFound,
      Method.GET / groupId -> Handler.fromFunctionHandler[(MavenCentral.GroupId, Request)](withGroupId),
      Method.GET / groupId / artifactId -> Handler.fromFunctionHandler[(MavenCentral.GroupId, MavenCentral.ArtifactId, Request)](withArtifactId),
      Method.GET / groupId / artifactId / version / trailing -> Handler.fromFunctionHandler[(MavenCentral.GroupId, MavenCentral.ArtifactId, MavenCentral.Version, Path, Request)](withVersionAndFile),
    )

    mcpRoutes ++ appRoutes

  private val redirectQueryParams = HandlerAspect.intercept: (request, response) =>
    request.url.queryParam("groupId").map: groupId =>
      request.url.path(Path.root / groupId).setQueryParams()
    .orElse:
      request.url.queryParam("artifactId").map: artifactId =>
        request.url.path(request.path / artifactId).setQueryParams()
    .orElse:
      request.url.queryParam("version").map: version =>
        request.url.path(request.path / version).setQueryParams()
    .fold(
      if request.url.path.hasTrailingSlash then
        Response.redirect(request.url.dropTrailingSlash)
      else
        response
    )(Response.redirect(_, true))

  val appWithMiddleware: Routes[Extractor.JavadocCache & Extractor.FetchBlocker & Extractor.LatestCache & Extractor.TmpDir & Client & Redis, Response] =
    app @@ redirectQueryParams @@ Middleware.requestLogging()

  // todo: i think there is a better way
  val server =
    ZLayer.fromZIO:
      defer:
        val system = ZIO.system.run
        val maybePort = system.env("PORT").run.flatMap(_.toIntOption)
        maybePort.fold(Server.default)(Server.defaultWithPort)
    .flatten

  val blockerLayer: ZLayer[Any, Nothing, Extractor.FetchBlocker] =
    ZLayer.fromZIO(ConcurrentMap.empty[MavenCentral.GroupArtifactVersion, Promise[Nothing, Unit]])

  val latestCacheLayer: ZLayer[Client, Nothing, Extractor.LatestCache] = ZLayer.fromZIO:
    Cache.makeWith(1_000, Lookup(Extractor.latest)):
      case Exit.Success(_) => 1.hour
      case Exit.Failure(_) => Duration.Zero

  val javadocCacheLayer: ZLayer[Client & Extractor.FetchBlocker & Extractor.TmpDir, Nothing, Extractor.JavadocCache] = ZLayer.fromZIO:
    Cache.makeWith(1_000, Lookup(Extractor.javadoc)):
      case Exit.Success(_) => Duration.Infinity
      case Exit.Failure(_) => Duration.Zero

  val tmpDirLayer = ZLayer.succeed(Extractor.TmpDir(Files.createTempDirectory("jars").nn.toFile))

  val redisUri: ZIO[Any, Throwable, URI] =
    ZIO.systemWith:
      system =>
        system.env("REDIS_URL")
          .someOrFail(new RuntimeException("REDIS_URL env var not set"))
          .map:
            redisUrl =>
              URI(redisUrl)

  val redisConfigLayer: ZLayer[Any, Throwable, RedisConfig] =
    ZLayer.fromZIO:
      defer:
        val uri = redisUri.run
        RedisConfig(uri.getHost, uri.getPort, ssl = true, verifyCertificate = false)

  // may not work with reconnects
  val redisAuthLayer: ZLayer[CodecSupplier & RedisConfig, Throwable, Redis] =
    Redis.singleNode.flatMap:
      env =>
        ZLayer.fromZIO:
          defer:
            val uri = redisUri.run
            val redis = env.get[Redis]
            val password = uri.getUserInfo.drop(1) // REDIS_URL has an empty username
            redis.auth(password).as(redis).run

  def run =
    // todo: log filtering so they don't show up in tests / runtime config
    Server.serve(appWithMiddleware).provide(
      server,
      Client.default,
      blockerLayer,
      latestCacheLayer,
      javadocCacheLayer,
      tmpDirLayer,
      redisConfigLayer,
      redisAuthLayer,
      ZLayer.succeed[CodecSupplier](SymbolSearch.ProtobufCodecSupplier),
    )
