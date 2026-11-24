import com.jamesward.zio_http_guard.guard.Guard
import com.jamesward.zio_mavencentral.MavenCentral
import zio.*
import zio.direct.*
import zio.http.*
import zio.test.*

/**
 * When Maven Central can't be reached (every mirror answers 503), resolving an
 * artifact's latest version is a temporary failure: a retryable 503 or a
 * worded "retry" message, never a 500 or an ERROR log, and nothing that
 * caches the failure.
 */
object LatestUnavailableSpec extends ZIOSpecDefault:

  private val ip = Header.Custom("X-Forwarded-For", "192.168.7.71")

  /** A Maven Central mirror that answers every request with 503. */
  private val downRepo: ZLayer[Client & Server, Nothing, MavenCentral.MavenCentralRepo] =
    ZLayer.fromZIO(Server.install(Routes(Method.ANY / trailing -> Handler.status(Status.ServiceUnavailable)))).flatMap: port =>
      MavenCentral.MavenCentralRepo.custom(mirrors = List(MavenCentral.Mirror(URL.decode(s"http://localhost:${port.get}/maven2/").toOption.get, authoritative = true, MavenCentral.Listings.Html)))

  private def get(path: Path, headers: Header*): ZIO[Scope & Guard & MavenCache & Extractor.JavadocCache & Extractor.SourcesCache & Extractor.LatestCache & Client & MavenCentral.MavenCentralRepo & SymbolIndex & SymbolSearch.HerokuInference & SymbolSearch.SymbolSearchGuard, Nothing, (Response, String)] =
    Web.appWithMiddleware.runZIO(Request.get(URL(path)).addHeaders(Headers(ip +: headers))).flatMap(r => r.body.asString.orDie.map(r -> _))

  def spec = suite("LatestUnavailable")(
    test("latest, its JSON, the badge and the API say retry") {
      defer:
        val (redirect, _)   = get(Path.root / "com.foo" / "bar" / "latest").run
        val (json, _)       = get(Path.root / "com.foo" / "bar" / "latest", Header.Accept(MediaType.application.json)).run
        val (badge, _)      = get(Path.root / "com.foo" / "bar" / "badge.svg").run
        val (api2, apiBody2) = Web.appWithMiddleware.runZIO(
          Request.get(URL(Path.root / "api" / "latest-version", queryParams = QueryParams("groupId" -> "com.foo", "artifactId" -> "bar"))).addHeader(ip)
        ).flatMap(r => r.body.asString.orDie.map(r -> _)).run
        val logs   = ZTestLogger.logOutput.run
        val errors = logs.filter(_.logLevel.syslog == LogLevel.Error.syslog).map(_.message())
        val served = logs.count(_.message() == "Http request served")
        assertTrue(
          redirect.status.code == 503,
          redirect.header(Header.RetryAfter).isDefined,
          json.status.code == 503,
          badge.status.isRedirection,
          badge.header(Header.Location).exists(_.renderedValue.contains("unavailable")),
          badge.header(Header.CacheControl).exists(_.renderedValue.contains("no-store")),
          api2.status.code < 500,
          apiBody2.contains("retry"),
          served > 0, // the test logger sees the app's logs
          errors.isEmpty,
        )
    },
  ).provide(
    Server.defaultWith(_.onAnyOpenPort),
    Client.default,
    downRepo,
    App.javadocCacheLayer,
    App.sourcesCacheLayer,
    App.latestCacheLayer,
    PostgresContainer.freshDatabase,
    SymbolIndex.layer,
    MavenCache.layer,
    MockInference.layer,
    JavadocsGuard.layer,
    App.symbolSearchGuardLayer,
    Scope.default,
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
