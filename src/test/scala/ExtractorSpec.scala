import Extractor.gav
import com.jamesward.zio_mavencentral.MavenCentral.*
import zio.direct.*
import zio.http.Client
import zio.test.*
import zio.test.Assertion.failsWithA
import zio.{Exit, Scope, ZIO, ZLayer, durationInt}

object ExtractorSpec extends ZIOSpecDefault:

  def spec = suite("Extractor")(
    test("parseScaladoc") {
      val contents = Extractor.parseScaladoc(
        """[{
          |    "l": "index.html#",
          |    "e": false,
          |    "i": "",
          |    "n": "zio-mavencentral",
          |    "t": "zio-mavencentral",
          |    "d": "",
          |    "k": "static",
          |    "x": ""
          |}, {
          |    "l": "com/jamesward/zio_mavencentral.html#",
          |    "e": false,
          |    "i": "",
          |    "n": "com.jamesward.zio_mavencentral",
          |    "t": "com.jamesward.zio_mavencentral",
          |    "d": "",
          |    "k": "package",
          |    "x": ""
          |}]
          |""".stripMargin).toOption.get

      assertTrue(
        contents.size == 2,
      )
    },
    test("artifact does not exist") {
      assertZIO(Extractor.javadocContents(gav("com.jamesward", "zio-mavencentral_3", "0.0.0")).exit)(
        failsWithA[NotFoundError]
      )
    },
    test("javadoc does not exist") {
      assertZIO(Extractor.javadoc(gav("com.jamesward", "zio-mavencentral_3", "0.0.0")).exit)(
        failsWithA[NotFoundError]
      )
    },
    test("sources do not exist") {
      assertZIO(Extractor.sources(gav("com.jamesward", "zio-mavencentral_3", "0.0.0")).exit)(
        failsWithA[NotFoundError]
      )
    },
    test("javadoc file not found") {
      val groupArtifactVersion = gav("com.jamesward", "zio-mavencentral_3", "0.0.21")
      assertZIO {
        Extractor.javadoc(groupArtifactVersion).flatMap { javadocDir =>
          Extractor.javadocFile(groupArtifactVersion, javadocDir, "asdf").exit
        }
      }(
        failsWithA[Extractor.JavadocFileNotFound]
      )
    },
    test("scaladoc - zio-mavencentral_3") {
      defer:
        val scaladoc = Extractor.javadocContents(gav("com.jamesward", "zio-mavencentral_3", "0.0.21")).run
        assertTrue(
          scaladoc.size == 48,
          scaladoc.exists { contents =>
            contents.link == "com/jamesward/zio_mavencentral/MavenCentral$.html#" &&
              contents.fqn == "com.jamesward.zio_mavencentral.MavenCentral" &&
              contents.kind == "object"
          },
          scaladoc.exists(_.`type` == "searchArtifacts(groupId: GroupId): ZIO[Client & Scope, GroupIdNotFoundError | Throwable, Seq[ArtifactId]]")
        )
    },
    test("scaladoc - zio_3") {
      defer:
        val scaladoc = Extractor.javadocContents(gav("dev.zio", "zio_3", "2.1.9")).run
        assertTrue(
          scaladoc.size == 3696
        )
    },
    test("scaladoc - zio_2.13") {
      defer:
        val scaladoc = Extractor.javadocContents(gav("dev.zio", "zio_2.13", "2.1.9")).run
        assertTrue(
          scaladoc.size == 544
        )
    },
    test("kotlin - ktor-io-jvm/3.2.3") {
      defer:
        val doccontents = Extractor.javadocContents(gav("io.ktor", "ktor-io-jvm", "3.2.3")).run
        assertTrue(
          doccontents.size == 465,
          doccontents.exists(_.fqn == "io.ktor.utils.io.pool.SingleInstancePool"),
          doccontents.exists(_.`type` == "abstract class SingleInstancePool<T : Any> : ObjectPool<T>"),
        )
    },
    test("java - spring-ai-mcp/1.0.1") {
      defer:
        val doccontents = Extractor.javadocContents(gav("org.springframework.ai", "spring-ai-mcp", "1.0.1")).run
        assertTrue(
          doccontents.size == 8,
          doccontents.exists(_.fqn == "org.springframework.ai.mcp.SyncMcpToolCallback")
        )
    },
    test("symbolContents - zio-mavencentral_3") {
      defer:
        val contents = Extractor.javadocSymbolContents(gav("com.jamesward", "zio-mavencentral_3", "0.0.21"), "com/jamesward/zio_mavencentral/MavenCentral$$GroupId$.html#unapply-fffffd22").run
        assertTrue(
          contents.contains("com.jamesward.zio_mavencentral.MavenCentral.GroupId")
        )
    },
    test("symbolContents - smaller") {
      defer:
        val contents = Extractor.javadocSymbolContents(gav("com.vaadin", "vaadin-confirm-dialog-flow", "24.9.0"), "com/vaadin/flow/component/confirmdialog/ConfirmDialog.html").run
        assertTrue(
          contents.length < 55_000,
          contents.contains("This method is inherited from"),
          contents.lines().count() > 900,
        )
    },
    test("sourceContents - zio-mavencentral_3") {
      defer:
        val contents = Extractor.sourceFileContents(gav("com.jamesward", "zio-mavencentral_3", "0.0.21"), "com/jamesward/zio_mavencentral/MavenCentral.scala").run
        assertTrue(
          contents.contains("object MavenCentral:")
        )
    },
    test("listSourceContents - zio-mavencentral_3") {
      defer:
        val contents = Extractor.sourceContents(gav("com.jamesward", "zio-mavencentral_3", "0.0.21")).run
        assertTrue(
          contents.size == 2,
          contents.contains("com/jamesward/zio_mavencentral/MavenCentral.scala")
        )
    },
    test("parallel downloads: 20 artifacts concurrently") {
      val gavs = Seq(
        gav("com.jamesward", "zio-mavencentral_3", "0.0.21"),
        gav("dev.zio", "zio_3", "2.1.9"),
        gav("dev.zio", "zio_2.13", "2.1.9"),
        gav("io.ktor", "ktor-io-jvm", "3.2.3"),
        gav("io.ktor", "ktor-http-jvm", "3.2.3"),
        gav("io.ktor", "ktor-utils-jvm", "3.2.3"),
        gav("io.ktor", "ktor-serialization-jvm", "3.2.3"),
        gav("io.ktor", "ktor-client-core-jvm", "3.2.3"),
        gav("io.ktor", "ktor-events-jvm", "3.2.3"),
        gav("io.ktor", "ktor-websockets-jvm", "3.2.3"),
        gav("org.springframework.ai", "spring-ai-mcp", "1.0.1"),
        gav("com.vaadin", "vaadin-confirm-dialog-flow", "24.9.0"),
        gav("org.webjars", "webjars-locator-lite", "1.1.3"),
        gav("org.webjars", "webjars-locator-core", "0.52"),
        gav("org.jsoup", "jsoup", "1.22.2"),
        gav("org.slf4j", "slf4j-simple", "2.0.17"),
        gav("org.slf4j", "slf4j-api", "2.0.17"),
        gav("dev.zio", "zio-schema_3", "1.8.3"),
        gav("dev.zio", "zio-streams_3", "2.1.25"),
        gav("dev.zio", "zio-test_3", "2.1.25"),
      )
      defer:
        val dirs = ZIO.foreachPar(gavs)(Extractor.javadoc).run
        assertTrue(dirs.size == 20, dirs.forall(_.exists()))
    } @@ TestAspect.timeout(2.minutes) @@ TestAspect.withLiveClock
  ).provide(
    Scope.default,
    Client.default,
    App.javadocCacheLayer,
    App.sourcesCacheLayer,
    App.blockerLayer,
    App.sourcesBlockerLayer,
    App.tmpDirLayer,
  )
