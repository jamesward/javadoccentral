package controllers

import java.io.File
import java.net.URL
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton

import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import play.api.libs.json.JsValue
import play.api.libs.ws.WSClient
import play.api.mvc.{Action, Controller}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

@Singleton
class MainController @Inject() (ws: WSClient) (implicit ec: ExecutionContext) extends Controller {

  private lazy val tmpDir: File = Files.createTempDirectory("jars").toFile

  private def artifactPath(groupId: String, artifactId: String, version: String): String = {
    Seq(
      groupId.replaceAllLiterally(".", "/"),
      artifactId,
      version
    ).mkString("/")
  }

  private def downloadAndExtractZip(source: String, destination: File): Unit = {
    val url = new URL(source)
    val inputStream = url.openConnection().getInputStream
    val zipArchiveInputStream = new ZipArchiveInputStream(inputStream)

    Stream
      .continually(zipArchiveInputStream.getNextEntry)
      .takeWhile(_ != null)
      .foreach { ze =>
        val tmpFile = new File(destination, ze.getName)
        if (ze.isDirectory) {
          tmpFile.mkdirs()
        }
        else {
          if (!tmpFile.getParentFile.exists()) {
            tmpFile.getParentFile.mkdirs()
          }
          Files.copy(zipArchiveInputStream, tmpFile.toPath)
        }
      }

    zipArchiveInputStream.close()
    inputStream.close()
  }

  def notFound() = Action(NotFound)

  def needGroupId(maybeGroupId: Option[String]) = Action {
    maybeGroupId.fold(Ok(views.html.needGroupId())) { groupId =>
      Redirect(routes.MainController.needArtifactId(groupId, None))
    }
  }

  def needArtifactId(groupId: String, maybeArtifactId: Option[String]) = Action.async {
    maybeArtifactId.fold {
      ws.url("https://search.maven.org/solrsearch/select").withQueryString(
        "q" -> s"""g:"$groupId"""",
        "rows" -> "5000",
        "wt" -> "json"
      ).get().map { response =>
        response.status match {
          case OK =>
            val docs = (response.json \ "response" \ "docs").as[Seq[JsValue]]
            val artifactIds = docs.map(_.\("a").as[String])
            Ok(views.html.needArtifactId(groupId, artifactIds))
          case NOT_FOUND =>
            NotFound(response.body)
          case _ =>
            BadRequest(response.body)
        }
      }
    } { artifactId =>
      Future.successful(Redirect(routes.MainController.needVersion(groupId, artifactId, None)))
    }
  }

  def needVersion(groupId: String, artifactId: String, maybeVersion: Option[String]) = Action.async {
    maybeVersion.fold {
      ws.url("https://search.maven.org/solrsearch/select").withQueryString(
        "q" -> s"""g:"$groupId" AND a:"$artifactId"""",
        "core" -> "gav",
        "rows" -> "5000",
        "wt" -> "json"
      ).get().map { response =>
        response.status match {
          case OK =>
            val docs = (response.json \ "response" \ "docs").as[Seq[JsValue]]
            val versions = docs.map(_.\("v").as[String])
            Ok(views.html.needVersion(groupId, artifactId, versions))
          case NOT_FOUND =>
            NotFound(response.body)
          case _ =>
            BadRequest(response.body)
        }
      }
    } { version =>
      Future.successful(Redirect(routes.MainController.index(groupId, artifactId, version)))
    }
  }

  def index(groupId: String, artifactId: String, version: String) = Action.async {
    val url = s"https://repo1.maven.org/maven2/${artifactPath(groupId, artifactId, version)}/"
    ws.url(url).get().map { response =>
      response.status match {
        case OK =>
          Redirect(routes.MainController.file(groupId, artifactId, version, "index.html"))
        case _ =>
          NotFound("The specified Maven Central module does not exist.")
      }
    }
  }

  // todo: fix race condition
  def file(groupId: String, artifactId: String, version: String, file: String) = Action.async {
    val path = artifactPath(groupId, artifactId, version)
    val javadocUrl = s"https://repo1.maven.org/maven2/$path/$artifactId-$version-javadoc.jar"
    val javadocDir = new File(tmpDir, path)
    val javadocFile = new File(javadocDir, file)

    val extracted = if (!javadocDir.exists()) {
      Try(downloadAndExtractZip(javadocUrl, javadocDir))
    }
    else {
      Success(Unit)
    }

    Future.fromTry {
      extracted.map { _ =>
        if (javadocFile.exists()) {
          Ok.sendFile(javadocFile, true)
        }
        else {
          NotFound("The specified file does not exist.")
        }
      } recover {
        case e: Exception => InternalServerError("Could not get the JavaDoc at: " + e.getMessage)
      }
    }
  }

}
