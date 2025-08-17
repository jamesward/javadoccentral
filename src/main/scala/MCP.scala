import chimp.{mcpEndpoint, tool}
import com.jamesward.zio_mavencentral.MavenCentral.*
import io.circe.syntax.*
import io.circe.generic.semiauto.{deriveCodec, deriveEncoder}
import io.circe.{Codec, Decoder, Encoder}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*
import zio.http.Client
import zio.{RIO, ZIO}

object MCP {

  given Schema[GroupId] = Schema.string.description("Maven Central Group ID")
  given Schema[ArtifactId] = Schema.string.description("Maven Central Artifact ID")
  given Schema[Version] = Schema.string.description("Maven Central Version")

  given Codec[GroupId] = Codec.from(Decoder.decodeString.map(GroupId(_)), Encoder.encodeString.contramap(_.asInstanceOf[String]))
  given Codec[ArtifactId] = Codec.from(Decoder.decodeString.map(ArtifactId(_)), Encoder.encodeString.contramap(_.asInstanceOf[String]))
  given Codec[Version] = Codec.from(Decoder.decodeString.map(Version(_)), Encoder.encodeString.contramap(_.asInstanceOf[String]))
  given Codec[GroupArtifactVersion] = deriveCodec
  given Schema[GroupArtifactVersion] = Schema.derived

  given Encoder[Extractor.Content] = deriveEncoder
  given Encoder[Set[Extractor.Content]] = Encoder.encodeSet[Extractor.Content]

  val getClassesTool = tool("get_javadoc_content_list")
    .description("Gets a list of the contents of a javadoc jar")
    .input[GroupArtifactVersion]

  val getClassesServerTool = getClassesTool.serverLogic[[X] =>> RIO[Extractor.JavadocCache & Client & Extractor.FetchBlocker, X]]: (input, _) =>
    ZIO.scoped:
      Extractor.javadocContents(input)
        .map(_.asJson.toString)
        .mapError(_.toString)
        .either

  val mcpServerEndpoint = mcpEndpoint(List(getClassesServerTool), List("mcp"))

  // tool: latest version

  // tool: javadoc contents

}
