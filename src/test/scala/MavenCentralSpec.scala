import java.nio.file.Files
import MavenCentral._
import cats.effect.{Blocker, ContextShift, IO, Timer}
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.specs2.mutable.Specification

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.global

class MavenCentralSpec extends Specification {

  implicit val cs: ContextShift[IO] = IO.contextShift(global)
  implicit val timer: Timer[IO] = IO.timer(global)

  def runWithClient[OUT](f: Client[IO] => IO[OUT]): OUT = {
    BlazeClientBuilder[IO](ExecutionContext.global).resource.use(f(_)).unsafeRunSync()
  }

  // todo: test sorting
  "searchArtifacts" >> {
    runWithClient {
      searchArtifacts("com.jamesward")(_)
    } must not be empty
  }

  // todo: test sorting
  "searchVersions" >> {
    runWithClient {
      searchVersions("com.jamesward", "travis-central-test")(_)
    } must not be empty
  }

  "latest" >> {
    runWithClient {
      latest("com.jamesward", "travis-central-test")(_)
    } must beSome ("0.0.15")
  }

  "artifactExists" >> {
    "works for existing artifacts" >> {
      runWithClient {
        artifactExists("com.jamesward", "travis-central-test", "0.0.15")(_)
      } must beTrue
    }
    "be false for non-existant artifacts" >> {
      runWithClient {
        artifactExists("com.jamesward", "travis-central-test", "0.0.0")(_)
      } must beFalse
    }
  }

  "downloadAndExtractZip" >> {
    val uri = Uri.unsafeFromString("https://repo1.maven.org/maven2/com/jamesward/travis-central-test/0.0.15/travis-central-test-0.0.15.jar")
    val tmpFile = Files.createTempDirectory("test").toFile
    runWithClient {
      downloadAndExtractZip(uri, tmpFile)(_, cs)
    }

    tmpFile.list().toList must contain ("META-INF")
  }

}
