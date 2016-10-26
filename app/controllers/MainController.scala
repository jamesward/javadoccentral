package controllers

import java.io.File
import java.net.URL
import java.nio.file.Files
import javax.inject.Inject
import javax.inject.Singleton

import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import play.api.libs.ws.WSClient
import play.api.mvc.{Action, Controller}

import scala.concurrent.ExecutionContext

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

  def file(groupId: String, artifactId: String, version: String, file: String) = Action {
    val path = artifactPath(groupId, artifactId, version)
    val javadocUrl = s"https://repo1.maven.org/maven2/$path/$artifactId-$version-javadoc.jar"
    val javadocDir = new File(tmpDir, path)
    val javadocFile = new File(javadocDir, file)

    if (!javadocDir.exists()) {
      downloadAndExtractZip(javadocUrl, javadocDir)
    }

    if (javadocFile.exists()) {
      Ok.sendFile(javadocFile, true)
    }
    else {
      NotFound("The specified file does not exist.")
    }
  }


}
