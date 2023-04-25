import org.apache.commons.compress.archivers.ArchiveEntry
import zio.{Console, Scope, ZIO}
import zio.http.{Client, Method, Path, Response, Status}
import zio.direct.*
import zio.direct.stream.{each, defer as deferStream}
import zio.stream.{ZPipeline, ZStream}
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream

import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import scala.annotation.targetName
import scala.util.Try
import scala.util.matching.Regex

object MavenCentral:

  given CanEqual[Path, Path] = CanEqual.derived
  given CanEqual[Version, Version] = CanEqual.derived
  given CanEqual[Url, Url] = CanEqual.derived
  given CanEqual[Status, Status] = CanEqual.derived

  // todo: regionalize to maven central mirrors for lower latency
  val artifactUri = "https://repo1.maven.org/maven2/"

  sealed trait MavenCentralError extends Exception
  case class UnknownError(s: String) extends MavenCentralError
  case class GroupIdNotFoundError(groupId: GroupId) extends MavenCentralError
  case class GroupIdOrArtifactIdNotFoundError(groupId: GroupId, artifactId: ArtifactId) extends MavenCentralError
  case class JavadocNotFoundError(groupId: GroupId, artifactId: ArtifactId, version: Version) extends MavenCentralError

  extension (path: Path)
    @targetName("slash")
    def /(version: Version): Path = path / version

  opaque type GroupId = String
  object GroupId:
    def apply(s: String): GroupId = s
    def unapply(s: String): Option[GroupId] = Some(s)

  extension (groupId: GroupId)
    @targetName("slash")
    def /(artifactId: ArtifactId): Path = Path.root / groupId / artifactId

  opaque type ArtifactId = String
  object ArtifactId:
    def apply(s: String): ArtifactId = s
    def unapply(s: String): Option[ArtifactId] = Some(s)

  opaque type Version = String
  object Version:
    def apply(s: String): Version = s
    def unapply(s: String): Option[Version] = Some(s)
    val latest = Version("latest")

  opaque type Url = String
  object Url:
    def apply(s: String): Url = s

  case class ArtifactAndVersion(artifactId: ArtifactId, maybeVersion: Option[Version] = None)

  object CaseInsensitiveOrdering extends Ordering[ArtifactId]:
    def compare(a: ArtifactId, b: ArtifactId): Int = a.compareToIgnoreCase(b)


  def artifactPath(groupId: GroupId, artifactAndVersion: Option[ArtifactAndVersion] = None): Path = {
    val withGroup = groupId.split('.').foldLeft(Path.empty)(_ / _)
    val withArtifact = artifactAndVersion.fold(withGroup)(withGroup / _.artifactId)
    val withVersion = artifactAndVersion.flatMap(_.maybeVersion).fold(withArtifact)(withArtifact / _)

    withVersion
  }

  private val filenameExtractor: Regex = """.*<a href="([^"]+)".*""".r

  private def lineExtractor(names: Seq[String], line: String): Seq[String] =
    line match {
      case filenameExtractor(line) if line.endsWith("/") && !line.startsWith("..") =>
        names :+ line.stripSuffix("/")
      case _ =>
        names
    }

  private def responseToNames(response: Response): ZIO[Any, Throwable, Seq[String]] =
    deferStream {
      response.body.asStream.via(ZPipeline.utf8Decode >>> ZPipeline.splitLines).each
    }.runFold(Seq.empty[String])(lineExtractor)

  def searchArtifacts(groupId: GroupId): ZIO[Client, Throwable, Seq[ArtifactId]] = {
    val url = artifactUri + artifactPath(groupId).addTrailingSlash
    defer {
      val resp = Client.request(url).run
      if resp.status == Status.NotFound then
        throw GroupIdNotFoundError(groupId)
      else if resp.status.isSuccess then
        responseToNames(resp).run.map(ArtifactId(_)).sorted(CaseInsensitiveOrdering)
      else
        throw UnknownError(resp.status.text)
    }
  }

  def searchVersions(groupId: GroupId, artifactId: ArtifactId): ZIO[Client, Throwable, Seq[Version]] = {
    val url = artifactUri + artifactPath(groupId, Some(ArtifactAndVersion(artifactId))).addTrailingSlash
    defer {
      val resp = Client.request(url).run
      if resp.status == Status.NotFound then
        throw GroupIdOrArtifactIdNotFoundError(groupId, artifactId)
      else if resp.status.isSuccess then
        responseToNames(resp).run.map(Version(_)).reverse
      else
        throw UnknownError(resp.status.text)
    }
  }

  def latest(groupId: GroupId, artifactId: ArtifactId): ZIO[Client, Throwable, Option[Version]] = {
    searchVersions(groupId, artifactId).map(_.headOption)
  }

  def isArtifact(groupId: GroupId, artifactId: ArtifactId): ZIO[Client, Throwable, Boolean] = {
    val url = artifactUri + artifactPath(groupId, Some(ArtifactAndVersion(artifactId))) / "maven-metadata.xml"
    defer {
      val response = Client.request(url).run
      if response.status.isSuccess then
        val body = response.body.asString.run
        // checks that the maven-metadata.xml contains the groupId
        body.contains(s"<groupId>${groupId}</groupId>")
      else
        false
    }
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
      else if response.status == Status.NotFound then
        throw JavadocNotFoundError(groupId, artifactId, version)
      else
        throw UnknownError(response.status.text)
    }
  }

  // todo: this is terrible
  //       zip handling via zio?
  //       what about file locking
  def downloadAndExtractZip(source: Url, destination: File): ZIO[Client & Scope, Throwable, Unit] = {
    defer {
      val resp = Client.request(source).run
      if resp.status.isError then
        throw UnknownError(resp.status.text)
      else
        val zipArchiveInputStream = ZIO.fromAutoCloseable {
          resp.body.asStream.toInputStream.map(ZipArchiveInputStream(_))
        }.run

        LazyList
          .continually(zipArchiveInputStream.getNextEntry)
          .takeWhile {
            case _: ArchiveEntry => true
            case _ => false
          }
          .foreach { ze =>
            val tmpFile = File(destination, ze.nn.getName)
            if (ze.nn.isDirectory) {
              tmpFile.mkdirs()
            }
            else {
              if (!tmpFile.getParentFile.nn.exists()) {
                tmpFile.getParentFile.nn.mkdirs()
              }
              Files.copy(zipArchiveInputStream, tmpFile.toPath)
            }
          }
    }
  }
