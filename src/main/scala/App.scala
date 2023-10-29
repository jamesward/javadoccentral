import com.jamesward.zio_mavencentral.MavenCentral
import zio.*
import zio.cache.{Cache, Lookup}
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.http.*
import zio.http.codec.PathCodec

import java.io.{File, FileNotFoundException}
import java.nio.file.Files
import scala.annotation.unused


object App extends ZIOAppDefault:
  // todo: maybe via env?
  private val tmpDir = Files.createTempDirectory("jars").nn.toFile

  given CanEqual[Path, Path] = CanEqual.derived
  given CanEqual[Method, Method] = CanEqual.derived

  def withGroupId(groupId: MavenCentral.GroupId, @unused request: Request): Handler[Client & Scope, Nothing, (MavenCentral.GroupId, Request), Response] =
    Handler.fromZIO:
      MavenCentral.searchArtifacts(groupId)
    .flatMap: artifacts =>
      Handler.template("javadocs.dev")(UI.needArtifactId(groupId, artifacts))
    .catchAllCause:
      case Cause.Fail(_: MavenCentral.GroupIdNotFoundError, _) =>
        Handler.notFound(groupId.toString)
      case cause =>
        Handler.fromZIO:
          ZIO.logErrorCause(cause).as(Response.status(Status.InternalServerError))

  def withArtifactId(groupId: MavenCentral.GroupId, artifactId: MavenCentral.ArtifactId, @unused request: Request): Handler[Client & Scope, Nothing, (MavenCentral.GroupId, MavenCentral.ArtifactId, Request), Response] =
    Handler.fromZIO:
      defer:
        val isArtifact = MavenCentral.isArtifact(groupId, artifactId).run
        Option.when(isArtifact):
          MavenCentral.searchVersions(groupId, artifactId).run
    .flatMap: maybeVersions =>
      maybeVersions.fold:
        // todo: better api?
        Handler.response(Response.redirect(URL(Path.root / (groupId.toString + "." + artifactId.toString))))
      .apply: versions =>
        Handler.template("javadocs.dev")(UI.needVersion(groupId, artifactId, versions))
    .catchAllCause:
      case Cause.Fail(_: MavenCentral.GroupIdOrArtifactIdNotFoundError, _) =>
        Handler.notFound(MavenCentral.GroupArtifact(groupId, artifactId).toString)
      case cause =>
        Handler.fromZIO:
          ZIO.logErrorCause(cause).as(Response.status(Status.InternalServerError))

  type LatestCache = Cache[MavenCentral.GroupArtifact, MavenCentral.GroupIdOrArtifactIdNotFoundError | Throwable, Path]
  type JavadocExistsCache = Cache[MavenCentral.GroupArtifactVersion, MavenCentral.JavadocNotFoundError | Throwable, Path]

  // todo: is the option handling here correct?
  def latest(groupArtifact: MavenCentral.GroupArtifact): ZIO[Client & Scope, MavenCentral.GroupIdOrArtifactIdNotFoundError | Throwable, Path] =
    defer:
      val maybeLatest = MavenCentral.latest(groupArtifact.groupId, groupArtifact.artifactId).run
      maybeLatest.fold:
        groupArtifact.toPath
      .apply: latestVersion =>
        groupArtifact / latestVersion

  def javadocExists(groupArtifactVersion: MavenCentral.GroupArtifactVersion): ZIO[Client & Scope, MavenCentral.JavadocNotFoundError | Throwable, Path] =
    defer:
      MavenCentral.javadocUri(groupArtifactVersion.groupId, groupArtifactVersion.artifactId, groupArtifactVersion.version).run // javadoc exists
      groupArtifactVersion.toPath / "index.html"

  def withVersion(groupId: MavenCentral.GroupId, artifactId: MavenCentral.ArtifactId, version: MavenCentral.Version, @unused request: Request)(latestCache: LatestCache, javadocExistsCache: JavadocExistsCache): Handler[Client & Scope, Nothing, (MavenCentral.GroupId, MavenCentral.ArtifactId, MavenCentral.Version, Request), Response] =
    val groupArtifactVersion = MavenCentral.GroupArtifactVersion(groupId, artifactId, version)
    val javadocPathZIO =
      if groupArtifactVersion.version == MavenCentral.Version.latest then
        latestCache.get(groupArtifactVersion.noVersion)
      else
        javadocExistsCache.get(groupArtifactVersion)

    Handler.fromZIO:
      javadocPathZIO.map: path =>
        Response.redirect(URL(path)) // todo: perm when not latest
    .catchAllCause:
      case Cause.Fail(MavenCentral.JavadocNotFoundError(groupId, artifactId, version), _) =>
        Handler.fromZIO:
          MavenCentral.searchVersions(groupId, artifactId)
        .flatMap: versions =>
          Handler.template("javadocs.dev")(UI.noJavadoc(groupId, artifactId, versions, version))
        .catchAllCause:
          case Cause.Fail(MavenCentral.GroupIdOrArtifactIdNotFoundError(_, _), _) =>
            Handler.notFound(groupArtifactVersion.toString) // todo: better handling
          case cause =>
            Handler.fromZIO:
              ZIO.logErrorCause(cause).as(Response.status(Status.InternalServerError))
      case cause =>
        Handler.fromZIO:
          ZIO.logErrorCause(cause).as(Response.status(Status.InternalServerError))

  type Blocker = ConcurrentMap[MavenCentral.GroupArtifactVersion, Promise[Nothing, Unit]]

  def withFile(groupId: MavenCentral.GroupId, artifactId: MavenCentral.ArtifactId, version: MavenCentral.Version, file: Path, @unused request: Request, blocker: Blocker): Handler[Client & Scope, Nothing, (MavenCentral.GroupId, MavenCentral.ArtifactId, MavenCentral.Version, Path, Request), Response] =
    val groupArtifactVersion = MavenCentral.GroupArtifactVersion(groupId, artifactId, version)
    val javadocFileZIO = defer:
      val javadocDir = File(tmpDir, groupArtifactVersion.toString)
      val javadocFile = File(javadocDir, file.toString)

      // could be less racey
      if !javadocDir.exists() then
        val maybeBlock = blocker.get(groupArtifactVersion).run
        // note: fold doesn't work with defer here
        maybeBlock match
          case Some(promise) =>
            promise.await.run
          case _ =>
            val promise = Promise.make[Nothing, Unit].run
            blocker.put(groupArtifactVersion, promise).run
            val javadocUri = MavenCentral.javadocUri(groupArtifactVersion.groupId, groupArtifactVersion.artifactId, groupArtifactVersion.version).run
            MavenCentral.downloadAndExtractZip(javadocUri, javadocDir).run
            promise.succeed(()).run

      javadocFile

    // todo: could be better
    Handler.fromZIO(javadocFileZIO).flatMap(Handler.fromFile).catchAllCause:
      case Cause.Fail(error, _) =>
        if error.isInstanceOf[MavenCentral.JavadocNotFoundError] then
          Response.redirect(URL(groupArtifactVersion.toPath)).toHandler
        if error.isInstanceOf[FileNotFoundException] then
          Response.notFound((groupArtifactVersion.toPath ++ file).toString).toHandler
        else
          Handler.internalServerError
      case cause =>
        Handler.fromZIO:
          ZIO.logErrorCause(cause).as(Response.status(Status.InternalServerError))


  private def groupIdExtractor(groupId: String) = MavenCentral.GroupId.unapply(groupId).toRight(groupId)
  private val groupId: PathCodec[MavenCentral.GroupId] =
    string("groupId").transformOrFailLeft(groupIdExtractor)(_.toString)

  private def artifactIdExtractor(artifactId: String) = MavenCentral.ArtifactId.unapply(artifactId).toRight(artifactId)
  private val artifactId: PathCodec[MavenCentral.ArtifactId] =
    string("artifactId").transformOrFailLeft(artifactIdExtractor)(_.toString)

  private def versionExtractor(version: String) = MavenCentral.Version.unapply(version).toRight(version)
  private val version: PathCodec[MavenCentral.Version] =
    string("version").transformOrFailLeft(versionExtractor)(_.toString)

  def app(latestCache: LatestCache, javadocExistsCache: JavadocExistsCache, blocker: Blocker): Routes[Client & Scope, Nothing] =
    Routes[Client & Scope, Nothing](
      Method.GET / "" -> Handler.template("javadocs.dev")(UI.index),
      Method.GET / "favicon.io" -> Handler.notFound,
      Method.GET / groupId -> Handler.fromFunctionHandler[(MavenCentral.GroupId, Request)](withGroupId),
      Method.GET / groupId / artifactId -> Handler.fromFunctionHandler[(MavenCentral.GroupId, MavenCentral.ArtifactId, Request)](withArtifactId),
      Method.GET / groupId / artifactId / version -> Handler.fromFunctionHandler[(MavenCentral.GroupId, MavenCentral.ArtifactId, MavenCentral.Version, Request)] {
        (groupId, artifactId, version, request) =>
          withVersion(groupId, artifactId, version, request)(latestCache, javadocExistsCache)
      },
      Method.GET / groupId / artifactId / version / trailing -> Handler.fromFunctionHandler[(MavenCentral.GroupId, MavenCentral.ArtifactId, MavenCentral.Version, Path, Request)] {
        (groupId, artifactId, version, file, request) =>
          withFile(groupId, artifactId, version, file, request, blocker)
      },
    )

  private val redirectQueryParams = HandlerAspect.intercept: (request, response) =>
    request.url.queryParams.get("groupId").map: groupId =>
      request.url.path(Path.root / groupId).queryParams()
    .orElse:
      request.url.queryParams.get("artifactId").map: artifactId =>
        request.url.path(request.path / artifactId).queryParams()
    .orElse:
      request.url.queryParams.get("version").map: version =>
        request.url.path(request.path / version).queryParams()
    .fold(
      if request.url.path.hasTrailingSlash then
        Response.redirect(request.url.dropTrailingSlash)
      else
        response
    )(Response.redirect(_, true))

  def appWithMiddleware(blocker: Blocker, latestCache: LatestCache, javadocExistsCache: JavadocExistsCache): HttpApp[Client & Scope] =
    app(latestCache, javadocExistsCache, blocker).toHttpApp @@ redirectQueryParams @@ Middleware.requestLogging()

  def run =
    // todo: log filtering so they don't show up in tests / runtime config
    defer:
      val blocker = ConcurrentMap.empty[MavenCentral.GroupArtifactVersion, Promise[Nothing, Unit]].run
      val latestCache = Cache.makeWith(1_000, Lookup(latest)) {
        case Exit.Success(_) => 1.hour
        case Exit.Failure(_) => Duration.Zero
      }.run
      val javadocExistsCache = Cache.makeWith(1_000, Lookup(javadocExists)) {
        case Exit.Success(_) => Duration.Infinity
        case Exit.Failure(_) => Duration.Zero
      }.run
      val app = appWithMiddleware(blocker, latestCache, javadocExistsCache)
      Server.serve(app).run
      ()
    .provide(Server.default, Client.default, Scope.default)
