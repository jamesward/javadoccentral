import com.jamesward.zio_mavencentral.MavenCentral
import com.jamesward.zio_mavencentral.MavenCentral.{ArtifactId, GroupArtifact, GroupArtifactVersion, GroupId, GroupIdOrArtifactIdNotFoundError, JavadocNotFoundError, Version}
import zio.cache.Cache
import zio.{Promise, Scope, ZIO}
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.http.Client

import java.io.File
import java.nio.file.{Files, NoSuchFileException}
import scala.jdk.CollectionConverters.*

object Extractor:
  case class TmpDir(dir: File)

  case class JavadocFileNotFound(groupArtifactVersion: GroupArtifactVersion, path: String)

  case class LatestNotFound(groupArtifact: GroupArtifact)

  case class Content(link: String, external: Boolean, info: String, name: String, `type`: String, declartion: String, kind: String, extra: String)

  type LatestCache = Cache[GroupArtifact, GroupIdOrArtifactIdNotFoundError | LatestNotFound, Version]
  type JavadocCache = Cache[GroupArtifactVersion, JavadocNotFoundError, File]
  type FetchBlocker = ConcurrentMap[GroupArtifactVersion, Promise[Nothing, Unit]]

  def latest(groupArtifact: GroupArtifact): ZIO[Client, GroupIdOrArtifactIdNotFoundError | LatestNotFound, Version] =
    ZIO.scoped:
      MavenCentral.latest(groupArtifact.groupId, groupArtifact.artifactId).someOrFail(LatestNotFound(groupArtifact))

  def javadoc(groupArtifactVersion: GroupArtifactVersion):
      ZIO[Client & FetchBlocker & TmpDir, JavadocNotFoundError, File] =
    ZIO.scoped:
      defer:
        val javadocUrl = MavenCentral.javadocUri(groupArtifactVersion.groupId, groupArtifactVersion.artifactId, groupArtifactVersion.version).run
        val blocker = ZIO.service[FetchBlocker].run
        val tmpDir = ZIO.service[TmpDir].run
        println(tmpDir.dir)
        val javadocDir = File(tmpDir.dir, groupArtifactVersion.toString)

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
              MavenCentral.downloadAndExtractZip(javadocUrl, javadocDir).run
              promise.succeed(()).run

        javadocDir

  def javadocFile(groupArtifactVersion: GroupArtifactVersion, javadocDir: File, path: String):
      ZIO[Any, JavadocFileNotFound, File] =
    val javadocFile = File(javadocDir, path)

    if javadocFile.exists() then
      ZIO.succeed(javadocFile)
    else
      ZIO.fail(JavadocFileNotFound(groupArtifactVersion, path))

  def parseScaladoc(contents: String): Either[io.circe.Error, Set[Content]] =
    import io.circe.Decoder
    import io.circe.parser.decode

    given Decoder[Content] = Decoder.instance { c =>
      for
        link <- c.downField("l").as[String]
        external <- c.downField("e").as[Boolean]
        info <- c.downField("i").as[String]
        name <- c.downField("n").as[String]
        tpe <- c.downField("t").as[String]
        decl <- c.downField("d").as[String]
        kind <- c.downField("k").as[String]
        extra <- c.downField("x").as[String]
      yield Content(link, external, info, name, tpe, decl, kind, extra)
    }

    decode[Set[Content]](contents)

  def bruteForce(baseDir: File): Set[Content] =
    // todo: handle index.html & zio/stm/index.html
    Files.walk(baseDir.toPath).iterator().asScala
      .filter { path =>
        path.toString.endsWith(".html")
      }
      .map { path =>
        Content(
          baseDir.toPath.relativize(path).toString,
          false,
          "",
          path.getFileName.toString.stripSuffix(".html"),
          "",
          "",
          "",
          "",
        )
      }
      .toSet

  def javadocContents(groupArtifactVersion: GroupArtifactVersion):
      ZIO[JavadocCache & Client & FetchBlocker & Scope, JavadocNotFoundError, Set[Content]] =
    defer:
      val javadocCache = ZIO.service[JavadocCache].run
      val javadocDir = javadocCache.get(groupArtifactVersion).run

      javadocFile(groupArtifactVersion, javadocDir, "scripts/searchData.js")
        .flatMap { file =>
          val contents = Files.readString(file.toPath).stripPrefix("pages = ").stripSuffix(";")
          ZIO.fromEither(parseScaladoc(contents))
        }
        .catchAll { // todo: catchSome and remove from error channel
          // fallback
          case _: io.circe.Error | _: JavadocFileNotFound =>
            ZIO.succeed(bruteForce(javadocDir))
          // keep JavadocNotFoundError
          case e: JavadocNotFoundError =>
            ZIO.fail(e)
        }
        .run

    // kotlin javadocs

    // java javadocs
