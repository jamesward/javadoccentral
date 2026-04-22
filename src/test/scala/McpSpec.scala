import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema as JMcpSchema
import zio.*
import zio.http.*
import zio.redis.embedded.EmbeddedRedis
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

  private def javadocContentListTest(label: String, gid: String, aid: String, ver: String) =
    test(s"get_javadoc_content_list returns non-empty contents for $label"):
      for
        port   <- Server.install(App.appWithMiddleware)
        result <- withClient(port): client =>
          callTool(client, "get_javadoc_content_list", java.util.Map.of("groupId", gid, "artifactId", aid, "version", ver))
      yield
        val text = resultText(result)
        assertNotError(result) && assertTrue(text.contains("\"link\""), text.contains("\"fqn\""), text != "[]")

  private def javadocSymbolContentsTest(label: String, gid: String, aid: String, ver: String) =
    test(s"get_javadoc_symbol_contents works for $label"):
      for
        port   <- Server.install(App.appWithMiddleware)
        result <- withClient(port): client =>
          val contentList = resultText(callTool(client, "get_javadoc_content_list", java.util.Map.of("groupId", gid, "artifactId", aid, "version", ver)))
          val linkPattern = """"link"\s*:\s*"([^"]+)"""".r
          val link = linkPattern.findFirstMatchIn(contentList).get.group(1)
          callTool(client, "get_javadoc_symbol_contents", java.util.Map.of("groupId", gid, "artifactId", aid, "version", ver, "link", link))
      yield
        val text = resultText(result)
        assertNotError(result) && assertTrue(text.length > 10)

  private def sourceContentsTest(label: String, gid: String, aid: String, ver: String, ext: String) =
    test(s"list and get source contents for $label"):
      for
        port   <- Server.install(App.appWithMiddleware)
        result <- withClient(port): client =>
          val sourceList = resultText(callTool(client, "list_source_contents", java.util.Map.of("groupId", gid, "artifactId", aid, "version", ver)))
          assertTrue(sourceList.contains(ext) && sourceList != "[]") // assert inline to fail fast
          val filePattern = (""""([^"]+\.""" + ext.stripPrefix(".") + """)"""").r
          val link = filePattern.findFirstMatchIn(sourceList).get.group(1)
          callTool(client, "get_source_contents", java.util.Map.of("groupId", gid, "artifactId", aid, "version", ver, "link", link))
      yield
        val text = resultText(result)
        assertNotError(result) && assertTrue(text.length > 50)

  override def spec =
    suite("MCP Integration")(

      // --- tools/list validation ---
      test("tools/list returns all tools with valid schemas"):
        for
          port   <- Server.install(App.appWithMiddleware)
          tools  <- withClient(port): client =>
            client.listTools().tools()
        yield
          import scala.jdk.CollectionConverters.*
          val toolList = tools.asScala.toList
          val toolNames = toolList.map(_.name()).toSet
          // all tools present
          assertTrue(
            toolNames == Set("get_latest_version", "get_javadoc_index", "get_javadoc_content_list", "get_javadoc_symbol_contents",
              "list_source_contents", "get_source_contents", "search_artifacts", "symbol_to_artifact"),
          ) &&
          // outputSchema, if present, must have type "object"
          assertTrue(toolList.forall: tool =>
            val schema = tool.outputSchema()
            schema == null || schema.get("type").asInstanceOf[String] == "object"
          ) &&
          // inputSchema must have type "object"
          assertTrue(toolList.forall(_.inputSchema().`type`() == "object"))
      ,

      // --- get_latest_version ---
      test("get_latest_version returns a version"):
        for
          port   <- Server.install(App.appWithMiddleware)
          result <- withClient(port): client =>
            callTool(client, "get_latest_version", java.util.Map.of("groupId", javaGroupId, "artifactId", javaArtifactId))
        yield
          val text = resultText(result)
          assertNotError(result) && assertTrue(text.matches("\\d+\\.\\d+.*"))
      ,
      test("get_latest_version errors for nonexistent artifact"):
        for
          port   <- Server.install(App.appWithMiddleware)
          result <- withClient(port): client =>
            callTool(client, "get_latest_version", java.util.Map.of("groupId", "com.nonexistent.fake", "artifactId", "does-not-exist"))
        yield assertIsError(result)
      ,

      // --- get_javadoc_content_list ---
      javadocContentListTest("java (modular)", javaGroupId, javaArtifactId, javaVersion),
      javadocContentListTest("scala", scalaGroupId, scalaArtifactId, scalaVersion),
      javadocContentListTest("kotlin", kotlinGroupId, kotlinArtifactId, kotlinVersion),
      test("get_javadoc_content_list errors for nonexistent version"):
        for
          port   <- Server.install(App.appWithMiddleware)
          result <- withClient(port): client =>
            callTool(client, "get_javadoc_content_list", java.util.Map.of("groupId", javaGroupId, "artifactId", javaArtifactId, "version", "0.0.0-does-not-exist"))
        yield assertIsError(result)
      ,

      // --- get_javadoc_symbol_contents ---
      javadocSymbolContentsTest("java (modular)", javaGroupId, javaArtifactId, javaVersion),
      javadocSymbolContentsTest("scala", scalaGroupId, scalaArtifactId, scalaVersion),
      javadocSymbolContentsTest("kotlin", kotlinGroupId, kotlinArtifactId, kotlinVersion),
      test("get_javadoc_symbol_contents errors for nonexistent link"):
        for
          port   <- Server.install(App.appWithMiddleware)
          result <- withClient(port): client =>
            callTool(client, "get_javadoc_symbol_contents", java.util.Map.of("groupId", javaGroupId, "artifactId", javaArtifactId, "version", javaVersion, "link", "nonexistent/FakeClass.html"))
        yield assertIsError(result)
      ,

      // --- list_source_contents / get_source_contents ---
      sourceContentsTest("java", javaGroupId, javaArtifactId, javaVersion, ".java"),
      sourceContentsTest("scala", scalaGroupId, scalaArtifactId, scalaVersion, ".scala"),
      sourceContentsTest("kotlin", kotlinGroupId, kotlinArtifactId, kotlinVersion, ".kt"),
      test("list_source_contents errors for nonexistent artifact"):
        for
          port   <- Server.install(App.appWithMiddleware)
          result <- withClient(port): client =>
            callTool(client, "list_source_contents", java.util.Map.of("groupId", "com.nonexistent.fake", "artifactId", "does-not-exist", "version", "1.0.0"))
        yield assertIsError(result)
      ,
      test("get_source_contents errors for nonexistent file"):
        for
          port   <- Server.install(App.appWithMiddleware)
          result <- withClient(port): client =>
            callTool(client, "get_source_contents", java.util.Map.of("groupId", javaGroupId, "artifactId", javaArtifactId, "version", javaVersion, "link", "com/fake/NonExistent.java"))
        yield assertIsError(result)
      ,

      // --- symbol_to_artifact ---
      test("symbol_to_artifact returns matching artifacts with groupId and artifactId"):
        for
          port   <- Server.install(App.appWithMiddleware)
          result <- withClient(port): client =>
            callTool(client, "symbol_to_artifact", java.util.Map.of[String, Object]("query", "zio.cache.Cache"))
        yield
          val text = resultText(result)
          assertNotError(result) && assertTrue(text.contains("\"groupId\""), text.contains("\"artifactId\""), text.contains("zio-cache"))
      ,

    ).provide(
      Server.defaultWith(_.onAnyOpenPort),
      Client.default,
      Scope.default,
      App.blockerLayer,
      App.sourcesBlockerLayer,
      App.latestCacheLayer,
      App.javadocCacheLayer,
      App.sourcesCacheLayer,
      App.tmpDirLayer,
      EmbeddedRedis.layer,
      Redis.singleNode,
      ZLayer.succeed[CodecSupplier](SymbolSearch.ProtobufCodecSupplier),
      SymbolSearch.herokuInferenceLayer.orElse(MockInference.layer),
      BadActor.live,
      App.crawlerEvictionsLayer,
      App.crawlerGavLimiterLayer,
    ) @@ withLiveClock @@ timeout(3.minutes) @@ sequential
