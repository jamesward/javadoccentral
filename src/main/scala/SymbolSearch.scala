import com.jamesward.zio_mavencentral.MavenCentral
import zio.*
import zio.direct.*
import zio.http.*
import zio.http.Header.Authorization
import zio.json.{DecoderOps, JsonCodec}
import zio.redis.{CodecSupplier, Count, Redis}
import zio.schema.*
import zio.schema.annotation.*
import zio.schema.codec.{BinaryCodec, ProtobufCodec}
import zio.stream.ZStream

object SymbolSearch:

  object ProtobufCodecSupplier extends CodecSupplier:
    def get[A: Schema]: BinaryCodec[A] = ProtobufCodec.protobufCodec

  def stringToGroupArtifact(s: String): MavenCentral.GroupArtifact =
    // todo: parse failure handling
    val parts = s.split(':')
    MavenCentral.GroupArtifact(MavenCentral.GroupId(parts(0)), MavenCentral.ArtifactId(parts(1)))

  def groupArtifactToString(ga: MavenCentral.GroupArtifact): String = s"${ga.groupId}:${ga.artifactId}"

  given Schema[MavenCentral.GroupArtifact] = Schema.primitive[String].transform(stringToGroupArtifact, groupArtifactToString)

  given Schema[MavenCentral.GroupId] = Schema.primitive[String].transform(MavenCentral.GroupId.apply, _.toString)
  given Schema[MavenCentral.ArtifactId] = Schema.primitive[String].transform(MavenCentral.ArtifactId.apply, _.toString)
  private val groupArtifactSchemaForJson = DeriveSchema.gen[MavenCentral.GroupArtifact]

  given JsonCodec[MavenCentral.GroupArtifact] = zio.schema.codec.JsonCodec.jsonCodec(groupArtifactSchemaForJson)

  case class Message(role: String = "user", content: String) derives Schema

  case class InferenceRequest(model: String, messages: List[Message]) derives Schema

  case class MessageChoice(
    index: Int,
    message: Message,
    @fieldName("finish_reason") finishReason: String
  ) derives Schema

  case class MessageUsage(
    @fieldName("prompt_tokens") promptTokens: Int,
    @fieldName("completion_tokens") completionTokens: Int,
    @fieldName("total_tokens") totalTokens: Int
  ) derives Schema

  case class MessageResponse(
    id: String,
    `object`: String,
    created: Int,
    model: String,
    choices: List[MessageChoice],
    usage: MessageUsage
  ) derives Schema

  trait HerokuInference:
    def req(prompt: String): ZIO[Client & Scope, Throwable, Response]

  object HerokuInference:
    case class Live(inferenceUrl: String, inferenceKey: String, inferenceModelId: String) extends HerokuInference:
      private val url = URL.decode(inferenceUrl).getOrElse(throw new RuntimeException("invalid inference url"))

      def req(prompt: String): ZIO[Client & Scope, Throwable, Response] =
        defer:
          val client = ZIO.serviceWith[Client](_.addHeader(Authorization.Bearer(token = inferenceKey)).url(url)).run
          val inferenceRequest = InferenceRequest(inferenceModelId, List(Message(content = prompt)))
          client.post("/v1/chat/completions")(Body.json(inferenceRequest)).run

  val herokuInferenceLayer: ZLayer[Any, Throwable, HerokuInference] =
    ZLayer.fromZIO:
      defer:
        val system = ZIO.system.run
        val inferenceUrl = system.env("INFERENCE_URL").map(_.filter(_.nonEmpty)).someOrFail(new RuntimeException("INFERENCE_URL env var not set")).run
        val inferenceKey = system.env("INFERENCE_KEY").map(_.filter(_.nonEmpty)).someOrFail(new RuntimeException("INFERENCE_KEY env var not set")).run
        val inferenceModelId = system.env("INFERENCE_MODEL_ID").map(_.filter(_.nonEmpty)).someOrFail(new RuntimeException("INFERENCE_MODEL_ID env var not set")).run
        HerokuInference.Live(inferenceUrl, inferenceKey, inferenceModelId)

  // todo: heroku inference structured output??
  def aiSearch(symbol: String): ZIO[HerokuInference & Client & Scope, Throwable, Set[MavenCentral.GroupArtifact]] =
    defer:
      val herokuInference = ZIO.service[HerokuInference].run
      val prompt =
        s"""List the most probable maven central artifacts that contain the symbol: `$symbol`
          |If you don't know the maven group id or artifact id, just return an empty list.
          |The response must be JSON structured in this format:
          |[
          |  {
          |    groupId: "the maven group id",
          |    artifactId: "the maven artifact id"
          |  }
          |]
          |""".stripMargin
      val resp = herokuInference.req(prompt).run
      if !resp.status.isSuccess then
        val errorBody = resp.body.asString.run
        ZIO.fail(new RuntimeException(s"Inference request failed with status ${resp.status}: $errorBody")).run

      val body = resp.body.asJson[MessageResponse].tapError: e =>
        ZIO.logError(s"Failed to parse inference response: ${e.getMessage}")
      .run
      val choice = ZIO.fromOption(body.choices.headOption).orElseFail(Exception("no llm messages found in response")).run
      val workaroundForMarkdownContent = choice.message.content.replace("```json", "").replace("```", "").trim
      workaroundForMarkdownContent.fromJson[Set[MavenCentral.GroupArtifact]].getOrElse(Set.empty)

  def search(symbol: String): ZIO[Redis & HerokuInference & Scope & Client & Extractor.FetchBlocker & Extractor.JavadocCache, Throwable, Set[MavenCentral.GroupArtifact]] =
    defer:
      val redis = ZIO.service[Redis].run
      val pattern = "*" + symbol + "*"

      val allKeys = ZStream.paginateZIO(0L): cursor =>
        redis.scan(cursor, Some(pattern), Some(Count(10_000L))).returning[String].map:
          case (nextCursor, keys) =>
            val next = if (nextCursor == 0L) None else Some(nextCursor)
            (keys, next)
      .runCollect.run.flatten

      val cacheResults = ZIO.foreachPar(allKeys): key =>
        redis.sMembers(key).returning[MavenCentral.GroupArtifact].catchAll: e =>
          defer:
            ZIO.logError(e.toString).run
            redis.del(key).run // unparsable keys shouldn't be in there
            Chunk.empty
      .run
      .flatten
      .toSet

      if cacheResults.isEmpty then
        val aiResults = aiSearch(symbol).tapError: e =>
          ZIO.logError(s"aiSearch failed for symbol: $symbol: ${e.getMessage}")
        .run
        val indexLoad = aiResults.map:
          groupArtifact =>
            Extractor.latest(groupArtifact).flatMap:
              latest =>
                App.indexJavadocContents(MavenCentral.GroupArtifactVersion(groupArtifact.groupId, groupArtifact.artifactId, latest))
            .ignore
        ZIO.collectAllParDiscard(indexLoad).forkDaemon.run
        aiResults
      else
        cacheResults

  def update(groupArtifact: MavenCentral.GroupArtifact, symbols: Set[Extractor.Content]): ZIO[Redis, Throwable, Unit] =
    defer:
      val redis = ZIO.service[Redis].run
      ZIO.foreachPar(symbols):
        symbol =>
          redis.sAdd(symbol.fqn, groupArtifact)
      .unit
      .run
