import chimp.{mcpEndpoint, tool}
import com.jamesward.zio_mavencentral.MavenCentral.*
import io.circe.syntax.*
import io.circe.generic.semiauto.{deriveCodec, deriveEncoder}
import io.circe.{Codec, Decoder, Encoder}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*
import zio.http.Client
import zio.{RIO, ZIO}

object MCP:

  given Schema[GroupId] = Schema.string.description("Maven Central Group ID")
  given Schema[ArtifactId] = Schema.string.description("Maven Central Artifact ID")
  given Schema[Version] = Schema.string.description("Maven Central Version")

  given Codec[GroupId] = Codec.from(Decoder.decodeString.map(GroupId(_)), Encoder.encodeString.contramap(_.asInstanceOf[String]))
  given Codec[ArtifactId] = Codec.from(Decoder.decodeString.map(ArtifactId(_)), Encoder.encodeString.contramap(_.asInstanceOf[String]))
  given Codec[Version] = Codec.from(Decoder.decodeString.map(Version(_)), Encoder.encodeString.contramap(_.asInstanceOf[String]))

  given Codec[GroupArtifactVersion] = deriveCodec
  given Schema[GroupArtifactVersion] = Schema.derived

  given Codec[GroupArtifact] = deriveCodec
  given Schema[GroupArtifact] = Schema.derived

  given Encoder[Extractor.Content] = deriveEncoder
  given Encoder[Set[Extractor.Content]] = Encoder.encodeSet[Extractor.Content]

  val getLatestTool = tool("get_latest_version")
    .description("Gets the latest version of a given artifact")
    .input[GroupArtifact]

  val getLatestServerTool = getLatestTool.serverLogic[[X] =>> RIO[Extractor.JavadocCache & Client & Extractor.FetchBlocker, X]]: (input, _) =>
    ZIO.scoped:
      Extractor.latest(input)
        .map(_.toString)
        .mapError(_.toString)
        .either


  val getClassesTool = tool("get_javadoc_content_list")
    .description("Gets a list of the contents of a javadoc jar")
    .input[GroupArtifactVersion]

  val getClassesServerTool = getClassesTool.serverLogic[[X] =>> RIO[Extractor.JavadocCache & Client & Extractor.FetchBlocker, X]]: (input, _) =>
    ZIO.scoped:
      Extractor.javadocContents(input)
        .map(_.asJson.toString)
        .mapError(_.toString)
        .either


  case class JavadocSymbol(groupId: GroupId, artifactId: ArtifactId, version: Version, link: String) derives io.circe.Codec, Schema

  val getSymbolContentsTool = tool("get_javadoc_symbol_contents")
    .description(s"Gets the contents of a javadoc symbol. Get the symbole link from the ${getClassesTool.name} tool.")
    .input[JavadocSymbol]

  // todo: should this convert the html to markdown?
  val getSymbolContentsServerTool = getSymbolContentsTool.serverLogic[[X] =>> RIO[Extractor.JavadocCache & Client & Extractor.FetchBlocker, X]]: (input, _) =>
    val groupArtifactVersion = GroupArtifactVersion(input.groupId, input.artifactId, input.version)
    ZIO.scoped:
      Extractor.javadocSymbolContents(groupArtifactVersion, input.link)
        .mapError(_.toString)
        .either


  val mcpServerEndpoint = mcpEndpoint(
    List(getLatestServerTool, getClassesServerTool, getSymbolContentsServerTool),
    List("mcp"),
    "javadocs.dev",
    "0.0.1",
    false
  )
