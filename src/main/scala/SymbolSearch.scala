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

  /** How a symbol query was matched. */
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

  /**
   * Resolving a query to artifacts (`search`), as a [[StateMachine]]:
   * {{{
   * Query ──name match──▶ Found
   *   └──▶ SymbolTiers ──▶ Found
   *         ├──▶ SymbolTiers (next tier)
   *         └──▶ NotIndexed ──AI search──▶ Found
   * }}}
   * Symbol tiers: names containing the query (any case) first, then close spellings. An
   * exact name match is always a containing match too, so stopping at exact matches would
   * hide other artifacts ("jev" is one artifact's `Mode.Jev` and part of other artifacts'
   * `JevJudge`). Queries too short for trigrams only match exactly. A dotted query is a
   * qualified name: when nothing contains it, the artifact is most likely not indexed yet,
   * so it goes to AI search (which indexes it) rather than to spelling matches of its last
   * segment (`zio.cache.Cache` → every `Cache`).
   */
  object Resolution:
    case class Query(symbol: String)
    case class SymbolTiers(symbol: String, tiers: ::[Match])
    /** Nothing in the index matches. */
    case class NotIndexed(symbol: String)
    case class Found(artifacts: Set[MavenCentral.GroupArtifact])

    def byName(query: Query): ZIO[SymbolIndex, Throwable, Found | SymbolTiers] =
      searchGroupArtifacts(query.symbol).map: found =>
        if found.nonEmpty then Found(found) else tiersFor(query.symbol)

    def tiersFor(symbol: String): SymbolTiers =
      val trimmed = symbol.trim
      val tiers =
        if trimmed.length < minFuzzyLength then ::(Match.Exact, Nil)
        else if trimmed.contains('.') then ::(Match.Containing, Nil)
        else ::(Match.Containing, List(Match.Similar))
      SymbolTiers(symbol, tiers)

    def bySymbol(tiers: SymbolTiers): ZIO[SymbolIndex, Throwable, Found | SymbolTiers | NotIndexed] =
      lookup(tiers.tiers.head, tiers.symbol).map: found =>
        if found.nonEmpty then Found(found)
        else tiers.tiers.tail match
          case next :: rest => SymbolTiers(tiers.symbol, ::(next, rest))
          case Nil          => NotIndexed(tiers.symbol)

    def byAi(notIndexed: NotIndexed): ZIO[HerokuInference & Client & MavenCentralRepo & Extractor.JavadocCache & SymbolIndex & SymbolSearchGuard, SearchError, Found] =
      aiSearchAndIndex(notIndexed.symbol).map(Found(_))

  def symbolArtifacts(symbol: String): ZIO[SymbolIndex, Throwable, Set[MavenCentral.GroupArtifact]] =
    import Resolution.*
    StateMachine[SymbolTiers, Found | NotIndexed]("symbol-search").run(tiersFor(symbol)):
      case tiers: SymbolTiers => bySymbol(tiers)
    .map:
      case Found(artifacts) => artifacts
      case _: NotIndexed    => Set.empty

  def search(symbol: String): ZIO[SymbolIndex & HerokuInference & Client & MavenCentralRepo & Extractor.JavadocCache & SymbolSearchGuard, SearchError, Set[MavenCentral.GroupArtifact]] =
    import Resolution.*
    StateMachine[Query | SymbolTiers | NotIndexed, Found]("search").run(Query(symbol)):
      case query: Query       => byName(query).mapError(e => SearchError(e.toString))
      case tiers: SymbolTiers => bySymbol(tiers).mapError(e => SearchError(e.toString))
      case notIndexed: NotIndexed => byAi(notIndexed)
    .map(_.artifacts)

  /** Asks AI search for the artifacts, keeps the real ones, and schedules indexing them. */
  private def aiSearchAndIndex(symbol: String): ZIO[HerokuInference & Client & MavenCentralRepo & Extractor.JavadocCache & SymbolIndex & SymbolSearchGuard, SearchError, Set[MavenCentral.GroupArtifact]] =
    defer:
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

  /**
   * Indexing one parsed artifact version, as explicit states. Each transition takes the
   * state it requires, so the order (compare, then claim, then write) is checked by the
   * compiler: only [[Indexing.claim]] can make a [[Indexing.Claimed]], and only a
   * `Claimed` can be written. The claim's guard entry is released by its `Scope`.
   */
  object Indexing:
    /** Parsed symbols for a version, not yet compared with the index. */
    case class Candidate(gav: MavenCentral.GroupArtifactVersion, symbols: Set[Extractor.Content])

    /** Newer than the indexed version (or nothing is indexed). */
    case class Stale private[Indexing] (candidate: Candidate)

    /** This fiber holds the artifact's [[SymbolSearchGuard]] entry. */
    case class Claimed private[Indexing] (candidate: Candidate)

    /** Why a candidate wasn't written. */
    enum Skipped:
      /** The same or a newer version is already indexed. */
      case UpToDate(indexed: Option[MavenCentral.Version])
      /** Another fiber is indexing this artifact (`replace` would also serialize them). */
      case Busy

    def compare(candidate: Candidate): ZIO[SymbolIndex, Throwable, Stale | Skipped] =
      SymbolIndex.indexedVersion(candidate.gav.noVersion).map: indexed =>
        val version = candidate.gav.version
        if !indexed.contains(version) && SymbolIndex.supersedes(version, indexed) then Stale(candidate)
        else Skipped.UpToDate(indexed)

    def claim(stale: Stale): ZIO[SymbolSearchGuard & Scope, Nothing, Claimed | Skipped] =
      val groupArtifact = stale.candidate.gav.noVersion
      ZIO.serviceWithZIO[SymbolSearchGuard]: guard =>
        ZIO.acquireRelease(guard.active.putIfAbsent(groupArtifact, ()).map(_.isEmpty)): acquired =>
          ZIO.when(acquired)(guard.active.remove(groupArtifact))
        .map(acquired => if acquired then Claimed(stale.candidate) else Skipped.Busy)

    def write(claimed: Claimed): ZIO[SymbolIndex, Throwable, Boolean] =
      val Candidate(gav, symbols) = claimed.candidate
      defer:
        val (duration, written) = SymbolIndex.replace(gav, symbols.map(_.fqn)).timed.run
        ZIO.logAnnotate(
          LogAnnotation("gav", gav.toString),
          LogAnnotation("symbols", symbols.size.toString),
          LogAnnotation("written", written.toString),
          LogAnnotation("durationMs", duration.toMillis.toString),
        )(ZIO.logInfo("Updated symbol index")).run
        written

    /** Runs the machine from `candidate` to its final state: written (`true`/`false`) or skipped. */
    def run(candidate: Candidate): ZIO[SymbolIndex & SymbolSearchGuard, Throwable, Boolean | Skipped] =
      ZIO.scoped:
        StateMachine[Candidate | Stale | Claimed, Boolean | Skipped]("indexing").run(candidate):
          case candidate: Candidate => compare(candidate)
          case stale: Stale         => claim(stale)
          case claimed: Claimed     => write(claimed)

  def update(groupArtifactVersion: MavenCentral.GroupArtifactVersion, symbols: Set[Extractor.Content]): ZIO[SymbolIndex & SymbolSearchGuard, Throwable, Unit] =
    Indexing.run(Indexing.Candidate(groupArtifactVersion, symbols)).unit

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
