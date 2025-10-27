package mcp.model

import mcp.model.RequestId
import mcp.model.Common.*
import zio.schema.codec.BinaryCodec
import zio.schema.annotation.{caseName, discriminatorName}
import zio.schema.{DeriveSchema, Schema}
import zio.schema.codec.JsonCodec

// todo: `derives Schema` has issues with JSONRPCRequest.ToolsCall so we do it manually for now
object Request:

  given Schema[ClientCapabilities] = DeriveSchema.gen[ClientCapabilities]
  given Schema[ClientImplementation] = DeriveSchema.gen[ClientImplementation]
  given Schema[ToolParams] = DeriveSchema.gen[ToolParams]
  given Schema[JSONRPCRequest] = DeriveSchema.gen[JSONRPCRequest]
  given Schema[JSONRPCRequest.ToolsCall] = DeriveSchema.gen[JSONRPCRequest.ToolsCall]

  given zio.json.JsonCodec[ClientCapabilities] = JsonCodec.jsonCodec(summon[Schema[ClientCapabilities]])
  given zio.json.JsonCodec[ClientImplementation] = JsonCodec.jsonCodec(summon[Schema[ClientImplementation]])
  given zio.json.JsonCodec[ToolParams] = JsonCodec.jsonCodec(summon[Schema[ToolParams]])
  given zio.json.JsonCodec[JSONRPCRequest] = JsonCodec.jsonCodec(summon[Schema[JSONRPCRequest]])
  given zio.json.JsonCodec[JSONRPCRequest.ToolsCall] = JsonCodec.jsonCodec(summon[Schema[JSONRPCRequest.ToolsCall]])

  given BinaryCodec[ClientCapabilities] = JsonCodec.schemaBasedBinaryCodec[ClientCapabilities]
  given BinaryCodec[ClientImplementation] = JsonCodec.schemaBasedBinaryCodec[ClientImplementation]
  given BinaryCodec[ToolParams] = JsonCodec.schemaBasedBinaryCodec[ToolParams]
  given BinaryCodec[JSONRPCRequest] = JsonCodec.schemaBasedBinaryCodec[JSONRPCRequest]
  given BinaryCodec[JSONRPCRequest.ToolsCall] = JsonCodec.schemaBasedBinaryCodec[JSONRPCRequest.ToolsCall]

  case class ClientCapabilities()

  case class ClientImplementation(
                                   protocolVersion: String = PROTOCOL_VERSION,
                                   capabilities: ClientCapabilities,
                                   clientInfo: Info,
                                 )

  case class ToolParams(
                         name: String,
                         arguments: Map[String, String] // todo: any way to param based on the Tool inputSchema?
                       )

  @discriminatorName("method")
  enum JSONRPCRequest(
                       val jsonrpc: String = JSONRPC_VERSION,
                       val id: RequestId,
                     ):
    @caseName("initialize")
    case Initialize(
//                     jsonrpc: String = JSONRPC_VERSION, // todo: include here otherwise not serialized
                     override val id: RequestId,
                     params: ClientImplementation,
                   ) extends JSONRPCRequest(id = id)

    // todo: no ID?
    @caseName("notifications/initialized")
    case NotificationsInitialized(
//                                   jsonrpc: String = JSONRPC_VERSION, // todo: include here otherwise not serialized
      override val id: RequestId = -1,
                                 ) extends JSONRPCRequest(id = id)

    @caseName("ping")
    case Ping(
//               jsonrpc: String = JSONRPC_VERSION, // todo: include here otherwise not serialized
      override val id: RequestId,
             ) extends JSONRPCRequest(id = id)

    @caseName("tools/list")
    case ToolsList(
//                    jsonrpc: String = JSONRPC_VERSION, // todo: include here otherwise not serialized
      override val id: RequestId,
                  ) extends JSONRPCRequest(id = id)

    @caseName("tools/call")
    case ToolsCall(
//                    jsonrpc: String = JSONRPC_VERSION, // todo: include here otherwise not serialized
      override val id: RequestId,
                    params: ToolParams
                  ) extends JSONRPCRequest(id = id)


