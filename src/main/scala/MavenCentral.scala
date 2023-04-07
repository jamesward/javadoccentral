import zio.ZIO
import zio.http.{Client, Path}
import zio.direct.*
import zio.direct.stream.{each, defer as deferStream}
import zio.http.model.Method
import zio.stream.{ZPipeline, ZStream}

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import scala.util.Try
import scala.util.matching.Regex

object MavenCentral:

  // todo: regionalize to maven central mirrors for lower latency
  val artifactUri = "https://repo1.maven.org/maven2/"

  case class MavenCentralError() extends Exception

  opaque type GroupId = String
  object GroupId:
    def apply(s: String): GroupId = s

  opaque type ArtifactId = String
  object ArtifactId:
    def apply(s: String): ArtifactId = s

  opaque type Version = String
  object Version:
    def apply(s: String): Version = s

  opaque type Url = String
  object Url:
    def apply(s: String): Url = s

  case class ArtifactAndVersion(artifactId: ArtifactId, maybeVersion: Option[Version] = None)

  def artifactPath(groupId: GroupId, artifactAndVersion: Option[ArtifactAndVersion] = None): Path = {
    val withGroup = groupId.split('.').foldLeft(Path.empty)(_ / _)
    val withArtifact = artifactAndVersion.fold(withGroup)(withGroup / _.artifactId)
    val withVersion = artifactAndVersion.flatMap(_.maybeVersion).fold(withArtifact)(withArtifact / _)

    withVersion
  }

  private val filenameExtractor: Regex = """.*<a href="([^"]+)".*""".r

  // 2018-04-06 15:59
  private val filenameAndDateExtractor: Regex = """.*<a href="([^"]+)".*</a>\s+(\d+-\d+-\d+\s\d+:\d+)\s+-.*""".r

  def searchArtifacts(groupId: GroupId): ZIO[Client, Throwable, Seq[ArtifactId]] = {
    val url = artifactUri + artifactPath(groupId).addTrailingSlash
    defer {
      val resp = Client.request(url, addZioUserAgentHeader = true).run
      deferStream {
        resp.body.asStream.via(ZPipeline.utf8Decode >>> ZPipeline.splitLines).each
      }.runFold(Seq.empty[ArtifactId]) { (lines, line) =>
        line match {
          case filenameExtractor(line) if line.endsWith("/") && !line.startsWith("..") =>
            lines :+ ArtifactId(line.stripSuffix("/"))
          case _ =>
            lines
        }
      }.run
    }
  }

  def searchVersions(groupId: GroupId, artifactId: ArtifactId): ZIO[Client, Throwable, Seq[Version]] = {
    val url = artifactUri + artifactPath(groupId, Some(ArtifactAndVersion(artifactId))).addTrailingSlash
    defer {
      val resp = Client.request(url, addZioUserAgentHeader = true).run
      deferStream {
        resp.body.asStream.via(ZPipeline.utf8Decode >>> ZPipeline.splitLines).each
      }.runFold(Seq.empty[(Version, Option[Date])]) { (lines, line) =>
        line match {
          case filenameAndDateExtractor(name, dateString) =>
            // reminder: SimpleDateFormat is not thread safe
            val dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm")
            val date = Try(dateFormat.parse(dateString)).collect {
              case d: Date => d
            }.toOption
            lines :+ Version(name.stripSuffix("/")) -> date
          case _ =>
            lines
        }
      }.run.sortBy(_._2).reverse.map(_._1)
    }
  }

  def latest(groupId: GroupId, artifactId: ArtifactId): ZIO[Client, Throwable, Option[Version]] = {
    searchVersions(groupId, artifactId).map(_.headOption)
  }

  def artifactExists(groupId: GroupId, artifactId: ArtifactId, version: Version): ZIO[Client, Throwable, Boolean] = {
    val url = artifactUri + artifactPath(groupId, Some(ArtifactAndVersion(artifactId, Some(version)))).addTrailingSlash
    Client.request(url, Method.HEAD).map(_.status.isSuccess)
  }

  def javadocUri(groupId: GroupId, artifactId: ArtifactId, version: Version): ZIO[Client, Throwable, Url] = {
    val url = artifactUri + artifactPath(groupId, Some(ArtifactAndVersion(artifactId, Some(version)))) / s"$artifactId-$version-javadoc.jar"
    defer {
      val response = Client.request(url, Method.HEAD).run
      if response.status.isSuccess then
        Url(url)
      else
        throw MavenCentralError()
    }
  }

  // todo: this is terrible
  def downloadAndExtractZip(source: Url, destination: File): ZIO[Client, Throwable, Unit] = {
    defer {
      val resp = Client.request(source, addZioUserAgentHeader = true).run
      if resp.status.isError then
        throw MavenCentralError()
      else
        resp.body.asStream.run
        ()
    }
    /*
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
    */

  }

/*

object MavenCentral {

  object CaseInsensitiveOrdering extends Ordering[String] {
    def compare(a:String, b:String): Int = a.toLowerCase compare b.toLowerCase
  }

}

*/
