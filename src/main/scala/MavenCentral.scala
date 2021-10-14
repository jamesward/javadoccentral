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

  // todo: version can't be Some if artifactId is None
  def artifactPath(groupId: String, artifactId: Option[String] = None, version: Option[String] = None): Uri.Path = {
    val withGroup = Vector("maven2") :++ groupId.split('.')
    val withArtifact = artifactId.fold(withGroup)(withGroup :+ _)
    val withVersion = version.fold(withArtifact)(withArtifact :+ _)
    Uri.Path(withVersion.map(Uri.Path.Segment.encoded), absolute = true)
  }

  // todo: regionalize to maven central mirrors for lower latency
  val artifactUri = uri"https://repo1.maven.org/"

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
