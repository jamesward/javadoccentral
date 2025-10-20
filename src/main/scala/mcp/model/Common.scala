package mcp.model

import mcp.schema.JsonSchema
import mcp.schema.JsonSchema.JsonSchemaType
import zio.ZIO
import zio.json.ast.Json
import zio.schema.Schema

// can't be in the Common object because of Schema derivation issues
type RequestId = String | Int

object Common:
  val JSONRPC_VERSION = "2.0"
  val PROTOCOL_VERSION = "2025-06-18"

  case class Info(
                 name: String,
                 version: String
               )

  case class JSONRPCError(code: Int, message: String, data: Option[Json])

  case class Tool[S: Schema, -Env, +Err, A: Schema](
                                               name: String,
                                               description: String,
                                               handler: S => ZIO[Env, Err, A]
                                             ):
    //    type InputSchema = Schema[S]
    val inputSchema = summon[Schema[S]]
    val inputSchemaAsJsonSchema: JsonSchemaType =
      JsonSchema.schemaToJsonSchema(summon[Schema[S]])
    val outputSchema = summon[Schema[A]]

  val PARSE_ERROR = -32700
  val INVALID_REQUEST = -32600
  val METHOD_NOT_FOUND = -32601
  val INVALID_PARAMS = -32602
  val INTERNAL_ERROR = -32603
