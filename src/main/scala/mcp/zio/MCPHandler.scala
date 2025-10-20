package mcp.zio

import mcp.model.Common.{Info, Tool}
import mcp.model.Request.JSONRPCRequest
import mcp.model.Response.Content.TextContent
import mcp.model.Response.*
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


  case class ToolCallArgumentsError[TI](message: String, argumentsSchema: Schema[TI], argumentsJson: String)

  // todo: figure out how to have req.params.arguments be the tool input type
  def callTool[R, TI, TR, TE, TA](req: JSONRPCRequest.ToolsCall)(tool: Tool[TI, TR, TE, TA]): ZIO[TR, ToolCallArgumentsError[TI] | TE, TA] =
    // first turn the arguments into JSON because they don't have the type of the input schema
    val jsonArgs = req.params.arguments.toJson

    // then decode the JSON into the input type
    val maybeInput = ZIO.fromEither(zio.schema.codec.JsonCodec.jsonDecoder(tool.inputSchema).decodeJson(jsonArgs)).mapError:
      error =>
        ToolCallArgumentsError(error, tool.inputSchema, jsonArgs)

    // now call the handler with the input
    maybeInput.flatMap(tool.handler)

  def reqToJSONRPCReqeuest(request: Request): ZIO[Any, Throwable, JSONRPCRequest] =
    request.body.to[JSONRPCRequest].debug

  // todo: ServerInfo from Env?
  def mcpHandler[Env](tools: Tools[Env])(req: Request): Handler[Env, Nothing, Request, Response] =
    println(tools.metas)

    Handler.fromZIO:
      val e = reqToJSONRPCReqeuest(req).flatMap:
        case req: JSONRPCRequest.Initialize =>
          val impl = ServerImplementation(capabilities = ServerCapabilities(tools = Some(ToolsCapability(false))), serverInfo = Info("javadocs.dev", "0.0.1"))
          val mcpResponse = JSONRPCResponse.Initialize(id = req.id, result = impl)
          ZIO.succeed(Response(body = Body.from(mcpResponse)).contentType(MediaType.application.json))
        case req: JSONRPCRequest.Ping =>
          val mcpResponse = JSONRPCResponse.Ping(id = req.id)
          ZIO.succeed(Response(body = Body.from(mcpResponse)).contentType(MediaType.application.json))
        case req: JSONRPCRequest.NotificationsInitialized =>
          ZIO.succeed(Response.status(Status.Accepted))
        case req: JSONRPCRequest.ToolsList =>
          val mcpResponse = JSONRPCResponse.ToolList(id = req.id, result = ToolMetas(tools.metas.toList))
          ZIO.succeed(Response(body = Body.from(mcpResponse)).contentType(MediaType.application.json))
        case req: JSONRPCRequest.ToolsCall =>
          tools.tools.foreach(tool => println(tool.name))
          println(req.params.name)
          val maybeTool = tools.tools.find(_.name == req.params.name)
          println(maybeTool)

          ZIO.fromOption(maybeTool).flatMap:
            tool =>
              callTool(req)(tool).map: result =>
                println(result)
                val jsonEncoder = JsonCodec.jsonEncoder(tool.outputSchema)
                val resultJson = result.toJson(using jsonEncoder)
                println(resultJson)
                // todo: better handling of json into result

                val mcpResponse = JSONRPCResponse.ToolResult(id = req.id, result = Contents(List(TextContent(resultJson)), false))
                Response(body = Body.from(mcpResponse)).contentType(MediaType.application.json)

      // todo: MCP Errors
      e.catchAll:
        case t: Throwable =>
          ZIO.succeed(Response(Status.BadRequest, body = Body.fromString(s"Could not parse request: ${t.getMessage}")))
        //case toolCallArgumentsError: ToolCallArgumentsError[TI] =>
        //  ZIO.succeed(Response(Status.BadRequest, body = Body.fromString(s"Could not handle tool call arguments: ${toolCallArgumentsError.message}")))
        case _ =>
          ZIO.succeed(Response(Status.BadRequest, body = Body.fromString(s"Could not call tool")))

  def routes[Env](tools: Tools[Env]): Routes[Env, Response] =
    Routes(Method.POST / "mcp" -> Handler.fromFunctionHandler[Request](mcpHandler(tools)))
