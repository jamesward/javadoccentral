import com.jamesward.zio_mavencentral.MavenCentral
import zio.*
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.http.*
import zio.http.Header.Authorization
import zio.json.{DecoderOps, JsonCodec}
import zio.schema.*
import zio.schema.annotation.*

object SymbolSearch:

  import MavenCentral.MavenCentralRepo

  /** Artifacts being indexed right now, so concurrent requests for one artifact index it once. */
  case class SymbolSearchGuard(active: ConcurrentMap[MavenCentral.GroupArtifact, Unit])

  case class InferenceError(message: String)
  case class SearchError(message: String)

  // for deserializing inference responses
  given JsonCodec[MavenCentral.GroupArtifact] = zio.schema.codec.JsonCodec.jsonCodec(MavenCentral.GroupArtifact.schema)

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
  def aiSearch(symbol: String): ZIO[HerokuInference & Client & Scope, InferenceError, Set[MavenCentral.GroupArtifact]] =
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
      val resp = herokuInference.req(prompt).mapError(e => InferenceError(e.getMessage)).run
      if !resp.status.isSuccess then
        val errorBody = resp.body.asString.mapError(e => InferenceError(e.getMessage)).run
        ZIO.fail(InferenceError(s"Inference request failed with status ${resp.status}: $errorBody")).run

      val body = resp.body.asJson[MessageResponse].tapError: e =>
        ZIO.logAnnotate(LogAnnotation("error", e.getMessage))(ZIO.logError("Failed to parse inference response"))
      .mapError(e => InferenceError(s"Failed to parse inference response: ${e.getMessage}"))
      .run
      val choice = ZIO.fromOption(body.choices.headOption).orElseFail(InferenceError("no llm messages found in response")).run
      val workaroundForMarkdownContent = choice.message.content.replace("```json", "").replace("```", "").trim
      workaroundForMarkdownContent.fromJson[Set[MavenCentral.GroupArtifact]].getOrElse(Set.empty)

  /** How a symbol query was matched, in the order they are tried. */
  enum Match derives CanEqual:
    case Exact, Containing, Similar

  // Substring and similarity lookups can't use the trigram indexes below 3 characters.
  private val minFuzzyLength = 3
  private val similarLimit = 20

  private def lookup(tier: Match, symbol: String): ZIO[SymbolIndex, Throwable, Set[MavenCentral.GroupArtifact]] =
    val query = tier match
      case Match.Exact      => SymbolIndex.exact(symbol)
      case Match.Containing => SymbolIndex.containing(symbol)
      case Match.Similar    => SymbolIndex.similar(symbol, similarLimit)
    query.timed.tap: (duration, results) =>
      ZIO.logAnnotate(
        LogAnnotation("symbol", symbol),
        LogAnnotation("match", tier.toString),
        LogAnnotation("results", results.size.toString),
        LogAnnotation("durationMs", duration.toMillis.toString),
      )(ZIO.logInfo("Symbol search"))
    .map(_._2)

  /** The first tier (exact, then containing, then similar spelling) that finds anything. */
  def symbolArtifacts(symbol: String): ZIO[SymbolIndex, Throwable, Set[MavenCentral.GroupArtifact]] =
    val tiers = if symbol.trim.length >= minFuzzyLength then Match.values.toList else List(Match.Exact)
    ZIO.foldLeft(tiers)(Set.empty[MavenCentral.GroupArtifact]): (found, tier) =>
      if found.nonEmpty then ZIO.succeed(found) else lookup(tier, symbol)

  def search(symbol: String): ZIO[SymbolIndex & HerokuInference & Client & MavenCentralRepo & Extractor.JavadocCache & SymbolSearchGuard, SearchError, Set[MavenCentral.GroupArtifact]] =
    defer:
      val gaResults = searchGroupArtifacts(symbol).mapError(e => SearchError(e.toString)).run

      val cacheResults =
        if gaResults.nonEmpty then gaResults
        else symbolArtifacts(symbol).mapError(e => SearchError(e.toString)).run

      if cacheResults.isEmpty then
        // `aiSearch` needs a `Scope` for its HTTP response body; that
        // scope is local to the AI call (the body is fully materialized
        // here) so we don't propagate `Scope` out of `search`.
        val aiResults = ZIO.scoped(aiSearch(symbol)).mapError(e => SearchError(e.message)).tapError: e =>
          ZIO.logAnnotate(
            LogAnnotation("symbol", symbol),
            LogAnnotation("error", e.message),
          )(ZIO.logError("aiSearch failed"))
        .run
        ZIO.logAnnotate(
          LogAnnotation("symbol", symbol),
          LogAnnotation("results", aiResults.size.toString),
        )(ZIO.logInfo("AI search")).run
        val validatedResults = ZIO.filterPar(aiResults): ga =>
          MavenCentral.isArtifact(ga.groupId, ga.artifactId).orElseSucceed(false)
        .run
        val indexLoad = validatedResults.map:
          groupArtifact =>
            Extractor.latest(groupArtifact).flatMap:
              latest =>
                indexJavadocContents(MavenCentral.GroupArtifactVersion(groupArtifact.groupId, groupArtifact.artifactId, latest))
            .ignore
        ZIO.logAnnotate(
          LogAnnotation("symbol", symbol),
          LogAnnotation("artifacts", validatedResults.size.toString),
        )(ZIO.logInfo("Scheduling index load")).run
        // `indexJavadocContents` forks its own daemon and `Extractor.latest`
        // doesn't require `Scope`, so this daemon has no `Scope` to inherit.
        ZIO.collectAllParDiscard(indexLoad).forkDaemon.run
        validatedResults
      else
        cacheResults

  def queryParts(query: String): List[String] =
    query.toLowerCase.split("\\s+").nn.map(_.nn.replaceAll("[^a-z0-9._:-]", "")).toList.filter(_.nonEmpty)

  private val versionLike = "^v?\\d+([._-][0-9a-z]+)*$".r

  /** A standalone version token such as `3`, `3.0.0`, `2.1.0-m1` or `v1.2`. */
  def isVersionToken(part: String): Boolean = versionLike.matches(part)

  /**
   * Indexed artifacts whose `groupId:artifactId` contains every part of the query.
   * Agents routinely append a version to a name query ("jackson-databind 3.0.0"),
   * which can never match a `groupId:artifactId`. If the full query finds
   * nothing, retry without the version-like tokens.
   */
  def searchGroupArtifacts(query: String): ZIO[SymbolIndex, Throwable, Set[MavenCentral.GroupArtifact]] =
    val parts = queryParts(query)
    val withoutVersions = parts.filterNot(isVersionToken)
    SymbolIndex.groupArtifactsContainingAll(parts).flatMap: found =>
      if found.nonEmpty || withoutVersions.size == parts.size then ZIO.succeed(found)
      else SymbolIndex.groupArtifactsContainingAll(withoutVersions)

  def update(groupArtifactVersion: MavenCentral.GroupArtifactVersion, symbols: Set[Extractor.Content]): ZIO[SymbolIndex & SymbolSearchGuard, Throwable, Unit] =
    defer:
      val guard = ZIO.service[SymbolSearchGuard].run.active
      val groupArtifact = groupArtifactVersion.noVersion
      val indexed = SymbolIndex.indexedVersion(groupArtifact).run
      if !indexed.contains(groupArtifactVersion.version) && SymbolIndex.supersedes(groupArtifactVersion.version, indexed) then
        // Another fiber already indexing this artifact: skip (`replace` would also serialize them).
        val existing = guard.putIfAbsent(groupArtifact, ()).run
        if existing.isEmpty then
          replaceSymbols(groupArtifactVersion, symbols).ensuring(guard.remove(groupArtifact)).run

  private def replaceSymbols(groupArtifactVersion: MavenCentral.GroupArtifactVersion, symbols: Set[Extractor.Content]): ZIO[SymbolIndex, Throwable, Unit] =
    defer:
      val (duration, written) = SymbolIndex.replace(groupArtifactVersion, symbols.map(_.fqn)).timed.run
      ZIO.logAnnotate(
        LogAnnotation("gav", groupArtifactVersion.toString),
        LogAnnotation("symbols", symbols.size.toString),
        LogAnnotation("written", written.toString),
        LogAnnotation("durationMs", duration.toMillis.toString),
      )(ZIO.logInfo("Updated symbol index")).run

  // We only want to trigger symbol cache loading when the index page is loaded;
  // this is a lazy way to populate the cache. The returned effect forks a
  // daemon. With `JarCache`'s append-only model the daemon's `JarHandle` stays
  // valid until app shutdown — no Scope coordination needed.
  def indexJavadocContents(groupArtifactVersion: MavenCentral.GroupArtifactVersion):
    ZIO[Client & MavenCentralRepo & Extractor.JavadocCache & SymbolIndex & SymbolSearch.SymbolSearchGuard, Nothing, Unit] =

    val getContentsAndUpdateIndex =
      defer:
        val (parseDuration, contents) = Extractor.javadocContents(groupArtifactVersion).timed.run
        ZIO.logAnnotate(
          LogAnnotation("gav", groupArtifactVersion.toString),
          LogAnnotation("symbols", contents.size.toString),
          LogAnnotation("durationMs", parseDuration.toMillis.toString),
        )(ZIO.logInfo("Index load parsed")).run
        SymbolSearch.update(groupArtifactVersion, contents).run

    getContentsAndUpdateIndex
      .catchAllCause: cause =>
        ZIO.logAnnotate(LogAnnotation("gav", groupArtifactVersion.toString))(ZIO.logWarningCause("Index load failed", cause))
      .forkDaemon.unit
