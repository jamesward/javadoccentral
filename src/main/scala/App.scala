import com.jamesward.zio_http_guard.{BadActor, CrawlerLimiter}
import com.jamesward.zio_mavencentral.{JarCache, MavenCentral}
import zio.*
import zio.cache.{Cache, Lookup}
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.http.*
import zio.redis.{CodecSupplier, Redis, RedisConfig, RedisError}

import java.net.URI
import java.nio.file.Files

object App extends ZIOAppDefault:

  val server =
    ZLayer.fromZIO:
      defer:
        val system = ZIO.system.run
        val maybePort = system.env("PORT").run.flatMap(_.toIntOption)
        maybePort.fold(Server.default)(Server.defaultWithPort)
    .flatten

  // Wrap the default HTTP client with request logging so every outbound call
  // (Maven Central metadata lookups, javadoc/sources jar downloads, etc.)
  // emits a structured log line with method, URL, status, and duration.
  //
  // Caveat: for streaming responses (which `zio-mavencentral`'s
  // `downloadAndExtractZip` uses) `duration_ms` measures time-to-response-
  // headers, not end-of-body. Body-streaming + extraction time is still
  // covered by the `Downloaded javadoc/sources ... duration=...ms` log in
  // `Extractor.scala`; subtracting the two tells you body+extract wall time.
  val clientLayer: ZLayer[Any, Throwable, Client] =
    Client.default.update(_ @@ ZClientAspect.requestLogging(
      loggedRequestHeaders = Set(Header.UserAgent),
      loggedResponseHeaders = Set(Header.ContentLength, Header.ContentType)
    ))

  val latestCacheLayer: ZLayer[MavenCentral.MavenCentralRepo, Nothing, Extractor.LatestCache] = ZLayer.fromZIO:
    Cache.makeWith(1_000, Lookup(Extractor.latest)):
      case Exit.Success(_) => 1.hour
      case Exit.Failure(_) => Duration.Zero
    .map(Extractor.LatestCache(_))

  // Cached jars live on the dyno's ephemeral disk. Each cache entry owns one
  // immutable `.jar` file plus an open `ZipFile` handle; reads are random-
  // access into that handle. There is no eviction in normal operation —
  // Heroku's daily dyno restart is the natural reset, and we have far more
  // ephemeral disk (281 GB observed) than 24h of jars need (~25 GB worst-case).
  val jarCacheRoot: java.io.File = Files.createTempDirectory("jar-cache").nn.toFile

  val javadocCacheLayer: ZLayer[Client, Nothing, Extractor.JavadocCache] =
    ZLayer.scoped:
      JarCache.make(
        java.io.File(jarCacheRoot, "javadoc"),
        JarCache.httpDownloader(gav => MavenCentral.javadocUri(gav.groupId, gav.artifactId, gav.version)),
        label = "javadoc",
      ).map(new Extractor.JavadocCache(_))

  val sourcesCacheLayer: ZLayer[Client, Nothing, Extractor.SourcesCache] =
    ZLayer.scoped:
      JarCache.make(
        java.io.File(jarCacheRoot, "sources"),
        JarCache.httpDownloader(gav => MavenCentral.sourcesUri(gav.groupId, gav.artifactId, gav.version)),
        label = "sources",
      ).map(new Extractor.SourcesCache(_))

  val symbolSearchGuardLayer: ZLayer[Any, Nothing, SymbolSearch.SymbolSearchGuard] =
    ZLayer.fromZIO:
      ConcurrentMap.empty[MavenCentral.GroupArtifactVersion, Unit].map(SymbolSearch.SymbolSearchGuard(_))

  val redisUri: ZIO[Any, Throwable, URI] =
    ZIO.systemWith:
      system =>
        system.env("REDIS_URL")
          .someOrFail(new RuntimeException("REDIS_URL env var not set"))
          .map:
            redisUrl =>
              URI(redisUrl)

  val redisConfigLayer: ZLayer[Any, Throwable, RedisConfig] =
    ZLayer.fromZIO:
      defer:
        val uri = redisUri.run
        RedisConfig(uri.getHost, uri.getPort, ssl = true, verifyCertificate = false)

  // may not work with reconnects
  val redisAuthLayer: ZLayer[CodecSupplier & RedisConfig, Throwable, Redis] =
    Redis.singleNode.flatMap:
      env =>
        ZLayer.fromZIO:
          defer:
            val uri = redisUri.run
            val redis = env.get[Redis]
            val password = uri.getUserInfo.drop(1) // REDIS_URL has an empty username

            val authIfNeeded =
              redis.ping().catchAll:
                case e: RedisError if e.getMessage.contains("NOAUTH") =>
                  ZIO.logInfo("Redis NOAUTH detected, authenticating...") *> redis.auth(password)
                case e =>
                  ZIO.fail(e)

            redis.auth(password).run

            authIfNeeded.repeat(Schedule.spaced(5.seconds)).forkDaemon.run

            redis

  private def dirSize(dir: java.io.File): Long =
    val path = dir.toPath.nn
    if !Files.exists(path) then 0L
    else
      import java.nio.file.{Files as NioFiles, LinkOption}
      import java.nio.file.attribute.BasicFileAttributes
      var total = 0L
      val stream = NioFiles.walk(path).nn
      try
        stream.iterator.nn.forEachRemaining: p =>
          val attrs = NioFiles.readAttributes(p, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS).nn
          if attrs.isRegularFile then total += attrs.size()
      finally stream.close()
      total

  private def jvmMemStats: String =
    val mxBean = java.lang.management.ManagementFactory.getMemoryMXBean
    val heap = mxBean.getHeapMemoryUsage
    val nonHeap = mxBean.getNonHeapMemoryUsage
    val threadCount = java.lang.management.ManagementFactory.getThreadMXBean.getThreadCount
    val mb = (b: Long) => b / 1024 / 1024
    s"heap_used=${mb(heap.getUsed)}MB heap_committed=${mb(heap.getCommitted)}MB nonheap_used=${mb(nonHeap.getUsed)}MB nonheap_committed=${mb(nonHeap.getCommitted)}MB threads=$threadCount"

  private val logCacheStats: ZIO[Extractor.JavadocCache & Extractor.SourcesCache, Nothing, Unit] =
    defer:
      val javadocCacheSize  = ZIO.serviceWithZIO[Extractor.JavadocCache](_.cache.size).run
      val sourcesCacheSize  = ZIO.serviceWithZIO[Extractor.SourcesCache](_.cache.size).run
      val javadocCacheBytes = ZIO.serviceWithZIO[Extractor.JavadocCache](_.cache.totalBytes).run
      val sourcesCacheBytes = ZIO.serviceWithZIO[Extractor.SourcesCache](_.cache.totalBytes).run
      val totalMB = (javadocCacheBytes + sourcesCacheBytes) / 1024 / 1024
      ZIO.logInfo(s"cache stats: javadoc=$javadocCacheSize sources=$sourcesCacheSize disk=${totalMB}MB $jvmMemStats").run

  // How often to sample cache / memory stats for the logs.
  private val statsInterval: Duration = 1.minute

  // Use virtual threads for blocking operations. On JDK 21+, virtual threads
  // are lightweight (tiny stacks, mounted on a small carrier pool) and unmount
  // cleanly during blocking I/O, so we avoid the pthread_create EAGAIN risk of
  // the default ZIO cached thread pool without artificially capping concurrency.
  // Must be applied via `bootstrap` so fibers forked by the server inherit the
  // FiberRef.
  override val bootstrap =
    Runtime.enableLoomBasedBlockingExecutor

  def run =
    // `JarCache` is append-only for the dyno's lifetime; ZipFile handles
    // are released when the cache layer's scope closes at app shutdown.
    val background =
      logCacheStats.repeat(Schedule.spaced(statsInterval)).forkDaemon
    (background *> Server.serve(Web.appWithMiddleware)).provide(
      server,
      clientLayer,
      MavenCentral.MavenCentralRepo.live,
      latestCacheLayer,
      javadocCacheLayer,
      sourcesCacheLayer,
      redisConfigLayer,
      redisAuthLayer,
      ZLayer.succeed[CodecSupplier](SymbolSearch.ProtobufCodecSupplier),
      SymbolSearch.herokuInferenceLayer,
      BadActor.live,
      CrawlerLimiter.layer[MavenCentral.GroupArtifactVersion],
      symbolSearchGuardLayer,
    )
