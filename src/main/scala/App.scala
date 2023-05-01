import zio.*
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.http.*
import zio.http.html.*
import zio.http.Path.Segment
import zio.http.Status.TemporaryRedirect
import zio.http.netty.NettyConfig
import zio.http.netty.client.NettyClientDriver
import zio.stream.ZStream

import java.io.File
import java.nio.file.Files

object App extends ZIOAppDefault:
  // todo: maybe via env?
  val tmpDir = Files.createTempDirectory("jars").nn.toFile

  given CanEqual[Path, Path] = CanEqual.derived
  given CanEqual[Method, Method] = CanEqual.derived

  def withGroupId(groupId: MavenCentral.GroupId): Handler[Client, Nothing, Request, Response] = {
    Handler.fromZIO {
      MavenCentral.searchArtifacts(groupId)
    }.flatMap { artifacts =>
      Handler.template("javadocs.dev")(UI.needArtifactId(groupId, artifacts))
    }.catchAllCause {
      case Cause.Fail(_: MavenCentral.GroupIdNotFoundError, _) =>
        Handler.notFound
      case cause =>
        Handler.fromZIO {
          ZIO.logErrorCause(cause).as(Response.status(Status.InternalServerError))
        }
    }
  }

  def withArtifactId(groupId: MavenCentral.GroupId, artifactId: MavenCentral.ArtifactId): Handler[Client, Nothing, Request, Response] = {
    Handler.fromZIO {
      defer {
        val isArtifact = MavenCentral.isArtifact(groupId, artifactId).run
        Option.when(isArtifact) {
          MavenCentral.searchVersions(groupId, artifactId).run
        }
      }
    }.flatMap { maybeVersions =>
      maybeVersions.fold {
        // todo: better api?
        Handler.response(Response.redirect(URL(Path.root / (groupId.toString + "." + artifactId.toString))))
      } { versions =>
        Handler.template("javadocs.dev")(UI.needVersion(groupId, artifactId, versions))
      }
    }.catchAllCause {
      case Cause.Fail(_: MavenCentral.GroupIdOrArtifactIdNotFoundError, _) =>
        Handler.notFound
      case cause =>
        Handler.fromZIO {
          ZIO.logErrorCause(cause).as(Response.status(Status.InternalServerError))
        }
    }
  }

  def withVersion(groupId: MavenCentral.GroupId, artifactId: MavenCentral.ArtifactId, version: MavenCentral.Version): Handler[Client, Nothing, Request, Response] = {
    val javadocPathZIO = defer {
      if version == MavenCentral.Version.latest then
        val maybeLatest = MavenCentral.latest(groupId, artifactId).run
        maybeLatest.fold {
          groupId / artifactId
        } { latestVersion =>
          groupId / artifactId / latestVersion
        }
      else
        MavenCentral.javadocUri(groupId, artifactId, version).run
        groupId / artifactId / version / "index.html"
    }

    Handler.fromZIO {
      javadocPathZIO.map { path =>
        Response.redirect(URL(path)) // todo: perm when not latest
      }
    }.catchAllCause {
      case Cause.Fail(MavenCentral.JavadocNotFoundError(groupId, artifactId, version), _) =>
        Handler.fromZIO(MavenCentral.searchVersions(groupId, artifactId)).flatMap { versions =>
          Handler.template("javadocs.dev")(UI.noJavadoc(groupId, artifactId, versions, version))
        }.catchAllCause {
          case Cause.Fail(MavenCentral.GroupIdOrArtifactIdNotFoundError(_, _), _) =>
            Handler.notFound // todo: better handling
          case cause =>
            Handler.fromZIO {
              ZIO.logErrorCause(cause).as(Response.status(Status.InternalServerError))
            }
        }
      case cause =>
        Handler.fromZIO {
          ZIO.logErrorCause(cause).as(Response.status(Status.InternalServerError))
        }
    }
  }

  type Blocker = ConcurrentMap[(MavenCentral.GroupId, MavenCentral.ArtifactId, MavenCentral.Version), Promise[Nothing, Unit]]

  def withFile(groupId: MavenCentral.GroupId, artifactId: MavenCentral.ArtifactId, version: MavenCentral.Version, file: Path, blocker: Blocker): Http[Client & Scope, Nothing, Request, Response] = {
    val javadocFileZIO = defer {
      val javadocDir = File(tmpDir, s"$groupId/$artifactId/$version")
      val javadocFile = File(javadocDir, file.toString)

      // could be less racey
      if !javadocDir.exists() then
        val maybeBlock = blocker.get((groupId, artifactId, version)).run
        // note: fold doesn't work with defer here
        maybeBlock match
          case Some(promise) =>
            promise.await.run
          case _ =>
            val promise = Promise.make[Nothing, Unit].run
            blocker.put((groupId, artifactId, version), promise).run
            val javadocUri = MavenCentral.javadocUri(groupId, artifactId, version).run
            MavenCentral.downloadAndExtractZip(javadocUri, javadocDir).run
            promise.succeed(()).run

      javadocFile
    }

    Http.fromHttpZIO { _ =>
      javadocFileZIO.map { javadocFile =>
        Http.fromFile(javadocFile).catchAllCauseZIO { cause =>
          ZIO.logErrorCause(cause).as(Response.status(Status.InternalServerError))
        }
      }.catchAllCause {
        case Cause.Fail(MavenCentral.JavadocNotFoundError(groupId, artifactId, version), _) =>
          ZIO.succeed(
            Http.fromHandler(
              Response.redirect(URL(groupId / artifactId / version)).toHandler
            )
          )
        case cause =>
          ZIO.logErrorCause(cause).as(
            Http.fromHandler(
              Response.status(Status.InternalServerError).toHandler
            )
          )
      }
    }
  }

  val app = Http.collectHandler[Request] {
    case Method.GET -> Path.empty => Handler.template("javadocs.dev")(UI.index)
    case Method.GET -> Path.root => Handler.template("javadocs.dev")(UI.index)
    case Method.GET -> Path.root / "favicon.ico" => Handler.notFound
    case Method.GET -> Path.root / MavenCentral.GroupId(groupId) => withGroupId(groupId)
    case Method.GET -> Path.root / MavenCentral.GroupId(groupId) / MavenCentral.ArtifactId(artifactId) => withArtifactId(groupId, artifactId)
    case Method.GET -> Path.root / MavenCentral.GroupId(groupId) / MavenCentral.ArtifactId(artifactId) / MavenCentral.Version(version) => withVersion(groupId, artifactId, version)
  }

  val redirectQueryParams = HttpAppMiddleware.ifRequestThenElseFunction(_.url.queryParams.nonEmpty)(
    // remove trailing slash on groupId & artifactId
    ifFalse = request =>
      if request.path != Path.empty && request.path != Path.root && request.path.trailingSlash then
        HttpAppMiddleware.redirect(URL(request.path.dropTrailingSlash), true)
      else
        RequestHandlerMiddleware.identity,

    // redirected query strings form submissions to path style
    ifTrue = request =>
      val url = request.url.queryParams.get("groupId").map { chunks =>
        request.url.withPath(Path.root / chunks.head).withQueryParams()
      }.orElse {
        request.url.queryParams.get("artifactId").map { chunks =>
          request.url.withPath(request.path / chunks.head).withQueryParams()
        }
      }.orElse {
        request.url.queryParams.get("version").map { chunks =>
          request.url.withPath(request.path / chunks.head).withQueryParams()
        }
      }.getOrElse(request.url)

      HttpAppMiddleware.redirect(url, true)
  )

  def serveFile(blocker: ConcurrentMap[(MavenCentral.GroupId, MavenCentral.ArtifactId, MavenCentral.Version), Promise[Nothing, Unit]]) = Http.collectHttp[Request] {
    case Method.GET -> "" /: MavenCentral.GroupId(groupId) /: MavenCentral.ArtifactId(artifactId) /: MavenCentral.Version(version) /: file =>
      withFile(groupId, artifactId, version, file, blocker)
  }

  def appWithMiddleware(blocker: ConcurrentMap[(MavenCentral.GroupId, MavenCentral.ArtifactId, MavenCentral.Version), Promise[Nothing, Unit]]) =
    ((app @@ redirectQueryParams) ++ serveFile(blocker)) @@ HttpAppMiddleware.requestLogging()

  def run =
//    val clientLayer = (
//      DnsResolver.default ++
//        (ZLayer.succeed(NettyConfig.default) >>> NettyClientDriver.live) ++
//        ZLayer.succeed(Client.Config.default.withFixedConnectionPool(10))
//      ) >>> Client.customized

    val clientLayer = Client.default

    for
      blocker <- ConcurrentMap.empty[(MavenCentral.GroupId, MavenCentral.ArtifactId, MavenCentral.Version), Promise[Nothing, Unit]]
      _ <- Server.serve(appWithMiddleware(blocker)).provide(Server.default, clientLayer, Scope.default)
    yield
      ()
