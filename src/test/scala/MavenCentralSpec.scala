import zio.{Chunk, Runtime, ZIO}
import zio.http.{Client, Path, Request, URL}
import zio.test.*
import zio.test.Assertion.*
import MavenCentral.*
import zio.direct.*

import java.net.URI
import java.nio.file.Files

object MavenCentralSpec extends ZIOSpecDefault:

  given CanEqual[Path, Path] = CanEqual.derived
  given CanEqual[Version, Version] = CanEqual.derived
  given CanEqual[Url, Url] = CanEqual.derived

  def spec = suite("MavenCentral")(
    test("artifactPath") {
      assertTrue(artifactPath(GroupId("org.webjars")) == Path.decode("org/webjars"))
      assertTrue(artifactPath(GroupId("org.webjars"), Some(ArtifactAndVersion(ArtifactId("jquery")))) == Path.decode("org/webjars/jquery"))
      assertTrue(artifactPath(GroupId("org.webjars"), Some(ArtifactAndVersion(ArtifactId("jquery"), Some(Version("3.6.4"))))) == Path.decode("org/webjars/jquery/3.6.4"))
    },

    // todo: test sorting
    test("searchArtifacts") {
      defer {
        val artifacts = searchArtifacts(GroupId("com.jamesward")).run
        assertTrue(artifacts.size > 3)
      }
    },

    test("searchVersions") {
      defer {
        val versions = searchVersions(GroupId("org.webjars"), ArtifactId("jquery")).run
        assertTrue(versions.contains("3.6.4"))
        assertTrue(versions.indexOf(Version("3.6.4")) < versions.indexOf(Version("3.6.3")))
      }
    },

    test("latest") {
      defer {
        assertTrue(latest(GroupId("com.jamesward"), ArtifactId("travis-central-test")).run.get == Version("0.0.15"))
      }
    },

    test("artifactExists") {
      defer {
        assertTrue(artifactExists(GroupId("com.jamesward"), ArtifactId("travis-central-test"), Version("0.0.15")).run)
        assertTrue(!artifactExists(GroupId("com.jamesward"), ArtifactId("travis-central-test"), Version("0.0.0")).run)
      }
    },

    test("javadocUri") {
      defer {
        val doesNotExist = javadocUri(GroupId("com.jamesward"), ArtifactId("travis-central-test"), Version("0.0.15")).exit.run
        assert(doesNotExist)(failsWithA[MavenCentralError])
        val doesExist = javadocUri(GroupId("org.webjars"), ArtifactId("webjars-locator-core"), Version("0.52")).run
        assertTrue(doesExist == Url("https://repo1.maven.org/maven2/org/webjars/webjars-locator-core/0.52/webjars-locator-core-0.52-javadoc.jar"))
      }
    },

    test("downloadAndExtractZip") {
      val url = Url("https://repo1.maven.org/maven2/com/jamesward/travis-central-test/0.0.15/travis-central-test-0.0.15.jar")
      val tmpFile = Files.createTempDirectory("test").nn.toFile.nn
      defer {
        downloadAndExtractZip(url, tmpFile).run
      }
      assertTrue(tmpFile.list().nn.contains("META-INF"))
    },
  ).provide(Client.default)
