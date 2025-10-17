import mcp.schema.JsonSchema
import zio.schema.annotation.description
import zio.schema.codec.JsonCodec
import zio.schema.{Schema, derived}
import zio.test.*

object JsonSchemaSpec extends ZIOSpecDefault:

  def spec = suite("JsonSchema")(
    test("JsonSchemaType") {
      val testObject = JsonSchema.JsonSchemaType.JsonObject(
        Map(
          "s" -> JsonSchema.JsonSchemaType.JsonString(Some("a string")),
          "i" -> JsonSchema.JsonSchemaType.JsonInteger(),
        ),
        List("s"),
        Some("a test object"),
      )

      val codec = JsonCodec.schemaBasedBinaryCodec[JsonSchema.JsonSchemaType]
      val json = codec.encode(testObject).asString

      assertTrue(
        json == """{"type":"object","properties":{"s":{"type":"string","description":"a string"},"i":{"type":"integer"}},"required":["s"],"description":"a test object"}""",
      )
    },
    test("primitive") {
      val jsonSchema = JsonSchema.schemaToJsonSchema(Schema.primitive[String])

      assertTrue(
        jsonSchema.isInstanceOf[JsonSchema.JsonSchemaType.JsonString],
      )
    },
    test("groupartifactversion") {
      val jsonSchema = JsonSchema.schemaToJsonSchema(App.given_Schema_GroupArtifact)
      assertTrue(
        jsonSchema.asInstanceOf[JsonSchema.JsonSchemaType.JsonObject].properties("groupId").asInstanceOf[JsonSchema.JsonSchemaType.JsonString].description.get.contains("group id"),
        jsonSchema.asInstanceOf[JsonSchema.JsonSchemaType.JsonObject].required == List("groupId", "artifactId"),
      )
    },
    test("object") {
      case class Foo(
                      @description("a string") s: String,
                      @description("an optional integer") i: Option[Int],
                    ) derives Schema

      val jsonSchema = JsonSchema.schemaToJsonSchema(summon[Schema[Foo]]).asInstanceOf[JsonSchema.JsonSchemaType.JsonObject]

      assertTrue(
        jsonSchema.description.isEmpty,
        jsonSchema.properties("s").asInstanceOf[JsonSchema.JsonSchemaType.JsonString].description.contains("a string"),
        jsonSchema.properties("i").asInstanceOf[JsonSchema.JsonSchemaType.JsonInteger].description.contains("an optional integer"),
        jsonSchema.required == List("s"),
      )
    }
  )
