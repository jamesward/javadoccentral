import cats.data.NonEmptyList

import java.io.File
import java.nio.file.Files
import cats.effect.{ExitCode, IO, IOApp}
import org.http4s.CacheDirective.{`max-age`, public}
import org.http4s.client.Client
import org.http4s.client.middleware.Logger
import org.http4s.headers.{Location, `Cache-Control`}
import org.http4s.server.middleware.GZip
import org.http4s.twirl._
import org.http4s.{HttpRoutes, Request, Response, StaticFile, Uri}
import org.http4s.blaze.client._
import org.http4s.client._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.http4s.blaze.server._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object App extends IOApp {

  def needGroupId(maybeGroupId: Option[String]): IO[Response[IO]] = {
    maybeGroupId.fold(Ok(html.needGroupId())) { groupId =>
      PermanentRedirect(Location(Uri.unsafeFromString(s"/$groupId")))
    }
  }

  def needArtifactId(groupId: String, maybeArtifactId: Option[String])(implicit client: Client[IO]): IO[Response[IO]] = {
    maybeArtifactId.filter(_.nonEmpty).fold {
      // todo: not found & bad request for other errors
      MavenCentral.searchArtifacts(groupId).flatMap { artifactIds =>
        if (artifactIds.isEmpty) {
          NotFound(html.needGroupId(Some(groupId)))
        }
        else {
          Ok(html.needArtifactId(groupId, artifactIds))
        }
      }
    } { artifactId =>
      PermanentRedirect(Location(Uri.unsafeFromString(s"/$groupId/$artifactId")))
    }
  }

  def needVersion(groupId: String, artifactId: String, maybeVersion: Option[String])(implicit client: Client[IO]) = {
    maybeVersion.filter(_.nonEmpty).fold {
      // todo: not found & bad request for other errors
      MavenCentral.searchVersions(groupId, artifactId)(client).flatMap { versions =>
        // todo: version sorting
        Ok(html.needVersion(groupId, artifactId, versions))
      }
    } { version =>
      PermanentRedirect(Location(Uri.unsafeFromString(s"/$groupId/$artifactId/$version")))
    }
  }

  def index(groupId: String, artifactId: String, version: String)(implicit client: Client[IO]) = {
    if (version == "latest") {
      MavenCentral.latest(groupId, artifactId).flatMap { maybeLatestVersion =>
        val uri = maybeLatestVersion.fold(Uri.unsafeFromString(s"/$groupId/$artifactId")) { latestVersion =>
          Uri.unsafeFromString(s"/$groupId/$artifactId/$latestVersion")
        }
        TemporaryRedirect(Location(uri))
      } handleErrorWith { t =>
        println(t)
        // todo: endless redirects are bad
        TemporaryRedirect(Location(Uri.unsafeFromString(s"/$groupId/$artifactId/latest")))
      }
    }
    else {
      MavenCentral.artifactExists(groupId, artifactId, version).flatMap { exists =>
        if (exists) {
          PermanentRedirect(Location(Uri.unsafeFromString(s"/$groupId/$artifactId/$version/index.html")))
        }
        else {
          NotFound("The specified Maven Central module does not exist.")
        }
      }
    }
  }

  def file(groupId: String, artifactId: String, version: String, filepath: Path, request: Request[IO])(implicit client: Client[IO], tmpDir: File) = {
    if (version == "latest") {
      MavenCentral.latest(groupId, artifactId).flatMap { maybeLatestVersion =>
        val uri = maybeLatestVersion.fold(Uri.unsafeFromString(s"/$groupId/$artifactId")) { latestVersion =>
          Uri.unsafeFromString(s"/$groupId/$artifactId/$latestVersion$filepath")
        }
        TemporaryRedirect(Location(uri))
      }
    }
    else {
      val javadocUri = MavenCentral.javadocUri(groupId, artifactId, version)
      val javadocDir = new File(tmpDir, s"$groupId/$artifactId/$version")
      val javadocFile = new File(javadocDir, filepath.toString)

      // todo: fix race condition
      val extracted = if (!javadocDir.exists()) {
        MavenCentral.downloadAndExtractZip(javadocUri, javadocDir)
      }
      else {
        IO.unit
      }

      extracted.flatMap { _ =>
        if (javadocFile.exists()) {
          StaticFile.fromFile(javadocFile, Some(request))
            .map(_.putHeaders(`Cache-Control`(NonEmptyList.of(public, `max-age`(365.days)))))
            .getOrElseF(NotFound())
        }
        else {
          NotFound("The specified file does not exist / the JavaDoc has not been published for that artifact.")
        }
      }
    }
  }

  object OptionalGroupIdQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("groupId")
  object OptionalArtifactIdQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("artifactId")
  object OptionalVersionQueryParamMatcher extends OptionalQueryParamDecoderMatcher[String]("version")

  def httpApp(implicit client: Client[IO], tmpDir: File) = HttpRoutes.of[IO] {
    case GET -> Root / "favicon.ico" => NotFound()
    case GET -> Root :? OptionalGroupIdQueryParamMatcher(maybeGroupId) => needGroupId(maybeGroupId)
    case GET -> Root / groupId :? OptionalArtifactIdQueryParamMatcher(maybeArtifactId) => needArtifactId(groupId, maybeArtifactId)
    case GET -> Root / groupId / "" :? OptionalArtifactIdQueryParamMatcher(maybeArtifactId) => needArtifactId(groupId, maybeArtifactId)
    case GET -> Root / groupId / artifactId :? OptionalVersionQueryParamMatcher(maybeVersion) => needVersion(groupId, artifactId, maybeVersion)
    case GET -> Root / groupId / artifactId / "" :? OptionalVersionQueryParamMatcher(maybeVersion) => needVersion(groupId, artifactId, maybeVersion)
    case GET -> Root / groupId / artifactId / version => index(groupId, artifactId, version)
    case GET -> Root / groupId / artifactId / version / "" => index(groupId, artifactId, version)
    case req @ GET -> groupId /: artifactId /: version /: filepath => file(groupId, artifactId, version, filepath, req)
  }.orNotFound

  def run(args: List[String]) = {
    val port = sys.env.getOrElse("PORT", "8080").toInt

    val tmpDir = Files.createTempDirectory("jars").toFile // todo: to resource

    {
      for {
        client <- BlazeClientBuilder[IO](ExecutionContext.global).resource
        loggerClient = Logger(logHeaders = true, logBody = false)(client)
        httpAppWithClient = GZip(httpApp(loggerClient, tmpDir))
        //loggerHttpApp = org.http4s.server.middleware.Logger.httpApp(true, true)(httpAppWithClient)
        server <- BlazeServerBuilder[IO](ExecutionContext.global).bindHttp(port, "0.0.0.0").withHttpApp(httpAppWithClient).resource
      } yield server
    }.use(_ => IO.never).as(ExitCode.Success)
  }

}
