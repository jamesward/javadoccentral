package mcp.schema

import zio.Chunk
import zio.schema.{Schema, StandardType, derived}
import zio.schema.annotation.{caseName, discriminatorName}

object JsonSchema:

  @discriminatorName("type")
  enum JsonSchemaType(description: Option[String]) derives Schema:
    @caseName("string")
    case JsonString(description: Option[String] = None) extends JsonSchemaType(description)

    @caseName("number")
    case JsonNumber(description: Option[String] = None) extends JsonSchemaType(description)

    @caseName("integer")
    case JsonInteger(description: Option[String] = None) extends JsonSchemaType(description)

    @caseName("boolean")
    case JsonBoolean(description: Option[String] = None) extends JsonSchemaType(description)

    @caseName("array")
    case JsonArray(items: List[JsonSchemaType], description: Option[String] = None) extends JsonSchemaType(description)

    @caseName("object")
    case JsonObject(properties: Map[String, JsonSchemaType], required: List[String], description: Option[String] = None) extends JsonSchemaType(description)


  def schemaToJsonSchema[A](s: Schema[A], annotations: Chunk[Any] = Chunk.empty): JsonSchemaType =
    s match
      case Schema.Primitive(standardType, _) => // annotation is on the field
        standardType match
          case _: StandardType.IntType.type => JsonSchemaType.JsonInteger(annotations.collectFirst { case zio.schema.annotation.description(text) => text })
          case _: StandardType.LongType.type => JsonSchemaType.JsonInteger(annotations.collectFirst { case zio.schema.annotation.description(text) => text })
          case _: StandardType.DoubleType.type => JsonSchemaType.JsonNumber(annotations.collectFirst { case zio.schema.annotation.description(text) => text })
          case _: StandardType.FloatType.type => JsonSchemaType.JsonNumber(annotations.collectFirst { case zio.schema.annotation.description(text) => text })
          case _: StandardType.BoolType.type => JsonSchemaType.JsonBoolean(annotations.collectFirst { case zio.schema.annotation.description(text) => text })
          case _ => JsonSchemaType.JsonString(annotations.collectFirst { case zio.schema.annotation.description(text) => text })

      case Schema.Lazy(ss) =>
        schemaToJsonSchema(ss(), annotations)
      case Schema.Optional(schema, _) =>
        schemaToJsonSchema(schema, annotations)
      case r: Schema.Record[?] =>
        JsonSchemaType.JsonObject(
          properties = r.fields.map {
            field =>
              field.fieldName -> schemaToJsonSchema(field.schema, field.annotations)
          }.toMap,
          required = r.fields.filterNot { field =>
            field.schema match
              case Schema.Lazy(ss) =>
                ss().isInstanceOf[Schema.Optional[?]]
              case Schema.Optional(_, _) =>
                true
              case _ =>
                false
          }.map(_.fieldName).toList,
          description = r.annotations.collectFirst { case zio.schema.annotation.description(text) => text },
        )
      case e: Schema.Collection[?, ?] =>
        ???
      case e: Schema.Enum[?] =>
        ???
      case t: Schema.Transform[?, ?, ?] =>
        schemaToJsonSchema(t.schema, t.annotations)
      case f: Schema.Fail[?] =>
        ???
      case f: Schema.Fallback[?, ?] =>
        ???
      case e: Schema.Either[?, ?] =>
        ???
      case d: Schema.Dynamic =>
        ???
      case t: Schema.Tuple2[?, ?] =>
        ???
