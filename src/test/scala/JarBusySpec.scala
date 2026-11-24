import com.jamesward.zio_http_guard.guard.Guard
import com.jamesward.zio_mavencentral.JarCache
import com.jamesward.zio_mavencentral.MavenCentral
import com.jamesward.zio_mavencentral.MavenCentral.*
import nl.vroste.rezilience.Bulkhead
import zio.*
import zio.direct.*
import zio.http.*
import zio.test.*

import java.io.{File, FileOutputStream}
import java.nio.file.Files
import java.util.zip.{ZipEntry, ZipOutputStream}

/**
 * When the jar-fetch bulkhead is full (incident #271: a crawler burst of
 * uncached version pages), requests for other uncached jars get a fast 503
 * that says javadocs.dev is busy, not that Maven Central is down, and each
 * one is logged and counted.
 */
object JarBusySpec extends ZIOSpecDefault:

  private val ip = Header.Custom("X-Forwarded-For", "192.168.7.72")

  private def writeJar(target: File): Unit =
    val out = ZipOutputStream(FileOutputStream(target))
    try
      out.putNextEntry(ZipEntry("index.html"))
      out.write("<html></html>".getBytes)
      out.closeEntry()
    finally out.close()

  /** A javadoc cache whose single fetch slot (no queue) is held by `slow` until `release` completes. */
  private def cacheLayer(release: Promise[Nothing, Unit]): ZLayer[Any, Nothing, Extractor.JavadocCache] =
    ZLayer.scoped:
      defer:
        val dir = ZIO.attemptBlockingIO(Files.createTempDirectory("jar-busy-spec").nn.toFile).orDie.run
        val download: (GroupArtifactVersion, File) => ZIO[Client & MavenCentralRepo, JarCache.DownloadError, JarMeta] =
          (g, target) =>
            val wait = if g.artifactId.toString == "slow" then release.await else ZIO.unit
            (wait *> ZIO.attemptBlockingIO(writeJar(target)).orDie).as(JarMeta(None, None))
        val cache  = JarCache.make(dir, download, label = "javadoc").run
        val misses = Bulkhead.make(maxInFlightCalls = 1, maxQueueing = 0).run
        Extractor.JavadocCache(cache, misses)

  private def get(path: Path): ZIO[Scope & Guard & MavenCache & Extractor.JavadocCache & Extractor.SourcesCache & Extractor.LatestCache & Client & MavenCentralRepo & SymbolIndex & SymbolSearch.HerokuInference & SymbolSearch.SymbolSearchGuard, Nothing, (Response, String)] =
    Web.appWithMiddleware.runZIO(Request.get(URL(path)).addHeader(ip)).flatMap(r => r.body.asString.orDie.map(r -> _))


  def spec = suite("JarBusy")(
    test("a full bulkhead answers a fast, honest 503, logs it and counts it") {
      defer:
        val cache = ZIO.service[Extractor.JavadocCache].run
        // Hold the only fetch slot.
        val holder = cache.get(gav("com.example", "slow", "1.0")).fork.run
        cache.misses.metrics.repeatUntil(_.inFlight == 1).run
        val (took, (versionPage, versionBody)) = get(Path.root / "com.example" / "other" / "1.0").timed.run
        val (file, fileBody) = get(Path.root / "com.example" / "other" / "1.0" / "index.html").run
        val (apiResp, apiBody) = Web.appWithMiddleware.runZIO(
          Request.get(URL(Path.root / "api" / "javadoc-index", queryParams = QueryParams("groupId" -> "com.example", "artifactId" -> "other", "version" -> "1.0"))).addHeader(ip)
        ).flatMap(r => r.body.asString.orDie.map(r -> _)).run
        val counted = cache.busy.sum()
        val logs    = ZTestLogger.logOutput.run
        val busyLogs = logs.filter(_.message() == "Jar fetch busy")
        val errors  = logs.filter(_.logLevel.syslog == LogLevel.Error.syslog)
        holder.interrupt.run
        assertTrue(
          versionPage.status.code == 503,
          versionPage.header(Header.RetryAfter).isDefined,
          versionBody.contains("javadocs.dev is busy"),
          !versionBody.contains("Maven Central"),
          took < 2.seconds,
          file.status.code == 503,
          fileBody.contains("javadocs.dev is busy"),
          apiBody.contains("javadocs.dev is busy"),
          counted >= 3L,
          busyLogs.size.toLong == counted,
          busyLogs.forall(_.annotations.get("gav").contains("com.example/other/1.0")),
          busyLogs.forall(_.annotations.get("client_ip").contains("192.168.7.72")),
          errors.isEmpty,
        )
    },
  ).provide(
    ZLayer.fromZIO(Promise.make[Nothing, Unit]).flatMap(p => cacheLayer(p.get)),
    App.sourcesCacheLayer,
    App.latestCacheLayer,
    Client.default,
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
  ) @@ TestAspect.withLiveClock @@ TestAspect.timeout(2.minutes)
