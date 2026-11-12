import com.jamesward.zio_mavencentral.{JarCache, MavenCentral}
import nl.vroste.rezilience.Bulkhead
import zio.*
import zio.cache.{Cache, Lookup}
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.http.*

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
      level = _ => LogLevel.Debug,
      loggedRequestHeaders = Set(Header.UserAgent),
      loggedResponseHeaders = Set(Header.ContentLength, Header.ContentType)
    ))

  val latestCacheLayer: ZLayer[MavenCentral.MavenCentralRepo, Nothing, Extractor.LatestCache] = ZLayer.fromZIO:
    // The web UI's `/latest` and badges resolve to the latest *release* (no pre-releases).
    Cache.makeWith(1_000, Lookup((ga: MavenCentral.GroupArtifact) => Extractor.latest(ga))):
      case Exit.Success(_) => 1.hour
      case Exit.Failure(_) => Duration.Zero
    .map(Extractor.LatestCache(_))

  // A warm entry holds an open `ZipFile` (its central directory is in heap).
  // A crawler walking every version of an artifact touched ~7,700 jars in one
  // dyno lifetime and kept them all warm for the old 30-minute default, which
  // ran the heap out. Demote after 2 idle minutes; evict from disk after 6
  // idle hours so the cache map and disk stay bounded too.
  private val jarWarmIdleTtl: Duration   = 2.minutes
  private val jarColdIdleTtl: Duration   = 6.hours
  private val jarSweepInterval: Duration = 30.seconds

  // Cache-miss jar fetches running at once, per cache, and how many more may
  // wait; beyond that a miss gets a 503. See `Extractor.boundedGet`.
  private val maxConcurrentJarFetches = 8
  private val maxQueuedJarFetches     = 16

  // Cached jars live on the dyno's ephemeral disk. Each cache entry owns one
  // immutable `.jar` file plus an open `ZipFile` handle; reads are random-
  // access into that handle (see the TTLs above for eviction).
  // Each layer instance owns a distinct directory. This matters in tests, where
  // multiple suites construct cache layers concurrently in one JVM: a shared
  // directory would let independent JarCache instances write/delete the same
  // GAV filename without sharing a single-flight map.
  val javadocCacheLayer: ZLayer[Client, Nothing, Extractor.JavadocCache] =
    ZLayer.scoped:
      defer:
        val cacheDir = ZIO.attemptBlockingIO(Files.createTempDirectory("jar-cache-javadoc").nn.toFile).orDie.run
        val cache = JarCache.make(
          cacheDir,
          JarCache.httpDownloader(gav => MavenCentral.javadocUri(gav.groupId, gav.artifactId, gav.version)),
          label = "javadoc",
          warmIdleTtl = jarWarmIdleTtl,
          coldIdleTtl = Some(jarColdIdleTtl),
          sweepInterval = jarSweepInterval,
        ).run
        val misses = Bulkhead.make(maxInFlightCalls = maxConcurrentJarFetches, maxQueueing = maxQueuedJarFetches).run
        new Extractor.JavadocCache(cache, misses)

  val sourcesCacheLayer: ZLayer[Client, Nothing, Extractor.SourcesCache] =
    ZLayer.scoped:
      defer:
        val cacheDir = ZIO.attemptBlockingIO(Files.createTempDirectory("jar-cache-sources").nn.toFile).orDie.run
        val cache = JarCache.make(
          cacheDir,
          JarCache.httpDownloader(gav => MavenCentral.sourcesUri(gav.groupId, gav.artifactId, gav.version)),
          label = "sources",
          warmIdleTtl = jarWarmIdleTtl,
          coldIdleTtl = Some(jarColdIdleTtl),
          sweepInterval = jarSweepInterval,
        ).run
        val misses = Bulkhead.make(maxInFlightCalls = maxConcurrentJarFetches, maxQueueing = maxQueuedJarFetches).run
        new Extractor.SourcesCache(cache, misses)

  val symbolSearchGuardLayer: ZLayer[Any, Nothing, SymbolSearch.SymbolSearchGuard] =
    ZLayer.fromZIO:
      ConcurrentMap.empty[MavenCentral.GroupArtifact, Unit].map(SymbolSearch.SymbolSearchGuard(_))

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

  private def jvmMemAnnotations: List[LogAnnotation] =
    val mxBean = java.lang.management.ManagementFactory.getMemoryMXBean
    val heap = mxBean.getHeapMemoryUsage
    val nonHeap = mxBean.getNonHeapMemoryUsage
    val threadCount = java.lang.management.ManagementFactory.getThreadMXBean.getThreadCount
    val mb = (b: Long) => b / 1024 / 1024
    List(
      LogAnnotation("heapUsedMb", mb(heap.getUsed).toString),
      LogAnnotation("heapCommittedMb", mb(heap.getCommitted).toString),
      LogAnnotation("nonheapUsedMb", mb(nonHeap.getUsed).toString),
      LogAnnotation("nonheapCommittedMb", mb(nonHeap.getCommitted).toString),
      LogAnnotation("threads", threadCount.toString),
    )

  private val logCacheStats: ZIO[Extractor.JavadocCache & Extractor.SourcesCache, Nothing, Unit] =
    defer:
      val javadocCacheSize  = ZIO.serviceWithZIO[Extractor.JavadocCache](_.cache.size).run
      val sourcesCacheSize  = ZIO.serviceWithZIO[Extractor.SourcesCache](_.cache.size).run
      val javadocCacheBytes = ZIO.serviceWithZIO[Extractor.JavadocCache](_.cache.totalBytes).run
      val sourcesCacheBytes = ZIO.serviceWithZIO[Extractor.SourcesCache](_.cache.totalBytes).run
      val totalMB = (javadocCacheBytes + sourcesCacheBytes) / 1024 / 1024
      val javadocWarm   = ZIO.serviceWithZIO[Extractor.JavadocCache](_.cache.warmCount).run
      val javadocFetch  = ZIO.serviceWithZIO[Extractor.JavadocCache](_.misses.metrics).run
      val sourcesFetch  = ZIO.serviceWithZIO[Extractor.SourcesCache](_.misses.metrics).run
      val statsAnnotations = List(
        LogAnnotation("javadocEntries", javadocCacheSize.toString),
        LogAnnotation("javadocWarm", javadocWarm.toString),
        LogAnnotation("sourcesEntries", sourcesCacheSize.toString),
        LogAnnotation("diskMb", totalMB.toString),
        LogAnnotation("javadocFetchInFlight", javadocFetch.inFlight.toString),
        LogAnnotation("javadocFetchQueued", javadocFetch.inQueue.toString),
        LogAnnotation("sourcesFetchInFlight", sourcesFetch.inFlight.toString),
        LogAnnotation("sourcesFetchQueued", sourcesFetch.inQueue.toString),
      ) ++ jvmMemAnnotations
      ZIO.logAnnotate(statsAnnotations.head, statsAnnotations.tail*)(ZIO.logInfo("cache stats")).run

  // How often to sample cache / memory stats for the logs.
  private val statsInterval: Duration = 1.minute

  // Use virtual threads for blocking operations. On JDK 21+, virtual threads
  // are lightweight (tiny stacks, mounted on a small carrier pool) and unmount
  // cleanly during blocking I/O, so we avoid the pthread_create EAGAIN risk of
  // the default ZIO cached thread pool without artificially capping concurrency.
  // Must be applied via `bootstrap` so fibers forked by the server inherit the
  // FiberRef. `AppLogging.json` installs structured (JSON) logging at an INFO
  // floor for prod — see AppLogging.scala.
  override val bootstrap =
    AppLogging.json ++ Runtime.enableLoomBasedBlockingExecutor

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
      Db.fromEnv,
      SymbolIndex.layer,
      SymbolSearch.herokuInferenceLayer,
      JavadocsGuard.layer,
      symbolSearchGuardLayer,
    )
