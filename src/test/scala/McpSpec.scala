import com.jamesward.zio_http_guard.{BadActor, CrawlerLimiter}
import com.jamesward.zio_mavencentral.MavenCentral
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema as JMcpSchema
import zio.*
import zio.http.*
import zio.redis.{CodecSupplier, Redis}
import zio.test.*
import zio.test.TestAspect.*

import java.time.Duration as JDuration

object McpSpec extends ZIOSpecDefault:

  private def withClient[A](port: Int)(f: io.modelcontextprotocol.client.McpSyncClient => A): Task[A] =
    ZIO.attemptBlocking:
      val transport = HttpClientStreamableHttpTransport.builder(s"http://localhost:$port")
        .endpoint("/mcp")
        .build()
      val client = McpClient.sync(transport)
        .requestTimeout(JDuration.ofSeconds(30))
        .clientInfo(JMcpSchema.Implementation("test-client", "1.0.0"))
        .build()
      try
        client.initialize()
        f(client)
      finally
        client.close()

  private def callTool(client: io.modelcontextprotocol.client.McpSyncClient, name: String, args: java.util.Map[String, Object]): JMcpSchema.CallToolResult =
    client.callTool(JMcpSchema.CallToolRequest(name, args, null))

  private def resultText(result: JMcpSchema.CallToolResult): String =
    result.content().get(0).asInstanceOf[JMcpSchema.TextContent].text()

  private def assertNotError(result: JMcpSchema.CallToolResult) =
    assertTrue(result.isError == null || !result.isError)

  private def assertIsError(result: JMcpSchema.CallToolResult) =
    assertTrue(result.isError != null && result.isError)

  // Java artifact (modular javadoc)
  private val javaGroupId = "org.webjars"
  private val javaArtifactId = "webjars-locator-lite"
  private val javaVersion = "1.1.3"

  // Scala artifact
  private val scalaGroupId = "dev.zio"
  private val scalaArtifactId = "zio_3"
  private val scalaVersion = "2.1.9"

  // Kotlin artifact
  private val kotlinGroupId = "io.ktor"
  private val kotlinArtifactId = "ktor-io-jvm"
  private val kotlinVersion = "3.2.3"

  private def listJavadocSymbolsTest(label: String, gid: String, aid: String, ver: String) =
    test(s"list_javadoc_symbols returns non-empty contents for $label"):
      for
        port   <- Server.install(Web.appWithMiddleware)
        result <- withClient(port): client =>
          callTool(client, "list_javadoc_symbols", java.util.Map.of("groupId", gid, "artifactId", aid, "version", ver))
      yield
        val text = resultText(result)
        assertNotError(result) && assertTrue(text.contains("\"link\""), text.contains("\"fqn\""), text != "[]")

  private def javadocSymbolTest(label: String, gid: String, aid: String, ver: String) =
    test(s"get_javadoc_symbol works for $label"):
      for
        port   <- Server.install(Web.appWithMiddleware)
        result <- withClient(port): client =>
          val contentList = resultText(callTool(client, "list_javadoc_symbols", java.util.Map.of("groupId", gid, "artifactId", aid, "version", ver)))
          val linkPattern = """"link"\s*:\s*"([^"]+)"""".r
          val link = linkPattern.findFirstMatchIn(contentList).get.group(1)
          callTool(client, "get_javadoc_symbol", java.util.Map.of("groupId", gid, "artifactId", aid, "version", ver, "link", link))
      yield
        val text = resultText(result)
        assertNotError(result) && assertTrue(text.length > 10)

  private def sourceFilesTest(label: String, gid: String, aid: String, ver: String, ext: String) =
    test(s"list and get source contents for $label"):
      for
        port   <- Server.install(Web.appWithMiddleware)
        result <- withClient(port): client =>
          val sourceList = resultText(callTool(client, "list_source_files", java.util.Map.of("groupId", gid, "artifactId", aid, "version", ver)))
          assertTrue(sourceList.contains(ext) && sourceList != "[]") // assert inline to fail fast
          val filePattern = (""""([^"]+\.""" + ext.stripPrefix(".") + """)"""").r
          val link = filePattern.findFirstMatchIn(sourceList).get.group(1)
          callTool(client, "get_source_file", java.util.Map.of("groupId", gid, "artifactId", aid, "version", ver, "link", link))
      yield
        val text = resultText(result)
        assertNotError(result) && assertTrue(text.length > 50)

  override def spec =
    suite("MCP Integration")(

      // --- tools/list validation ---
      test("tools/list returns all tools with valid schemas"):
        for
          port   <- Server.install(Web.appWithMiddleware)
          tools  <- withClient(port): client =>
            client.listTools().tools()
        yield
          import scala.jdk.CollectionConverters.*
          val toolList = tools.asScala.toList
          val toolNames = toolList.map(_.name()).toSet
          // all tools present
          assertTrue(
            toolNames == Set("get_latest_version", "get_javadoc_index", "list_javadoc_symbols", "get_javadoc_symbol",
              "list_source_files", "get_source_file", "search_artifacts", "symbol_to_artifact"),
          ) &&
          // outputSchema, if present, must have type "object"
          assertTrue(toolList.forall: tool =>
            val schema = tool.outputSchema()
            schema == null || schema.get("type").asInstanceOf[String] == "object"
          ) &&
          // tools that return structured data must advertise an object outputSchema
          assertTrue({
            val structured = Set("get_latest_version", "list_javadoc_symbols", "list_source_files", "search_artifacts", "symbol_to_artifact")
            toolList.filter(t => structured.contains(t.name())).forall: t =>
              t.outputSchema() != null && t.outputSchema().get("type").asInstanceOf[String] == "object"
          }) &&
          // inputSchema must have type "object"
          assertTrue(toolList.forall(_.inputSchema().get("type").asInstanceOf[String] == "object")) &&
          // data-class field descriptions flow through into the output schemas
          assertTrue(
            toolList.find(_.name() == "list_javadoc_symbols").exists(_.outputSchema().toString.contains("Fully-qualified name of the documented symbol")),
            toolList.find(_.name() == "search_artifacts").exists(_.outputSchema().toString.contains("The Maven groupId")),
          )
      ,

      // --- get_latest_version ---
      test("get_latest_version returns a version"):
        for
          port   <- Server.install(Web.appWithMiddleware)
          result <- withClient(port): client =>
            callTool(client, "get_latest_version", java.util.Map.of("groupId", javaGroupId, "artifactId", javaArtifactId))
        yield
          val text = resultText(result)
          // Output is now structured: `{"result":"<version>"}` (McpOutput wraps
          // a non-object value under `result`), so match a version anywhere.
          assertNotError(result) && assertTrue(text.matches(".*\\d+\\.\\d+.*"), result.structuredContent() != null)
      ,
      test("get_latest_version errors for nonexistent artifact"):
        for
          port   <- Server.install(Web.appWithMiddleware)
          result <- withClient(port): client =>
            callTool(client, "get_latest_version", java.util.Map.of("groupId", "com.nonexistent.fake", "artifactId", "does-not-exist"))
        yield assertIsError(result)
      ,

      // --- list_javadoc_symbols ---
      listJavadocSymbolsTest("java (modular)", javaGroupId, javaArtifactId, javaVersion),
      listJavadocSymbolsTest("scala", scalaGroupId, scalaArtifactId, scalaVersion),
      listJavadocSymbolsTest("kotlin", kotlinGroupId, kotlinArtifactId, kotlinVersion),
      test("list_javadoc_symbols errors for nonexistent version"):
        for
          port   <- Server.install(Web.appWithMiddleware)
          result <- withClient(port): client =>
            callTool(client, "list_javadoc_symbols", java.util.Map.of("groupId", javaGroupId, "artifactId", javaArtifactId, "version", "0.0.0-does-not-exist"))
        yield assertIsError(result)
      ,

      // --- get_javadoc_symbol ---
      javadocSymbolTest("java (modular)", javaGroupId, javaArtifactId, javaVersion),
      javadocSymbolTest("scala", scalaGroupId, scalaArtifactId, scalaVersion),
      javadocSymbolTest("kotlin", kotlinGroupId, kotlinArtifactId, kotlinVersion),
      test("get_javadoc_symbol errors for nonexistent link"):
        for
          port   <- Server.install(Web.appWithMiddleware)
          result <- withClient(port): client =>
            callTool(client, "get_javadoc_symbol", java.util.Map.of("groupId", javaGroupId, "artifactId", javaArtifactId, "version", javaVersion, "link", "nonexistent/FakeClass.html"))
        yield assertIsError(result)
      ,

      // --- list_source_files / get_source_file ---
      sourceFilesTest("java", javaGroupId, javaArtifactId, javaVersion, ".java"),
      sourceFilesTest("scala", scalaGroupId, scalaArtifactId, scalaVersion, ".scala"),
      sourceFilesTest("kotlin", kotlinGroupId, kotlinArtifactId, kotlinVersion, ".kt"),
      test("list_source_files errors for nonexistent artifact"):
        for
          port   <- Server.install(Web.appWithMiddleware)
          result <- withClient(port): client =>
            callTool(client, "list_source_files", java.util.Map.of("groupId", "com.nonexistent.fake", "artifactId", "does-not-exist", "version", "1.0.0"))
        yield assertIsError(result)
      ,
      test("get_source_file errors for nonexistent file"):
        for
          port   <- Server.install(Web.appWithMiddleware)
          result <- withClient(port): client =>
            callTool(client, "get_source_file", java.util.Map.of("groupId", javaGroupId, "artifactId", javaArtifactId, "version", javaVersion, "link", "com/fake/NonExistent.java"))
        yield assertIsError(result)
      ,

      // --- symbol_to_artifact ---
      test("symbol_to_artifact returns matching artifacts with groupId and artifactId"):
        for
          port   <- Server.install(Web.appWithMiddleware)
          result <- withClient(port): client =>
            callTool(client, "symbol_to_artifact", java.util.Map.of[String, Object]("query", "zio.cache.Cache"))
        yield
          val text = resultText(result)
          assertNotError(result) && assertTrue(text.contains("\"groupId\""), text.contains("\"artifactId\""), text.contains("zio-cache"), result.structuredContent() != null)
      ,

    ).provide(
      Server.defaultWith(_.onAnyOpenPort),
      Client.default,
      MavenCentral.MavenCentralRepo.live,
      App.latestCacheLayer,
      App.javadocCacheLayer,
      App.sourcesCacheLayer,
      ValkeyContainer.layer,
      Redis.singleNode,
      ZLayer.succeed[CodecSupplier](SymbolSearch.ProtobufCodecSupplier),
      SymbolSearch.herokuInferenceLayer.orElse(MockInference.layer),
      BadActor.live,
      CrawlerLimiter.layer[MavenCentral.GroupArtifactVersion],
      App.symbolSearchGuardLayer,
    ) @@ withLiveClock @@ timeout(3.minutes) @@ sequential
