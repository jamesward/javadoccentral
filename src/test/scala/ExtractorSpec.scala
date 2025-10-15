import com.jamesward.zio_mavencentral.MavenCentral.*
import zio.cache.Cache
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.http.Client
import zio.test.*
import zio.test.Assertion.failsWithA
import zio.{Exit, Scope, ZLayer}


object ExtractorSpec extends ZIOSpecDefault:

  def gav(groupId: String, artifactId: String, version: String) =
    GroupArtifactVersion(GroupId(groupId), ArtifactId(artifactId), Version(version))

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
    test("parseKotlindoc") {
      val contents = Extractor.parseKotlindoc(
        """[{
          |    "name": "MyClass",
          |    "description": "A sample class",
          |    "location": "com/example/MyClass.html"
          |}, {
          |    "name": "MyFunction",
          |    "description": "A sample function",
          |    "location": "com/example/MyFunction.html"
          |}]
          |""".stripMargin).toOption.get

      assertTrue(
        contents.size == 2,
        contents.exists(c => c.name == "MyClass" && c.link == "com/example/MyClass.html")
      )
    },
    test("artifact does not exist") {
      assertZIO(Extractor.javadocContents(gav("com.jamesward", "zio-mavencentral_3", "0.0.0")).exit)(
        failsWithA[JavadocNotFoundError]
      )
    },
    test("javadoc does not exist") {
      assertZIO(Extractor.javadoc(gav("com.jamesward", "zio-mavencentral_3", "0.0.0")).exit)(
        failsWithA[JavadocNotFoundError]
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
              contents.name == "MavenCentral" &&
              contents.declartion == "com.jamesward.zio_mavencentral" &&
              contents.kind == "object"
          }
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
          doccontents.size == 465
        )
    },
    test("java - spring-ai-mcp/1.0.1") {
      defer:
        val doccontents = Extractor.javadocContents(gav("org.springframework.ai", "spring-ai-mcp", "1.0.1")).run
        assertTrue(
          doccontents.size == 25
        )
    },
    test("symbolContents - zio-mavencentral_3") {
      defer:
        val contents = Extractor.javadocSymbolContents(gav("com.jamesward", "zio-mavencentral_3", "0.0.21"), "com/jamesward/zio_mavencentral/MavenCentral$$GroupId$.html#unapply-fffffd22").run
        assertTrue(
          contents.contains("com.jamesward.zio_mavencentral.MavenCentral.GroupId")
        )
    }
  ).provide(
    Scope.default,
    Client.default,
    App.javadocCacheLayer,
    App.blockerLayer,
    App.tmpDirLayer,
  )
