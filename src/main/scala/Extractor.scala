import com.jamesward.zio_mavencentral.MavenCentral
import com.jamesward.zio_mavencentral.MavenCentral.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.{Cleaner, Safelist}
import zio.cache.Cache
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.http.{Client, URL}
import zio.prelude.data.Optional.AllValuesAreNullable
import zio.{Promise, Scope, ZIO}

import java.io.File
import java.nio.file.Files
import scala.jdk.CollectionConverters.*

object Extractor:
  case class TmpDir(dir: File)

  case class JavadocFileNotFound(groupArtifactVersion: GroupArtifactVersion, path: String)

  case class LatestNotFound(groupArtifact: GroupArtifact)

  case class Content(link: String, external: Boolean, fqn: String, `type`: String, kind: String, extra: String)

  type LatestCache = Cache[GroupArtifact, GroupIdOrArtifactIdNotFoundError | LatestNotFound, Version]
  type JavadocCache = Cache[GroupArtifactVersion, JavadocNotFoundError, File]
  type FetchBlocker = ConcurrentMap[GroupArtifactVersion, Promise[Nothing, Unit]]

  def gav(groupId: String, artifactId: String, version: String) =
    GroupArtifactVersion(GroupId(groupId), ArtifactId(artifactId), Version(version))

  def latest(groupArtifact: GroupArtifact): ZIO[Client, GroupIdOrArtifactIdNotFoundError | LatestNotFound, Version] =
    ZIO.scoped:
      MavenCentral.latest(groupArtifact.groupId, groupArtifact.artifactId)
        .catchAll:
          case t: Throwable => ZIO.die(t)
          case groupIdOrArtifactIdNotFoundError: GroupIdOrArtifactIdNotFoundError => ZIO.fail(groupIdOrArtifactIdNotFoundError)
        .someOrFail(LatestNotFound(groupArtifact))

  def javadoc(groupArtifactVersion: GroupArtifactVersion):
      ZIO[Client & FetchBlocker & TmpDir, JavadocNotFoundError, File] =
    ZIO.scoped:
      val javadocUriOrDie: ZIO[Client & Scope, JavadocNotFoundError, URL] = MavenCentral.javadocUri(groupArtifactVersion.groupId, groupArtifactVersion.artifactId, groupArtifactVersion.version).catchAll:
        case t: Throwable => ZIO.die(t)
        case javadocNotFoundError: JavadocNotFoundError => ZIO.fail(javadocNotFoundError)

      defer:
        val javadocUrl = javadocUriOrDie.run
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
              MavenCentral.downloadAndExtractZip(javadocUrl, javadocDir).orDie.run
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
      yield Content(link, external, s"$decl.$name", tpe, kind, extra)
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
        // todo: extract kind i.e. abstract class SingleInstancePool<T : Any> : ObjectPool<T>
      yield Content(location, false, description, name.trim, "", "")
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
          path.getFileName.toString.stripSuffix(".html"),
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
      .orElseFail(JavadocFormatFailure())

  // todo: handle order version of dokka (fun!)
  def javadocKotlinFormat(groupArtifactVersion: GroupArtifactVersion, javadocDir: File):
      ZIO[Any, JavadocFormatFailure, Set[Content]] =
    javadocFile(groupArtifactVersion, javadocDir, "scripts/pages.json")
      .flatMap: file =>
        val contents = Files.readString(file.toPath)
        ZIO.fromEither(parseKotlindoc(contents))
      .orElseFail(JavadocFormatFailure())

  // could be better based on index-all.html
  def javadocJavaFormat(groupArtifactVersion: GroupArtifactVersion, javadocDir: File):
      ZIO[Any, JavadocFormatFailure, Set[Content]] =
    javadocFile(groupArtifactVersion, javadocDir, "element-list").mapBoth(
      _ => JavadocFormatFailure(),
      file =>
        val elements = Files.readAllLines(file.toPath).asScala
        elements.flatMap: element =>
          val elementDir = File(javadocDir, element.replace('.', '/'))
          val files = Files.walk(elementDir.toPath).iterator().asScala
          files
            .map:
              javadocDir.toPath.relativize
            .filter:
              file =>
                file.toString.endsWith(".html") &&
                  !file.getFileName.toString.startsWith("package-") &&
                  !file.toString.contains("class-use")
            .map:
              file =>
                val fqn = file.toString.stripSuffix(".html").replace('/', '.')
                Content(file.toString, false, fqn, "", "", "")
        .flatten
        .toSet
    )

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

  def textSymbolContents(contents: String, path: String): String =
    val document = Jsoup.parse(contents)
    val cleaner = new Cleaner(Safelist.none())

    val outputSettings = Document.OutputSettings()

    val clean = cleaner.clean(document)
    clean.outputSettings(outputSettings)
    clean.wholeText().replaceAll("\n{3,}", "\n\n---\n\n")

  def javadocSymbolContents(groupArtifactVersion: GroupArtifactVersion, path: String):
      ZIO[JavadocCache & Client & FetchBlocker & Scope, JavadocNotFoundError | JavadocFileNotFound, String] =
    defer:
      val javadocCache = ZIO.service[JavadocCache].run
      val javadocDir = javadocCache.get(groupArtifactVersion).run

      javadocFile(groupArtifactVersion, javadocDir, path)
        .map: file =>
          val contents = Files.readString(file.toPath)
          if contents.contains("Generated by javadoc") then
            textSymbolContents(contents, path)
          else
            contents
        .run
