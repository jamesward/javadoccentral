import MavenCentral.{*, given}
import zio.{Chunk, Exit, Runtime, Scope, ZIO}
import zio.http.{Client, Path, Request, URL}
import zio.test.*
import zio.direct.*

import java.net.URI
import java.nio.file.Files

object MavenCentralSpec extends ZIOSpecDefault:

  given CanEqual[String, String] = CanEqual.derived
  given CanEqual[Seq[ArtifactId], Seq[ArtifactId]] = CanEqual.derived
  given CanEqual[Exit[JavadocNotFoundError | Throwable, _], Exit[JavadocNotFoundError | Throwable, _]] = CanEqual.derived

  def spec = suite("MavenCentral")(
    test("artifactPath"):
      assertTrue(
        artifactPath(GroupId("org.webjars")) == Path.decode("org/webjars"),
        artifactPath(GroupId("org.webjars"), Some(ArtifactAndVersion(ArtifactId("jquery")))) == Path.decode("org/webjars/jquery"),
        artifactPath(GroupId("org.webjars"), Some(ArtifactAndVersion(ArtifactId("jquery"), Some(Version("3.6.4"))))) == Path.decode("org/webjars/jquery/3.6.4")
      )
    ,
    test("searchArtifacts"):
      defer:
        val webjarArtifacts = searchArtifacts(GroupId("org.webjars")).run
        val springdataArtifacts = searchArtifacts(GroupId("org.springframework.data")).run
        val err = searchArtifacts(GroupId("zxcv12313asdf")).flip.run

        assertTrue(
          webjarArtifacts.size > 1000,
          webjarArtifacts == webjarArtifacts.sorted(CaseInsensitiveOrdering),
          springdataArtifacts.size > 10,
          err.isInstanceOf[GroupIdNotFoundError],
        )
    ,
    test("searchVersions"):
      defer:
        val versions = searchVersions(GroupId("org.webjars"), ArtifactId("jquery")).run
        val err = searchVersions(GroupId("com.jamesward"), ArtifactId("zxcvasdf")).flip.run

        assertTrue(
          versions.contains("3.6.4"),
          versions.indexOf(Version("1.10.1")) < versions.indexOf(Version("1.0.0")),
          err.isInstanceOf[GroupIdOrArtifactIdNotFoundError],
        )
    ,
    test("latest"):
      defer:
        assertTrue(latest(GroupId("com.jamesward"), ArtifactId("travis-central-test")).run.get == Version("0.0.15"))
    ,
    test("isArtifact"):
      defer:
        assertTrue(
          isArtifact(GroupId("com.jamesward"), ArtifactId("travis-central-test")).run,
          !isArtifact(GroupId("org.springframework"), ArtifactId("data")).run,
          !isArtifact(GroupId("org.springframework"), ArtifactId("cloud")).run,
        )
    ,
    test("artifactExists"):
      defer:
        assertTrue(
          artifactExists(GroupId("com.jamesward"), ArtifactId("travis-central-test"), Version("0.0.15")).run,
          !artifactExists(GroupId("com.jamesward"), ArtifactId("travis-central-test"), Version("0.0.0")).run,
        )
    ,
    test("javadocUri"):
      defer:
        val doesExist = javadocUri(GroupId("org.webjars"), ArtifactId("webjars-locator-core"), Version("0.52")).run
        val doesNotExist = javadocUri(GroupId("com.jamesward"), ArtifactId("travis-central-test"), Version("0.0.15")).exit.run // todo: flip no worky?

        assertTrue(
          doesNotExist == Exit.fail(JavadocNotFoundError(GroupId("com.jamesward"), ArtifactId("travis-central-test"), Version("0.0.15"))),
          doesExist == Url("https://repo1.maven.org/maven2/org/webjars/webjars-locator-core/0.52/webjars-locator-core-0.52-javadoc.jar"),
        )
    ,
    test("downloadAndExtractZip"):
      val url = Url("https://repo1.maven.org/maven2/com/jamesward/travis-central-test/0.0.15/travis-central-test-0.0.15.jar")
      val tmpFile = Files.createTempDirectory("test").nn.toFile.nn
      downloadAndExtractZip(url, tmpFile).as(assertTrue(tmpFile.list().nn.contains("META-INF")))
  ).provide(Client.default)
