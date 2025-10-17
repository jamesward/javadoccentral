import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.spec.McpClientTransport
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import mcp.model.{Common, Request, Response}
import zio.*
import zio.test.*
import zio.direct.*
import zio.http.*
import zio.schema.codec.JsonCodec

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
    .capabilities(McpSchema.ClientCapabilities.builder().build())
    .build()

  // non-local because of mutability in MCP client response
  def listTools(listToolsResult: McpSchema.ListToolsResult): Seq[McpSchema.Tool] =
    listToolsResult.tools().asScala.toSeq

  // non-local because of mutability in MCP client response
  val getLatestVersion = McpSchema.CallToolRequest("get_latest_version", Map("groupId" -> "io.modelcontextprotocol.sdk", "artifactId" -> "mcp").asJava)

  def spec = suite("MCP")(
    suite("spec")(
      test("JSONRPCRequest") {
        val codec = JsonCodec.schemaBasedBinaryCodec[Request.JSONRPCRequest]
        val initialize = Request.JSONRPCRequest.Initialize(id = 1, params = Request.ClientImplementation(capabilities = Request.ClientCapabilities(), clientInfo = Common.Info("asdf", "1.0.0")))
        val initializeDecoded = codec.encode(initialize).asString
        println(initializeDecoded)

        val initializeString = """{"jsonrpc":"2.0","method":"initialize","id":"1","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"MCP Client","version":"1.0.0"}}}"""
        val decoded = JsonCodec.schemaBasedBinaryCodec[Request.JSONRPCRequest].decode(Chunk.fromArray(initializeString.getBytes))

        assertTrue(
          initializeDecoded.contains(""""method":"initialize""""),
          decoded.isRight,
        )
      },
      test("JSONRPCResponse") {
        val codec = JsonCodec.schemaBasedBinaryCodec[Response.JSONRPCResponse]
        val initialize = Response.JSONRPCResponse.Initialize(id = 1, result = Response.ServerImplementation(capabilities = Response.ServerCapabilities(None), serverInfo = Common.Info("asdf", "1.0.0")))
        val initializeDecoded = codec.encode(initialize).asString
        assertTrue(
          initializeDecoded.contains("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","""),
        )
      }
    ),
    suite("streamable")(
      test("listTools") {
        defer:
          val port = ZIO.service[ServerPort].run

          ZIO.debug(s"port = $port").run

          val mcpClientTransport = HttpClientStreamableHttpTransport.builder(s"http://localhost:$port/mcp").build()
          // todo: closable
          val mcpClient = mcpSyncClient(mcpClientTransport)

          val initializeResult = mcpClient.initialize()

          mcpClient.ping()

          val listToolsResult = mcpClient.listTools()

          val toolCallResult = mcpClient.callTool(getLatestVersion)

          assertTrue(
            initializeResult.serverInfo().name() == "javadocs.dev",
            listTools(listToolsResult).exists(_.name() == "get_latest_version"),
            !toolCallResult.isError,
          )
      }
    ).provideLayerShared(serverLayer)
  ) @@ TestAspect.withLiveClock
