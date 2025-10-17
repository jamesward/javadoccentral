package mcp.model

import mcp.schema.JsonSchema.JsonSchemaType
import mcp.model.Common.*
import zio.json.ast.Json
import zio.schema.{DeriveSchema, Schema}
import zio.schema.annotation.{caseName, discriminatorName, noDiscriminator}
import zio.schema.codec.{BinaryCodec, JsonCodec}

// todo: `derives Schema` has issues with JSONRPCResponse.ToolResult so we do things more manually
object Response:

  given Schema[ToolsCapability] = DeriveSchema.gen[ToolsCapability]
  given Schema[ServerCapabilities] = DeriveSchema.gen[ServerCapabilities]
  given Schema[ServerImplementation] = DeriveSchema.gen[ServerImplementation]
  given Schema[ToolMeta] = DeriveSchema.gen[ToolMeta]
  given Schema[ToolMetas] = DeriveSchema.gen[ToolMetas]
  given Schema[Content] = DeriveSchema.gen[Content]
  given Schema[Contents] = DeriveSchema.gen[Contents]
  given Schema[JSONRPCResponse] = DeriveSchema.gen[JSONRPCResponse]
  given Schema[JSONRPCResponse.ToolResult] = DeriveSchema.gen[JSONRPCResponse.ToolResult]

  given zio.json.JsonCodec[ToolsCapability] = JsonCodec.jsonCodec(summon[Schema[ToolsCapability]])
  given zio.json.JsonCodec[ServerCapabilities] = JsonCodec.jsonCodec(summon[Schema[ServerCapabilities]])
  given zio.json.JsonCodec[ServerImplementation] = JsonCodec.jsonCodec(summon[Schema[ServerImplementation]])
  given zio.json.JsonCodec[ToolMeta] = JsonCodec.jsonCodec(summon[Schema[ToolMeta]])
  given zio.json.JsonCodec[ToolMetas] = JsonCodec.jsonCodec(summon[Schema[ToolMetas]])
  given zio.json.JsonCodec[Content] = JsonCodec.jsonCodec(summon[Schema[Content]])
  given zio.json.JsonCodec[Contents] = JsonCodec.jsonCodec(summon[Schema[Contents]])
  given zio.json.JsonCodec[JSONRPCResponse] = JsonCodec.jsonCodec(summon[Schema[JSONRPCResponse]])
  given zio.json.JsonCodec[JSONRPCResponse.ToolResult] = JsonCodec.jsonCodec(summon[Schema[JSONRPCResponse.ToolResult]])

  given BinaryCodec[ToolsCapability] = JsonCodec.schemaBasedBinaryCodec[ToolsCapability]
  given BinaryCodec[ServerCapabilities] = JsonCodec.schemaBasedBinaryCodec[ServerCapabilities]
  given BinaryCodec[ServerImplementation] = JsonCodec.schemaBasedBinaryCodec[ServerImplementation]
  given BinaryCodec[ToolMeta] = JsonCodec.schemaBasedBinaryCodec[ToolMeta]
  given BinaryCodec[ToolMetas] = JsonCodec.schemaBasedBinaryCodec[ToolMetas]
  given BinaryCodec[Content] = JsonCodec.schemaBasedBinaryCodec[Content]
  given BinaryCodec[Contents] = JsonCodec.schemaBasedBinaryCodec[Contents]
  given BinaryCodec[JSONRPCResponse] = JsonCodec.schemaBasedBinaryCodec[JSONRPCResponse]
  given BinaryCodec[JSONRPCResponse.ToolResult] = JsonCodec.schemaBasedBinaryCodec[JSONRPCResponse.ToolResult]


  case class ToolsCapability(
                               listChanged: Boolean
                             )

  case class ServerCapabilities(
                               tools: Option[ToolsCapability]
                               )

  case class ServerImplementation(
                                 protocolVersion: String = PROTOCOL_VERSION,
                                   capabilities: ServerCapabilities,
                                   serverInfo: Info,
                                 )

  case class ToolMeta(
                       name: String,
                       title: String,
                       description: String,
                       inputSchema: JsonSchemaType,
                     )

  case class ToolMetas(tools: List[ToolMeta])

  @discriminatorName("type")
  enum Content:

    @caseName("text")
    case TextContent(text: String) extends Content

  case class Contents(content: List[Content], isError: Boolean)


  @noDiscriminator
  enum JSONRPCResponse(
                        jsonrpc: String,
                        id: RequestId,
                      ):

    case Initialize(
                     jsonrpc: String = JSONRPC_VERSION,
                     id: RequestId,
                     result: ServerImplementation,
                   ) extends JSONRPCResponse(jsonrpc, id)
    case Error(
                jsonrpc: String = JSONRPC_VERSION,
                id: RequestId,
                error: JSONRPCError,
              ) extends JSONRPCResponse(jsonrpc, id)

    case Ping(
               jsonrpc: String = JSONRPC_VERSION,
               id: RequestId,
               result: Json.Null = Json.Null,
             ) extends JSONRPCResponse(jsonrpc, id)

    case ToolList(
                   jsonrpc: String = JSONRPC_VERSION,
                   id: RequestId,
                   result: ToolMetas,
                 ) extends JSONRPCResponse(jsonrpc, id)

    case ToolResult(
                     jsonrpc: String = JSONRPC_VERSION,
                     id: RequestId,
                     result: Contents,
                   ) extends JSONRPCResponse(jsonrpc, id)

