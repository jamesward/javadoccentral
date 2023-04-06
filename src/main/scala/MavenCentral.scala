import zio.ZIO
import zio.http.{Client, Path}
import zio.direct.*
import zio.direct.stream.{ defer => deferStream, each }
import zio.stream.ZPipeline

import scala.util.matching.Regex

case class MavenCentralError()

object MavenCentral:

  // todo: regionalize to maven central mirrors for lower latency
  val artifactUri = "https://repo1.maven.org/maven2/"

  opaque type GroupId = String
  object GroupId:
    def apply(s: String): GroupId = s

  opaque type ArtifactId = String
  object ArtifactId:
    def apply(s: String): ArtifactId = s

  opaque type Version = String
  object Version:
    def apply(s: String): Version = s

  case class ArtifactAndVersion(artifactId: ArtifactId, maybeVersion: Option[Version] = None)

  def artifactPath(groupId: GroupId, artifactAndVersion: Option[ArtifactAndVersion] = None): Path = {
    val withGroup = groupId.split('.').foldLeft(Path.empty)(_ / _)
    val withArtifact = artifactAndVersion.fold(withGroup)(withGroup / _.artifactId)
    val withVersion = artifactAndVersion.flatMap(_.maybeVersion).fold(withArtifact)(withArtifact / _)

    withVersion
  }

  private val filenameExtractor: Regex = """.*<a href="([^"]+)".*""".r

  def searchArtifacts(groupId: GroupId): ZIO[Client, Throwable, Set[ArtifactId]] = {
    val url = artifactUri + artifactPath(groupId).addTrailingSlash
    defer {
      val resp = Client.request(url, addZioUserAgentHeader = true).run
      deferStream {
        resp.body.asStream.via(ZPipeline.utf8Decode >>> ZPipeline.splitLines).each
      }.runFold(Set.empty[ArtifactId]) { (lines, line) =>
        line match {
          case filenameExtractor(line) if line.endsWith("/") && !line.startsWith("..") =>
            lines + ArtifactId(line.stripSuffix("/"))
          case _ =>
            lines
        }
      }.run
    }
  }


  def searchVersions(groupId: GroupId, artifactId: String): ZIO[Any, MavenCentralError, Set[Version]] = ???
  def latest(groupId: GroupId, artifactId: ArtifactId): ZIO[Any, MavenCentralError, Option[Version]] = ???




/*
import java.io.File
import java.nio.file.Files
>>>>>>> Stashed changes
import cats.effect._
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.http4s.client.Client
import org.http4s.implicits._
import org.http4s.{Request, Uri}

import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import scala.util.Try
import scala.util.matching.Regex

object MavenCentral {

  object CaseInsensitiveOrdering extends Ordering[String] {
    def compare(a:String, b:String): Int = a.toLowerCase compare b.toLowerCase
  }

  private val filenameExtractor: Regex = """.*<a href="([^"]+)".*""".r

  def searchArtifacts(groupId: String)(implicit client: Client[IO]): IO[Seq[String]] = {
    val uri = artifactUri.withPath(artifactPath(groupId)) / "" // trailing slash to avoid the redirect

    client.expect[String](uri).map { html =>
      html.linesIterator.collect {
        case filenameExtractor(name) if name.endsWith("/") && !name.startsWith("..") => name.stripSuffix("/")
      }.toSeq
    }
  }

  // 2018-04-06 15:59
  private val filenameAndDateExtractor: Regex = """.*<a href="([^"]+)".*</a>\s+(\d+-\d+-\d+\s\d+:\d+)\s+-.*""".r

  def searchVersions(groupId: String, artifactId: String)(implicit client: Client[IO]): IO[Seq[String]] = {
    val uri = artifactUri.withPath(artifactPath(groupId, Some(artifactId))) / "" // trailing slash to avoid the redirect

    client.expect[String](uri).map { html =>
      html.linesIterator.collect {
        case filenameAndDateExtractor(name, dateString) =>
          // reminder: SimpleDateFormat is not thread safe
          val dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm")
          name.stripSuffix("/") -> Try(dateFormat.parse(dateString)).toOption
      }.toSeq.sortBy(_._2).reverse.map(_._1)
    }
  }

  def latest(groupId: String, artifactId: String)(implicit client: Client[IO]): IO[Option[String]] = {
    searchVersions(groupId, artifactId).map(_.headOption)
  }


  // todo: javadoc exists
  // todo: head request?
  def artifactExists(groupId: String, artifactId: String, version: String)(implicit client: Client[IO]): IO[Boolean] = {
    val uri = artifactUri.withPath(artifactPath(groupId, Some(artifactId), Some(version)))
    client.statusFromUri(uri).map(_.isSuccess)
  }

  def javadocUri(groupId: String, artifactId: String, version: String): Uri = {
    artifactUri.withPath(artifactPath(groupId, Some(artifactId), Some(version))) / s"$artifactId-$version-javadoc.jar"
  }

  // todo: this is terrible
  def downloadAndExtractZip(source: Uri, destination: File)(implicit client: Client[IO]): IO[Unit] = {
    val s = client.stream(Request(uri = source)).filter(_.status.isSuccess).flatMap(_.body)

    fs2.io.toInputStreamResource(s).map(new ZipArchiveInputStream(_)).use { zipArchiveInputStream =>
      IO {
        LazyList
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
      }
    }

  }

}

*/
