import com.jamesward.zio_mavencentral.MavenCentral
import com.jamesward.zio_mavencentral.MavenCentral.*
import com.jamesward.zio_mavencentral.JarCache
import zio.direct.*
import zio.http.Client
import zio.test.*
import zio.test.Assertion.failsWithA
import zio.{ZIO, durationInt}

object ExtractorSpec extends ZIOSpecDefault:

  /**
   * Independently re-derive the set of Java class FQNs a javadoc jar
   * *should* yield, straight from the jar's own bytes: for every
   * `<pkg-as-path>/<Class>.html` entry whose package appears in the
   * jar's `element-list` (modern) or `package-list` (old frames-based),
   * excluding `class-use/` and `package-*` pages. Handles module-prefixed
   * modular javadocs by stripping the leading module dir. This is the
   * ground truth we assert `javadocContents` fqns against — a real
   * cross-check, not a re-run of the parser.
   */
  private def expectedJavaFqns(handle: JarCache.JarHandle): ZIO[Any, Nothing, Set[String]] =
    defer:
      val listing = handle.readEntryString("element-list")
        .orElse(handle.readEntryString("package-list"))
        .orElseSucceed("")
        .run
      val packages     = parsePackageList(listing)
      val classEntries = handle.filterEntryNames { name =>
        name.endsWith(".html") && !name.contains("class-use")
      }.run
      classEntries.flatMap(name => javaFqnFor(name, packages))

  /** Parse an `element-list` / `package-list` body into its package set. */
  private def parsePackageList(listing: String): Set[String] =
    listing.linesIterator
      .filterNot(_.startsWith("module:"))
      .filter(_.nonEmpty)
      .toSet

  /** The fully-qualified name a `<pkg>/<Class>.html` entry maps to (if any),
   *  handling module-prefixed modular javadocs by stripping the leading dir. */
  private def javaFqnFor(name: String, packages: Set[String]): Option[String] =
    val segs = name.stripSuffix(".html").split('/').toVector
    val cls  = segs.last
    if cls.startsWith("package-") then None
    else
      val dirSegs = segs.init
      val dir     = dirSegs.mkString(".")
      if packages.contains(dir) then Some(s"$dir.$cls")
      else
        val stripped = dirSegs.drop(1).mkString(".")
        Option.when(dirSegs.nonEmpty && packages.contains(stripped))(s"$stripped.$cls")

  /** Last path segment of a jar entry (no `Array` inside defer). */
  private def lastSegment(path: String): String =
    path.substring(path.lastIndexOf('/') + 1)

  /**
   * Reusable Java-format assertion: the produced fqns must exactly equal
   * the jar-derived ground truth, all be fully qualified, contain the
   * given spot-check FQNs, and carry none of the `class-use` / `package-*`
   * noise that the `bruteForce` fallback would leak.
   */
  private def javaFqnsMatchJar(label: String, g: MavenCentral.GroupArtifactVersion, sampleFqns: String*) =
    test(s"java fqns verified against jar contents - $label"):
      defer:
        val handle   = Extractor.javadocJar(g).run
        val expected = expectedJavaFqns(handle).run
        val contents = Extractor.javadocContents(g).run
        val fqns     = contents.map(_.fqn)
        assertTrue(expected.nonEmpty) &&
          assertTrue(fqns == expected) &&
          assertTrue(fqns.forall(_.contains('.'))) &&
          assertTrue(sampleFqns.forall(fqns.contains)) &&
          assertTrue(!contents.exists(c => c.link.contains("class-use"))) &&
          assertTrue(!contents.exists(c => lastSegment(c.link).startsWith("package-")))

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
    test("javadoc jar does not exist") {
      assertZIO(Extractor.javadocJar(gav("com.jamesward", "zio-mavencentral_3", "0.0.0")).exit)(
        failsWithA[NotFoundError]
      )
    },
    test("sources do not exist") {
      assertZIO(Extractor.sourceContents(gav("com.jamesward", "zio-mavencentral_3", "0.0.0")).exit)(
        failsWithA[NotFoundError]
      )
    },
    test("javadoc file not found") {
      val groupArtifactVersion = gav("com.jamesward", "zio-mavencentral_3", "0.12.0")
      assertZIO(Extractor.javadocEntryBytes(groupArtifactVersion, "asdf").exit)(
        failsWithA[Extractor.JavadocFileNotFound]
      )
    },
    test("scaladoc - zio-mavencentral_3") {
      defer:
        val scaladoc = Extractor.javadocContents(gav("com.jamesward", "zio-mavencentral_3", "0.12.0")).run
        assertTrue(
          scaladoc.size == 128,
          scaladoc.exists { contents =>
            contents.link == "com/jamesward/zio_mavencentral/MavenCentral$.html#" &&
              contents.fqn == "com.jamesward.zio_mavencentral.MavenCentral" &&
              contents.kind == "object"
          },
          scaladoc.exists(_.`type` == "searchArtifacts(groupId: GroupId): ZIO[MavenCentralRepo, GroupIdNotFoundError | TemporaryServerError | Throwable, WithCacheInfo[Seq[ArtifactId]]]")
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
    // --- Java FQN regression coverage (checked against actual jar contents) ---
    //
    // jackson-databind 2.22.1 ships OLD frames-based javadoc: it has a
    // `package-list` (not `element-list`) plus `*-frame.html` pages. Before
    // the fix, `javadocJavaFormat` only read `element-list`, so this fell
    // through to `bruteForce` and every fqn was a bare simple name
    // ("ObjectMapper") with `class-use`/`package-*` noise mixed in. This test
    // pins the real fully-qualified names, re-derived from the jar itself.
    test("java (old package-list/frames) - jackson-databind/2.22.1 uses package-list, not element-list") {
      val g = gav("com.fasterxml.jackson.core", "jackson-databind", "2.22.1")
      defer:
        val handle = Extractor.javadocJar(g).run
        assertTrue(
          handle.hasEntry("package-list").run,
          !handle.hasEntry("element-list").run,
        )
    },
    javaFqnsMatchJar(
      "jackson-databind/2.22.1 (old package-list)",
      gav("com.fasterxml.jackson.core", "jackson-databind", "2.22.1"),
      "com.fasterxml.jackson.databind.ObjectMapper",
      "com.fasterxml.jackson.databind.JsonNode",
      "com.fasterxml.jackson.databind.node.ObjectNode",
      "com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping",
    ),
    test("java - jackson-databind/2.22.1 does NOT regress to bruteForce simple names") {
      val g = gav("com.fasterxml.jackson.core", "jackson-databind", "2.22.1")
      defer:
        val contents = Extractor.javadocContents(g).run
        val fqns     = contents.map(_.fqn)
        assertTrue(
          // the bruteForce fallback produced these bare simple names + noise
          !fqns.contains("ObjectMapper"),
          !fqns.contains("JsonNode"),
          !fqns.contains("package-summary"),
          !fqns.contains("allclasses-frame"),
          !fqns.contains("index-all"),
          // and for the Java format every fqn is exactly its jar path, dotted
          contents.forall(c => c.fqn == c.link.stripSuffix(".html").replace('/', '.')),
        )
    },
    javaFqnsMatchJar(
      "spring-ai-mcp/1.0.1 (modern element-list)",
      gav("org.springframework.ai", "spring-ai-mcp", "1.0.1"),
      "org.springframework.ai.mcp.SyncMcpToolCallback",
    ),
    javaFqnsMatchJar(
      "jsoup/1.22.2 (modern element-list)",
      gav("org.jsoup", "jsoup", "1.22.2"),
      "org.jsoup.Jsoup",
      "org.jsoup.nodes.Document",
    ),
    // --- Cross-language link-resolution: every non-external link must point
    //     at a real entry in the jar (scaladoc/kotlindoc links carry anchors). ---
    test("scala - zio-mavencentral_3 links resolve to real jar entries") {
      val g = gav("com.jamesward", "zio-mavencentral_3", "0.12.0")
      defer:
        val handle   = Extractor.javadocJar(g).run
        val names    = handle.entryNames.run
        val contents = Extractor.javadocContents(g).run
        val unresolved = contents
          .filterNot(_.external)
          .map(_.link.takeWhile(_ != '#'))
          .filterNot(names.contains)
        assertTrue(
          contents.nonEmpty,
          unresolved.isEmpty,
          contents.exists(_.fqn == "com.jamesward.zio_mavencentral.MavenCentral"),
        )
    },
    test("kotlin - ktor-io-jvm/3.2.3 fqns are qualified and type carries the declaration") {
      val g = gav("io.ktor", "ktor-io-jvm", "3.2.3")
      defer:
        val contents = Extractor.javadocContents(g).run
        val fqns     = contents.map(_.fqn)
        assertTrue(
          contents.nonEmpty,
          fqns.contains("io.ktor.utils.io.pool.SingleInstancePool"),
          // dokka gives us the qualified name in fqn and the signature in type
          contents.exists(c =>
            c.fqn == "io.ktor.utils.io.pool.SingleInstancePool" &&
              c.`type` == "abstract class SingleInstancePool<T : Any> : ObjectPool<T>"
          ),
        )
    },
    test("symbolContents - zio-mavencentral_3") {
      defer:
        val contents = Extractor.javadocSymbolContents(gav("com.jamesward", "zio-mavencentral_3", "0.12.0"), "com/jamesward/zio_mavencentral/MavenCentral$$GroupId$.html#unapply-fffffd22").run
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
        val contents = Extractor.sourceFileContents(gav("com.jamesward", "zio-mavencentral_3", "0.12.0"), "com/jamesward/zio_mavencentral/MavenCentral.scala").run
        assertTrue(
          contents.contains("object MavenCentral:")
        )
    },
    test("listSourceContents - zio-mavencentral_3") {
      defer:
        val contents = Extractor.sourceContents(gav("com.jamesward", "zio-mavencentral_3", "0.12.0")).run
        assertTrue(
          // jar entries: META-INF/MANIFEST.MF + the source file (directory entries
          // are filtered by sourceContents).
          contents.size >= 2,
          contents.contains("com/jamesward/zio_mavencentral/MavenCentral.scala")
        )
    },
    test("concurrent javadocJar calls for the same GAV deduplicate") {
      // The library's `JarCache` provides single-flight via a `Promise`-keyed
      // map; concurrent `get`s for the same GAV all observe the same handle.
      val g = gav("com.jamesward", "zio-mavencentral_3", "0.12.0")
      defer:
        val handles = ZIO.foreachPar(1 to 10)(_ => Extractor.javadocJar(g)).run
        assertTrue(
          handles.size == 10,
          handles.forall(_ eq handles.head),
        )
    } @@ TestAspect.timeout(2.minutes) @@ TestAspect.withLiveClock,
    test("parallel downloads: 20 artifacts concurrently") {
      val gavs = Seq(
        gav("com.jamesward", "zio-mavencentral_3", "0.12.0"),
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
        val handles = ZIO.foreachPar(gavs)(Extractor.javadocJar).run
        assertTrue(handles.size == 20, handles.forall(_.sizeBytes > 0L))
    } @@ TestAspect.timeout(2.minutes) @@ TestAspect.withLiveClock
  ).provide(
    Client.default,
    MavenCentral.MavenCentralRepo.live,
    App.javadocCacheLayer,
    App.sourcesCacheLayer,
  )
