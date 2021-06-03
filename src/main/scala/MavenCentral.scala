import java.io.File
import java.nio.file.Files

import cats.effect._
import cats.implicits._
import io.circe.Json
import io.circe.optics.JsonPath.root
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.implicits._
import org.http4s.{Request, Uri}

object MavenCentral {

  object CaseInsensitiveOrdering extends Ordering[String] {
    def compare(a:String, b:String): Int = a.toLowerCase compare b.toLowerCase
  }

  def artifactPath(groupId: String, artifactId: String, version: String): String = {
    Seq(
      "maven2",
      groupId.replace('.', '/'),
      artifactId,
      version
    ).mkString("/")
  }

  private val searchUri = uri"https://search.maven.org/solrsearch/select"

  private val _docs = root.response.docs.each

  def searchArtifacts(groupId: String)(implicit client: Client[IO]): IO[Seq[String]] = {
    val uri = searchUri.withQueryParams(Map(
      "q" -> s"""g:"$groupId"""",
      "rows" -> "5000",
      "wt" -> "json",
    ))

    val _artifactIds = _docs.a.string

    client.expect[Json](uri).map { json =>
      _artifactIds.getAll(json).sorted
    }
  }

  def groupAndArtifactSearchUri(groupId: String, artifactId: String): Uri = {
    searchUri.withQueryParams(Map(
      "q" -> s"""g:"$groupId" AND a:"$artifactId"""",
      "core" -> "gav",
      "rows" -> "5000",
      "wt" -> "json",
    ))
  }

  def searchVersions(groupId: String, artifactId: String)(implicit client: Client[IO]): IO[Seq[String]] = {
    client.expect[Json](groupAndArtifactSearchUri(groupId, artifactId)).map { json =>
      _docs.obj.getAll(json).flatMap { jsonObj =>
        for {
          timestamp <- jsonObj("timestamp").flatMap(_.as[Long].toOption)
          version <- jsonObj("v").flatMap(_.as[String].toOption)
        } yield (timestamp, version)
      }.sortBy(_._1)(Ordering[Long].reverse).map(_._2)
    }
  }

  def latest(groupId: String, artifactId: String)(implicit client: Client[IO]): IO[Option[String]] = {
    searchVersions(groupId, artifactId).map(_.headOption)
  }

  // todo: regionalize to maven central mirrors for lower latency
  val artifactUri = uri"https://repo1.maven.org/"

  // todo: javadoc exists
  // todo: head request?
  def artifactExists(groupId: String, artifactId: String, version: String)(implicit client: Client[IO]): IO[Boolean] = {
    val uri = artifactUri.withPath(artifactPath(groupId, artifactId, version))
    client.statusFromUri(uri).map(_.isSuccess)
  }

  def javadocUri(groupId: String, artifactId: String, version: String): Uri = {
    artifactUri.withPath(artifactPath(groupId, artifactId, version)) / s"$artifactId-$version-javadoc.jar"
  }

  // todo: this is terrible
  def downloadAndExtractZip(source: Uri, destination: File)(implicit client: Client[IO], contextShift: ContextShift[IO]): IO[Unit] = {
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
