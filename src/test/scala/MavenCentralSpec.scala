import zio.{Chunk, Runtime, ZIO}
import zio.http.{Client, Path, Request, URL}
import zio.test.*
import zio.test.Assertion.*
import MavenCentral.*
import zio.direct.*

object MavenCentralSpec extends ZIOSpecDefault:

  given CanEqual[Path, Path] = CanEqual.derived

  def spec = suite("MavenCentral")(
    test("artifactPath") {
      assertTrue(artifactPath(GroupId("org.webjars")) == Path.decode("org/webjars"))
      assertTrue(artifactPath(GroupId("org.webjars"), Some(ArtifactAndVersion(ArtifactId("jquery")))) == Path.decode("org/webjars/jquery"))
      assertTrue(artifactPath(GroupId("org.webjars"), Some(ArtifactAndVersion(ArtifactId("jquery"), Some(Version("3.6.4"))))) == Path.decode("org/webjars/jquery/3.6.4"))
    },
    test("searchArtifacts") {
      defer {
        val artifacts = searchArtifacts(GroupId("org.webjars")).run
        assertTrue(artifacts.size > 1000)
      }
    }.provide(Client.default),
  )

/*
import MavenCentral._
import cats.effect.{IO, Resource}
import cats.effect.testing.specs2.CatsResource
import org.http4s.Uri
import org.http4s.blaze.client.BlazeClientBuilder
import org.http4s.client.Client
import org.specs2.mutable.SpecificationLike

import java.nio.file.Files

class MavenCentralSpec extends CatsResource[IO, Client[IO]] with SpecificationLike {

  val resource: Resource[IO, Client[IO]] = BlazeClientBuilder[IO].resource

  // todo: test sorting
  "searchArtifacts" in withResource { implicit client =>
    searchArtifacts("com.jamesward").map {
      _ must not be empty
    }
  }

  // todo: test sorting
  "searchVersions" in withResource { implicit client =>
    searchVersions("com.jamesward", "travis-central-test").map {
      _ must not be empty
    }
  }

  "latest" in withResource { implicit client =>
    latest("com.jamesward", "travis-central-test").map {
      _ must beSome("0.0.15")
    }
  }

  "artifactExists" should {
    "works for existing artifacts" in withResource { implicit client =>
      artifactExists("com.jamesward", "travis-central-test", "0.0.15").map {
        _ must beTrue
      }
    }
    "be false for non-existant artifacts" in withResource { implicit client =>
      artifactExists("com.jamesward", "travis-central-test", "0.0.0").map {
        _ must beFalse
      }
    }
  }

  "downloadAndExtractZip" in withResource { implicit client =>
    val uri = Uri.unsafeFromString("https://repo1.maven.org/maven2/com/jamesward/travis-central-test/0.0.15/travis-central-test-0.0.15.jar")
    val tmpFile = Files.createTempDirectory("test").toFile
    downloadAndExtractZip(uri, tmpFile).map { _ =>
      tmpFile.list().toList must contain ("META-INF")
    }
  }

}

 */

