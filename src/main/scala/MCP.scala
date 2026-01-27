import SymbolSearch.HerokuInference
import chimp.{mcpEndpoint, tool}
import com.jamesward.zio_mavencentral.MavenCentral.*
import io.circe.generic.semiauto.{deriveCodec, deriveEncoder}
import io.circe.syntax.*
import io.circe.{Codec, Decoder, Encoder}
import sttp.tapir.Schema
import sttp.tapir.generic.auto.*
import zio.direct.*
import zio.http.Client
import zio.redis.Redis
import zio.{RIO, ZIO}

object MCP:

  given Schema[GroupId] = Schema.string.description("Maven Central Group ID")
  given Schema[ArtifactId] = Schema.string.description("Maven Central Artifact ID")
  given Schema[Version] = Schema.string.description("Maven Central Version")

  given Codec[GroupId] = Codec.from(Decoder.decodeString.map(GroupId(_)), Encoder.encodeString.contramap(_.asInstanceOf[String]))
  given Codec[ArtifactId] = Codec.from(Decoder.decodeString.map(ArtifactId(_)), Encoder.encodeString.contramap(_.asInstanceOf[String]))
  given Codec[Version] = Codec.from(Decoder.decodeString.map(Version(_)), Encoder.encodeString.contramap(_.asInstanceOf[String]))

  case class Symbol(query: String) derives io.circe.Codec, Schema

  given Codec[GroupArtifactVersion] = deriveCodec
  given Schema[GroupArtifactVersion] = Schema.derived

  given Codec[GroupArtifact] = deriveCodec
  given Schema[GroupArtifact] = Schema.derived

  given Encoder[Extractor.Content] = deriveEncoder
  given Encoder[Set[Extractor.Content]] = Encoder.encodeSet[Extractor.Content]

  val getLatestTool = tool("get_latest_version")
    .description("Gets the latest version of a given artifact")
    .input[GroupArtifact]

  val getLatestServerTool = getLatestTool.serverLogic[[X] =>> RIO[Extractor.JavadocCache & Extractor.SourcesCache & Client & Extractor.FetchBlocker & Extractor.FetchSourcesBlocker & Redis & HerokuInference, X]]: (input, _) =>
    ZIO.scoped:
      Extractor.latest(input).mapBoth(_.toString, _.toString).either


  val getClassesTool = tool("get_javadoc_content_list")
    .description("Gets a list of the contents of a javadoc jar")
    .input[GroupArtifactVersion]

  val getClassesServerTool = getClassesTool.serverLogic[[X] =>> RIO[Extractor.JavadocCache & Extractor.SourcesCache & Client & Extractor.FetchBlocker & Extractor.FetchSourcesBlocker & Redis & HerokuInference, X]]: (input, _) =>
    ZIO.scoped:
      defer:
        App.indexJavadocContents(input).run
        Extractor.javadocContents(input).mapBoth(_.toString, _.asJson.toString).either.run


  case class JavadocSymbol(groupId: GroupId, artifactId: ArtifactId, version: Version, link: String) derives io.circe.Codec, Schema

  val getSymbolContentsTool = tool("get_javadoc_symbol_contents")
    .description(s"Gets the contents of a javadoc symbol. Get the symbol link from the ${getClassesTool.name} tool.")
    .input[JavadocSymbol]

  // todo: should this convert the html to markdown?
  val getSymbolContentsServerTool = getSymbolContentsTool.serverLogic[[X] =>> RIO[Extractor.JavadocCache & Extractor.SourcesCache & Client & Extractor.FetchBlocker & Extractor.FetchSourcesBlocker & Redis & HerokuInference, X]]: (input, _) =>
    val groupArtifactVersion = GroupArtifactVersion(input.groupId, input.artifactId, input.version)
    ZIO.scoped:
      Extractor.javadocSymbolContents(groupArtifactVersion, input.link).mapError(_.toString).either

  val listSourceTool = tool("list_source_contents")
    .description("Gets a list of the contents of a source jar")
    .input[GroupArtifactVersion]

  val listSourceServerTool = listSourceTool.serverLogic[[X] =>> RIO[Extractor.JavadocCache & Extractor.SourcesCache & Client & Extractor.FetchBlocker & Extractor.FetchSourcesBlocker & Redis & HerokuInference, X]]: (input, _) =>
    ZIO.scoped:
      Extractor.sourceContents(input).mapBoth(_.toString, _.asJson.toString).either

  val getSourceTool = tool("get_source_contents")
    .description(s"Gets the contents of a source file. Get the list of files from the ${listSourceTool.name} tool.")
    .input[JavadocSymbol]

  val getSourceServerTool = getSourceTool.serverLogic[[X] =>> RIO[Extractor.JavadocCache & Extractor.SourcesCache & Client & Extractor.FetchBlocker & Extractor.FetchSourcesBlocker & Redis & HerokuInference, X]]: (input, _) =>
    val groupArtifactVersion = GroupArtifactVersion(input.groupId, input.artifactId, input.version)
    ZIO.scoped:
      Extractor.sourceFileContents(groupArtifactVersion, input.link).mapError(_.toString).either

  val symbolToArtifactTool = tool("symbol_to_artifact")
    .description("Gets the group and artifact for a given symbol/class/package")
    .input[Symbol]

  val symbolToArtifactServerTool = symbolToArtifactTool.serverLogic[[X] =>> RIO[Extractor.JavadocCache & Extractor.SourcesCache & Client & Extractor.FetchBlocker & Extractor.FetchSourcesBlocker & Redis & HerokuInference, X]]: (input, _) =>
    ZIO.scoped:
      // todo: rate limit
      SymbolSearch.search(input.query).mapBoth(_.getMessage, _.asJson.toString).either


  val mcpServerEndpoint = mcpEndpoint(
    List(getLatestServerTool, getClassesServerTool, getSymbolContentsServerTool, getSourceServerTool, listSourceServerTool, symbolToArtifactServerTool),
    List("mcp"),
    "javadocs.dev",
    "0.0.2",
    false
  )
