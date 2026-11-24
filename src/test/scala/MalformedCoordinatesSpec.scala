import com.jamesward.zio_http_guard.guard.Guard
import com.jamesward.zio_mavencentral.MavenCentral
import zio.*
import zio.direct.*
import zio.http.*
import zio.test.*

/**
 * Coordinates Maven Central answers with 400 rather than 404 (issue #270): a
 * NUL or invalid UTF-8 in a segment, or a segment over ~1,100 characters.
 * Every surface must answer them as "not found" (a 4xx with a worded message,
 * or a redirect to the artifact page), never a 500 or an ERROR log.
 */
object MalformedCoordinatesSpec extends ZIOSpecDefault:

  private val ip = Header.Custom("X-Forwarded-For", "192.168.7.70")

  private val malformed: List[(String, String)] = List(
    "NUL"          -> "x\u0000",
    "invalid UTF-8" -> "x\uFFFD\u0000",
    "long segment" -> ("a" * 1200),
  )

  private def api(endpoint: String, params: (String, String)*): Request =
    Request.get(URL(Path.root / "api" / endpoint, queryParams = QueryParams(params.head, params.tail*))).addHeader(ip)

  private def browse(segments: String*): Request =
    Request.get(URL(segments.foldLeft(Path.root)(_ / _))).addHeader(ip)

  /** Every surface that sends coordinates to Maven Central, with the artifactId replaced by `bad`. */
  private def requests(bad: String): List[(String, Request)] = List(
    "api latest-version"  -> api("latest-version", "groupId" -> "com.foo", "artifactId" -> bad),
    "api javadoc-index"   -> api("javadoc-index", "groupId" -> "com.foo", "artifactId" -> bad, "version" -> "1.0"),
    "api list-javadoc-symbols" -> api("list-javadoc-symbols", "groupId" -> "com.foo", "artifactId" -> bad, "version" -> "1.0"),
    "api list-source-files" -> api("list-source-files", "groupId" -> "com.foo", "artifactId" -> bad, "version" -> "1.0"),
    "browse artifact"     -> browse("com.foo", bad),
    "browse latest"       -> browse("com.foo", bad, "latest"),
    "browse version"      -> browse("com.foo", bad, "1.0"),
    "browse file"         -> browse("com.foo", bad, "1.0", "index.html"),
    "browse group"        -> browse(bad),
  )

  def spec = suite("MalformedCoordinates")(
    malformed.map: (label, bad) =>
      test(s"$label: no surface answers 5xx") {
        defer:
          val results = ZIO.foreach(requests(bad)): (name, request) =>
            Web.appWithMiddleware.runZIO(request).flatMap(r => r.body.asString.map(b => (name, r.status.code, b.take(160))))
          .run
          ZIO.foreachDiscard(results)((name, status, body) => ZIO.logInfo(s"malformed $label: $name -> $status $body")).run
          val logs   = ZTestLogger.logOutput.run
          val errors = logs.filter(_.logLevel.syslog == LogLevel.Error.syslog).map(_.message())
          val served = logs.count(_.message() == "Http request served")
          assertTrue(results.forall((_, status, _) => status < 500), served > 0, errors.isEmpty)
      } @@ MavenCentralRetry.aspect
  ).provide(
    App.javadocCacheLayer,
    App.sourcesCacheLayer,
    App.latestCacheLayer,
    LoggingClient.layer,
    MavenCentral.MavenCentralRepo.live,
    PostgresContainer.freshDatabase,
    SymbolIndex.layer,
    MavenCache.layer,
    MockInference.layer,
    ZLayer.scopedEnvironment(
      ZIO.withConfigProvider(ConfigProvider.fromMap(Map("GUARD_NEW_KEYS_PER_CLIENT" -> "10000")).orElse(ConfigProvider.defaultProvider)):
        JavadocsGuard.layer.build
    ) : ZLayer[Extractor.JavadocCache, Config.Error, Guard],
    App.symbolSearchGuardLayer,
    Scope.default,
  ) @@ TestAspect.withLiveClock @@ TestAspect.withLiveRandom @@ TestAspect.withLiveSystem @@ TestAspect.sequential
