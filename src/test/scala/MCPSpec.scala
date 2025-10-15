import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.spec.McpClientTransport
import io.modelcontextprotocol.spec.McpSchema.{CallToolRequest, ClientCapabilities, ListToolsResult, Tool}
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import zio.*
import zio.test.*
import zio.direct.*
import zio.http.*

import scala.jdk.CollectionConverters.*

object MCPSpec extends ZIOSpecDefault:

  opaque type ServerPort = Int

  object ServerPort:
    def apply(value: Int): ServerPort = value

  val serverDepLayers =
    ZLayer.make[Server & Client & Extractor.FetchBlocker & Extractor.LatestCache & Extractor.JavadocCache & Extractor.TmpDir](
      Server.defaultWith(_.onAnyOpenPort),
      Client.default,
      App.blockerLayer,
      App.latestCacheLayer,
      App.javadocCacheLayer,
      App.tmpDirLayer,
    )

  val serverLayer: ZLayer[Any, Throwable, ServerPort] =
    serverDepLayers >>> ZLayer.scoped:
      defer:
        ZIO.debug("starting server").run
        val portPromise = Promise.make[Nothing, Int].run
        val server = Server.install(App.appWithMiddleware).tap(portPromise.succeed).zipRight(ZIO.never).forkScoped.run
        val port = portPromise.await.run
        ZIO.debug(s"started server on port: $port").run
        ServerPort(port)

  def mcpSyncClient(transport: McpClientTransport) = McpClient.sync(transport)
    .requestTimeout(java.time.Duration.ofSeconds(10))
    .capabilities(ClientCapabilities.builder().build())
    .build()

  def listTools(listToolsResult: ListToolsResult): Seq[Tool] =
    listToolsResult.tools().asScala.toSeq

  val getLatestVersion = new CallToolRequest("get_latest_version", Map("groupId" -> "io.modelcontextprotocol.sdk", "artifactId" -> "mcp").asJava)

  def spec = suite("MCP")(
    suite("streamable")(
      test("listTools") {
        defer:
          val port = ZIO.service[ServerPort].run

          ZIO.debug(s"port = $port").run

          val mcpClientTransport = HttpClientStreamableHttpTransport.builder(s"http://localhost:$port/mcp").build()
          // todo: closable
          val mcpClient = mcpSyncClient(mcpClientTransport)

          val initializeResult = mcpClient.initialize()

//          mcpClient.ping()

          val listToolsResult = mcpClient.listTools()

          val toolCallResult = mcpClient.callTool(getLatestVersion)

          assertTrue(
            initializeResult.serverInfo().name() == "javadocs.dev",
            listTools(listToolsResult).exists(_.name() == "get_latest_version"),
            !toolCallResult.isError,
          )
      }
    )
  ).provideLayerShared(serverLayer) @@ TestAspect.withLiveClock
