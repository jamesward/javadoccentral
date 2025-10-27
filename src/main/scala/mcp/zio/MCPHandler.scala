package mcp.zio

import mcp.model.Common
import mcp.model.Common.{Info, Tool}
import mcp.model.Request.JSONRPCRequest
import mcp.model.Response.Content.TextContent
import mcp.model.Response.*
import mcp.model.Response.JSONRPCResponse.ToolResult
import zio.{Chunk, ZIO}
import zio.http.*
import zio.schema.codec.JsonCodec
import zio.json.EncoderOps
import zio.schema.Schema

object MCPHandler:

  case class Tools[-Env](tools: Chunk[Tool[?, Env, ?, ?]]):
    val metas = tools.map:
      tool => ToolMeta(name = tool.name, title = tool.description, description = tool.description, inputSchema = tool.inputSchemaAsJsonSchema)

  object Tools:
    def apply[Env](tool: Tool[?, Env, ?, ?], tools: Tool[?, Env, ?, ?]*): Tools[Env] =
      Tools(Chunk.fromIterable(tool +: tools))

  case class ToolNotFound(toolName: String)
  case class ToolCallArgumentsError[TI](message: String, argumentsSchema: Schema[TI], argumentsJson: String)
  case class ToolCallError[Err](err: Err)

//  // todo: figure out how to have req.params.arguments be the tool input type
//  def callTool[R, TI, TR, TE, TA](req: JSONRPCRequest.ToolsCall)(tool: Tool[TI, TR, TE, TA]): ZIO[TR, ToolCallArgumentsError[TI] | TE, TA] =
//    // first turn the arguments into JSON because they don't have the type of the input schema
//

  def callTool[Env](req: JSONRPCRequest.ToolsCall, tools: Tools[Env]): ZIO[Env, ToolNotFound | ToolCallArgumentsError[?] | ToolCallError[?], JSONRPCResponse.ToolResult] =
    for
      _ <- ZIO.logDebug(s"Trying to find tool ${req.params.name}")
      tool <- ZIO.fromOption(tools.tools.find(_.name == req.params.name)).orElseFail(ToolNotFound(req.params.name))
      jsonArgs = req.params.arguments.toJson
      _ <- ZIO.logDebug(s"JSON arguments for tool ${tool.name}:\n$jsonArgs")
      // decode the JSON into the input type
      input <- ZIO.fromEither(zio.schema.codec.JsonCodec.jsonDecoder(tool.inputSchema).decodeJson(jsonArgs)).mapError:
        error =>
          ToolCallArgumentsError(error, tool.inputSchema, jsonArgs)
      _ <- ZIO.logDebug(s"Calling tool ${tool.name} with arguments:\n${input.toJson(using zio.schema.codec.JsonCodec.jsonEncoder(tool.inputSchema))}")
      // call the handler with the input
      result <- tool.handler(input).mapError:
        error =>
          ToolCallError(error)
      _ <- ZIO.logDebug(s"Results from tool call ${tool.name}:\n$result")
    yield
      val jsonEncoder = JsonCodec.jsonEncoder(tool.outputSchema)
      val resultJson = result.toJson(using jsonEncoder)
      JSONRPCResponse.ToolResult(id = req.id, result = Contents(List(TextContent(resultJson)), false))

  def handleJSONRPCRequest[Env](jsonRpcRequest: JSONRPCRequest, tools: Tools[Env]): ZIO[Env, Nothing, JSONRPCResponse] =
    val e = jsonRpcRequest match
      case req: JSONRPCRequest.Initialize =>
        val impl = ServerImplementation(capabilities = ServerCapabilities(tools = Some(ToolsCapability(false))), serverInfo = Info("javadocs.dev", "0.0.1"))
        ZIO.succeed(JSONRPCResponse.Initialize(id = req.id, result = impl))

      case req: JSONRPCRequest.Ping =>
        ZIO.succeed(JSONRPCResponse.Ping(id = req.id))

      case req: JSONRPCRequest.NotificationsInitialized =>
        ZIO.succeed(JSONRPCResponse.Empty(id = req.id))

      case req: JSONRPCRequest.ToolsList =>
        ZIO.succeed(JSONRPCResponse.ToolList(id = req.id, result = ToolMetas(tools.metas.toList)))

      case req: JSONRPCRequest.ToolsCall =>
        callTool(req, tools)

    e.catchAll:
      case ToolNotFound(toolName) =>
        ZIO.succeed(JSONRPCResponse.Error(id = jsonRpcRequest.id, error = Common.JSONRPCError(-32602, s"Tool $toolName not found", None)))
      case ToolCallArgumentsError(message, argumentsSchema, argumentsJson) =>
        ???
      case ToolCallError(err) =>
        ZIO.succeed(JSONRPCResponse.ToolResult(id = jsonRpcRequest.id, result = Contents(List(TextContent(err.toString)), isError = true)))

  // todo: ServerInfo from Env?
  def mcpHandler[Env](tools: Tools[Env])(req: Request): Handler[Env, Nothing, Request, Response] =
    Handler.fromZIO:
      val e = for
        _ <- ZIO.logDebug(s"MCP tools: ${tools.metas}")
        _ <- ZIO.logDebug(s"MCP request: $req")
        reqAsString <- req.body.asString
        _ <- ZIO.logDebug(s"MCP req: $reqAsString")
        jsonRpcRequest <- req.body.to[JSONRPCRequest]
        _ <- ZIO.logDebug(s"MCP reqAsJSONRPCReqest: $jsonRpcRequest")
        jsonRpcResponse <- handleJSONRPCRequest(jsonRpcRequest, tools)
        _ <- ZIO.logDebug(s"MCP response: $jsonRpcResponse")
      yield
        jsonRpcResponse.toResponse

      e.catchAll:
        t =>
          ZIO.succeed(Response(Status.BadRequest, body = Body.fromString(s"Could not parse request: ${t.getMessage}")))
        //case toolCallArgumentsError: ToolCallArgumentsError[TI] =>
        //  ZIO.succeed(Response(Status.BadRequest, body = Body.fromString(s"Could not handle tool call arguments: ${toolCallArgumentsError.message}")))
//        case _ =>
//          ZIO.succeed(Response(Status.BadRequest, body = Body.fromString(s"Could not call tool")))


  def routes[Env](tools: Tools[Env]): Routes[Env, Response] =
    Routes(
      Method.POST / "mcp" -> Handler.fromFunctionHandler[Request](mcpHandler(tools)),
      Method.GET / "mcp" -> Handler.methodNotAllowed, // let MCP clients know that SSE isn't supported
    )
