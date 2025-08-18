import com.jamesward.zio_mavencentral.MavenCentral
import com.jamesward.zio_mavencentral.MavenCentral.*
import zio.cache.Cache
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.http.Client
import zio.prelude.data.Optional.AllValuesAreNullable
import zio.{Promise, Scope, ZIO}

import java.io.File
import java.nio.file.Files
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

    val normalizedPath = path.takeWhile(_ != '#')

    val javadocFile = File(javadocDir, normalizedPath)

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

  def parseKotlindoc(contents: String): Either[io.circe.Error, Set[Content]] =
    import io.circe.Decoder
    import io.circe.parser.decode

    given Decoder[Content] = Decoder.instance { c =>
      for
        name <- c.downField("name").as[String]
        description <- c.downField("description").as[String]
        location <- c.downField("location").as[String]
        // searchKeys <- c.downField("searchKeys").as[List[String]]
      yield Content(location, false, description, name, "", "", "", "")
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

  case class JavadocFormatFailure()

  // todo: handle order version of scaladoc (fun!)
  def javadocScalaFormat(groupArtifactVersion: GroupArtifactVersion, javadocDir: File):
      ZIO[Any, JavadocFormatFailure, Set[Content]] =
    javadocFile(groupArtifactVersion, javadocDir, "scripts/searchData.js")
      .flatMap: file =>
        val contents = Files.readString(file.toPath).stripPrefix("pages = ").stripSuffix(";")
        ZIO.fromEither(parseScaladoc(contents))
      .mapError: _ =>
        JavadocFormatFailure()

  // todo: handle order version of dokka (fun!)
  def javadocKotlinFormat(groupArtifactVersion: GroupArtifactVersion, javadocDir: File):
      ZIO[Any, JavadocFormatFailure, Set[Content]] =
    javadocFile(groupArtifactVersion, javadocDir, "scripts/pages.json")
      .flatMap: file =>
        val contents = Files.readString(file.toPath)
        ZIO.fromEither(parseKotlindoc(contents))
      .mapError: _ =>
        JavadocFormatFailure()

  // could be better based on index-all.html
  def javadocJavaFormat(groupArtifactVersion: GroupArtifactVersion, javadocDir: File):
      ZIO[Any, JavadocFormatFailure, Set[Content]] =
    javadocFile(groupArtifactVersion, javadocDir, "element-list")
      .map: file =>
        val elements = Files.readAllLines(file.toPath).asScala
        elements.flatMap: element =>
          val elementDir = File(javadocDir, element.replace('.', '/'))
          val files = Files.walk(elementDir.toPath).iterator().asScala
          files
            .filter(_.toString.endsWith(".html"))
            .map: file =>
              Content(javadocDir.toPath.relativize(file).toString, false, "", "", "", "", "", "")
        .flatten
        .toSet
      .mapError: _ =>
        JavadocFormatFailure()

  def javadocContents(groupArtifactVersion: GroupArtifactVersion):
      ZIO[JavadocCache & Client & FetchBlocker & Scope, JavadocNotFoundError, Set[Content]] =
    defer:
      val javadocCache = ZIO.service[JavadocCache].run
      val javadocDir = javadocCache.get(groupArtifactVersion).run

      javadocScalaFormat(groupArtifactVersion, javadocDir)
        .orElse(javadocKotlinFormat(groupArtifactVersion, javadocDir))
        .orElse(javadocJavaFormat(groupArtifactVersion, javadocDir))
        .catchAll:
          case _: JavadocFormatFailure =>
            ZIO.succeed(bruteForce(javadocDir))
          case e: JavadocNotFoundError =>
            ZIO.fail(e)
        .run

  def javadocSymbolContents(groupArtifactVersion: GroupArtifactVersion, path: String):
      ZIO[JavadocCache & Client & FetchBlocker & Scope, JavadocNotFoundError | JavadocFileNotFound, String] =
    defer:
      val javadocCache = ZIO.service[JavadocCache].run
      val javadocDir = javadocCache.get(groupArtifactVersion).run

      javadocFile(groupArtifactVersion, javadocDir, path)
        .map: file =>
          Files.readString(file.toPath)
        .run
