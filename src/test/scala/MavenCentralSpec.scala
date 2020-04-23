import java.nio.file.Files

import MavenCentral._
import cats.effect.{ContextShift, IO, Timer}
import org.http4s.Uri
import org.http4s.client.blaze.BlazeClientBuilder
import org.specs2.mutable.Specification

import scala.concurrent.ExecutionContext.global

class MavenCentralSpec extends Specification {

  implicit val cs: ContextShift[IO] = IO.contextShift(global)
  implicit val timer: Timer[IO] = IO.timer(global)

  // todo: test sorting
  "searchArtifacts" >> {
    BlazeClientBuilder[IO](global).resource.use { client =>
      searchArtifacts("com.jamesward")(client)
    }.unsafeRunSync() must not be empty
  }

  // todo: test sorting
  "searchVersions" >> {
    BlazeClientBuilder[IO](global).resource.use { client =>
      searchVersions("com.jamesward", "travis-central-test")(client)
    }.unsafeRunSync() must not be empty
  }

  "latest" >> {
    BlazeClientBuilder[IO](global).resource.use { client =>
      latest("com.jamesward", "travis-central-test")(client)
    }.unsafeRunSync() must beSome ("0.0.15")
  }

  "artifactExists" >> {
    "works for existing artifacts" >> {
      BlazeClientBuilder[IO](global).resource.use { client =>
        artifactExists("com.jamesward", "travis-central-test", "0.0.15")(client)
      }.unsafeRunSync() must beTrue
    }
    "be false for non-existant artifacts" >> {
      BlazeClientBuilder[IO](global).resource.use { client =>
        artifactExists("com.jamesward", "travis-central-test", "0.0.0")(client)
      }.unsafeRunSync() must beFalse
    }
  }

  "downloadAndExtractZip" >> {
    val uri = Uri.unsafeFromString("https://repo1.maven.org/maven2/com/jamesward/travis-central-test/0.0.15/travis-central-test-0.0.15.jar")
    val tmpFile = Files.createTempDirectory("test").toFile
    BlazeClientBuilder[IO](global).resource.use { implicit client =>
      downloadAndExtractZip(uri, tmpFile)
    }.unsafeRunSync()
    tmpFile.list().toList must contain ("META-INF")
  }

}
