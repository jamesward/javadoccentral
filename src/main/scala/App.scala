import MavenCentral.{ArtifactId, GroupId, Version}
import zio.*
import zio.direct.*
import zio.http.*
import zio.http.html.*
import zio.http.Path.Segment
import zio.http.Status.TemporaryRedirect
import zio.stream.ZStream

import java.io.File
import java.nio.file.Files

object App extends ZIOAppDefault:
  // todo: maybe via env?
  val tmpDir = Files.createTempDirectory("jars").nn.toFile

  given CanEqual[Path, Path] = CanEqual.derived
  given CanEqual[Method, Method] = CanEqual.derived

  def withGroupId(groupId: GroupId): Handler[Client, Nothing, Request, Response] = {
    Handler.fromZIO {
      MavenCentral.searchArtifacts(groupId)
    }.flatMap { artifacts =>
      Handler.template("javadocs.dev")(UI.needArtifactId(groupId, artifacts))
    }.catchAll { _ =>
      Handler.status(Status.InternalServerError)
    }
  }

  def withArtifactId(groupId: GroupId, artifactId: ArtifactId): Handler[Client, Nothing, Request, Response] = {
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
    }.catchAll { _ =>
      Handler.status(Status.InternalServerError)
    }
  }

  def withVersion(groupId: GroupId, artifactId: ArtifactId, version: Version): Handler[Client, Nothing, Request, Response] = {
    Handler.fromZIO {
      defer {
        if version == Version.latest then
          val maybeLatest = MavenCentral.latest(groupId, artifactId).run
          Some {
            maybeLatest.fold {
              groupId / artifactId
            } { version =>
              groupId / artifactId / version / "index.html"
            }
          }
        else
          val exists = MavenCentral.artifactExists(groupId, artifactId, version).run
          Option.when(exists) {
            groupId / artifactId / version / "index.html"
          }
      }
    }.flatMap { maybePath =>
      maybePath.fold {
        Handler.notFound
      } { path =>
        // todo: nicer api?
        Handler.response(Response.redirect(URL(path))) // todo: perm when not latest
      }
    }.catchAll { _ =>
      Handler.status(Status.InternalServerError)
    }
  }

  def withFile(groupId: GroupId, artifactId: ArtifactId, version: Version, file: Path): Http[Client & Scope, Throwable, Request, Response] = {
    Http.fromFileZIO {
      defer {
        val javadocUri = MavenCentral.javadocUri(groupId, artifactId, version).run
        val javadocDir = File(tmpDir, s"$groupId/$artifactId/$version")
        val javadocFile = File(javadocDir, file.toString)

        // todo: fix race condition
        if !javadocDir.exists() then
          MavenCentral.downloadAndExtractZip(javadocUri, javadocDir).run
        else
          ()

        javadocFile
      }
    }
  }

  val app = Http.collectHandler[Request] {
    case Method.GET -> Path.empty => Handler.template("javadocs.dev")(UI.index)
    case Method.GET -> Path.root => Handler.template("javadocs.dev")(UI.index)
    case Method.GET -> Path.root / "favicon.ico" => Handler.notFound
    case Method.GET -> Path.root / GroupId(groupId) => withGroupId(groupId)
    case Method.GET -> Path.root / GroupId(groupId) / ArtifactId(artifactId) => withArtifactId(groupId, artifactId)
    case Method.GET -> Path.root / GroupId(groupId) / ArtifactId(artifactId) / Version(version) => withVersion(groupId, artifactId, version)
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
          request.url.withPath(request.path / chunks.head / "index.html").withQueryParams()
        }
      }.getOrElse(request.url)

      HttpAppMiddleware.redirect(url, true)
  )

  val serveFile = Http.collectHttp[Request] {
    case Method.GET -> "" /: GroupId(groupId) /: ArtifactId(artifactId) /: Version(version) /: file =>
      withFile(groupId, artifactId, version, file)
  }.withDefaultErrorResponse

  val appWithMiddleware = (app @@ redirectQueryParams) ++ serveFile

  def run =
    Server.serve(appWithMiddleware).provide(Server.default, Client.default, Scope.default).exitCode
