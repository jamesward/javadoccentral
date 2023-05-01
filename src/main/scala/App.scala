import MavenCentral.given
import zio.*
import zio.cache.{Cache, Lookup}
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

  type LatestCache = Cache[MavenCentral.GroupArtifact, MavenCentral.GroupIdOrArtifactIdNotFoundError | Throwable, Path]
  type JavadocExistsCache = Cache[MavenCentral.GroupArtifactVersion, MavenCentral.JavadocNotFoundError | Throwable, Path]

  def latest(groupArtifact: MavenCentral.GroupArtifact): ZIO[Client, MavenCentral.GroupIdOrArtifactIdNotFoundError | Throwable, Path] = defer {
    val maybeLatest = MavenCentral.latest(groupArtifact.groupId, groupArtifact.artifactId).run
    maybeLatest.fold {
      groupArtifact.toPath
    } { latestVersion =>
      groupArtifact / latestVersion
    }
  }

  def javadocExists(groupArtifactVersion: MavenCentral.GroupArtifactVersion): ZIO[Client, MavenCentral.JavadocNotFoundError | Throwable, Path] = defer {
    MavenCentral.javadocUri(groupArtifactVersion.groupId, groupArtifactVersion.artifactId, groupArtifactVersion.version).run // javadoc exists
    groupArtifactVersion.toPath / "index.html"
  }

  def withVersion(groupArtifactVersion: MavenCentral.GroupArtifactVersion)(latestCache: LatestCache, javadocExistsCache: JavadocExistsCache): Handler[Client, Nothing, Request, Response] = {
    val javadocPathZIO = {
      if groupArtifactVersion.version == MavenCentral.Version.latest then
        latestCache.get(groupArtifactVersion.noVersion)
      else
        javadocExistsCache.get(groupArtifactVersion)
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

  type Blocker = ConcurrentMap[MavenCentral.GroupArtifactVersion, Promise[Nothing, Unit]]

  def withFile(groupArtifactVersion: MavenCentral.GroupArtifactVersion, file: Path, blocker: Blocker): Http[Client & Scope, Nothing, Request, Response] = {
    val javadocFileZIO = defer {
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
    }

    Http.fromHttpZIO { _ =>
      javadocFileZIO.map { javadocFile =>
        Http.fromFile(javadocFile).catchAllCauseZIO { cause =>
          ZIO.logErrorCause(cause).as(Response.status(Status.InternalServerError))
        }
      }.catchAllCause {
        case Cause.Fail(MavenCentral.JavadocNotFoundError(groupArtifactVersion.groupId, groupArtifactVersion.artifactId, groupArtifactVersion.version), _) =>
          ZIO.succeed(
            Http.fromHandler(
              Response.redirect(URL(groupArtifactVersion.toPath)).toHandler
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

  def app(latestCache: LatestCache, javadocExistsCache: JavadocExistsCache) = Http.collectHandler[Request] {
    case Method.GET -> Path.empty => Handler.template("javadocs.dev")(UI.index)
    case Method.GET -> Path.root => Handler.template("javadocs.dev")(UI.index)
    case Method.GET -> Path.root / "favicon.ico" => Handler.notFound
    case Method.GET -> Path.root / MavenCentral.GroupId(groupId) => withGroupId(groupId)
    case Method.GET -> Path.root / MavenCentral.GroupId(groupId) / MavenCentral.ArtifactId(artifactId) => withArtifactId(groupId, artifactId)
    case Method.GET -> Path.root / MavenCentral.GroupId(groupId) / MavenCentral.ArtifactId(artifactId) / MavenCentral.Version(version) => withVersion(MavenCentral.GroupArtifactVersion(groupId, artifactId, version))(latestCache, javadocExistsCache)
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

  def serveFile(blocker: Blocker) = Http.collectHttp[Request] {
    case Method.GET -> "" /: MavenCentral.GroupId(groupId) /: MavenCentral.ArtifactId(artifactId) /: MavenCentral.Version(version) /: file =>
      withFile(MavenCentral.GroupArtifactVersion(groupId, artifactId, version), file, blocker)
  }

  def appWithMiddleware(blocker: Blocker, latestCache: LatestCache, javadocExistsCache: JavadocExistsCache) =
    ((app(latestCache, javadocExistsCache) @@ redirectQueryParams) ++ serveFile(blocker)) @@ HttpAppMiddleware.requestLogging()

  def run =
//    val clientLayer = (
//      DnsResolver.default ++
//        (ZLayer.succeed(NettyConfig.default) >>> NettyClientDriver.live) ++
//        ZLayer.succeed(Client.Config.default.withFixedConnectionPool(10))
//      ) >>> Client.customized

    val clientLayer = Client.default

    // todo: log filtering so they don't show up in tests / runtime config
    defer {
      val blocker = ConcurrentMap.empty[MavenCentral.GroupArtifactVersion, Promise[Nothing, Unit]].run
      val latestCache = Cache.make(50, 1.hour, Lookup(latest)).run
      val javadocExistsCache = Cache.make(50, Duration.Infinity, Lookup(javadocExists)).run
      Server.serve(appWithMiddleware(blocker, latestCache, javadocExistsCache)).run
      ()
    }.provide(Server.default, clientLayer, Scope.default)
