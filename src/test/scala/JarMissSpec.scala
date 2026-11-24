import com.jamesward.zio_mavencentral.JarCache
import com.jamesward.zio_mavencentral.MavenCentral
import com.jamesward.zio_mavencentral.MavenCentral.*
import nl.vroste.rezilience.Bulkhead
import zio.*
import zio.direct.*
import zio.http.Client
import zio.test.*

import java.io.{File, FileOutputStream}
import java.nio.file.Files
import java.util.zip.{ZipEntry, ZipOutputStream}

/**
 * A cache miss that outlasts the request's wait answers a retryable error
 * fast instead of holding the request past Heroku's 30 s router timeout, and
 * the fetch keeps going so the retry is a hit (incident javadocs-29916434).
 */
object JarMissSpec extends ZIOSpecDefault:

  private val gav = GroupArtifactVersion(GroupId("com.example"), ArtifactId("slow"), Version("1.0.0"))

  private def writeJar(target: File): Unit =
    val out = ZipOutputStream(FileOutputStream(target))
    try
      out.putNextEntry(ZipEntry("index.html"))
      out.write("<html></html>".getBytes)
      out.closeEntry()
    finally out.close()

  /** A downloader that takes `delay` and counts its calls. */
  private def slowCache(delay: Duration, downloads: Ref[Int], missWait: Duration): ZIO[Scope, Nothing, Extractor.JavadocCache] =
    defer:
      val dir = ZIO.attemptBlockingIO(Files.createTempDirectory("jar-miss-spec").nn.toFile).orDie.run
      val download: (GroupArtifactVersion, File) => ZIO[Client & MavenCentralRepo, JarCache.DownloadError, JarMeta] =
        (_, target) =>
          (downloads.update(_ + 1) *> ZIO.attemptBlockingIO(writeJar(target)).orDie.delay(delay))
            .as(JarMeta(None, None))
      val cache  = JarCache.make(dir, download, label = "test").run
      val misses = Bulkhead.make(maxInFlightCalls = 2, maxQueueing = 2).run
      Extractor.JavadocCache(cache, misses, missWait)

  def spec = suite("JarMiss")(
    test("a slow miss fails fast as JarBusy and the fetch completes for the retry") {
      defer:
        val downloads = Ref.make(0).run
        val cache     = slowCache(2.seconds, downloads, 200.millis).run
        val first     = cache.get(gav).either.timed.run
        val (firstTook, firstResult) = first
        // The fetch keeps going after the first request gave up.
        cache.cache.get(gav).delay(3.seconds).run
        val retry = cache.get(gav).run
        val count = downloads.get.run
        assertTrue(
          firstResult.left.exists:
            case _: Extractor.JarBusy                 => true
            case _                                    => false
          ,
          firstTook < 1.second,
          retry.sizeBytes > 0L,
          count == 1,
        )
    },
    test("an interrupted request doesn't cancel the fetch") {
      defer:
        val downloads = Ref.make(0).run
        val cache     = slowCache(1.second, downloads, 10.seconds).run
        val request   = cache.get(gav).fork.run
        request.interrupt.delay(100.millis).run
        ZIO.sleep(2.seconds).run
        val retry = cache.get(gav).timed.run
        val count = downloads.get.run
        val (retryTook, handle) = retry
        assertTrue(handle.sizeBytes > 0L, retryTook < 500.millis, count == 1)
    },
    test("a fast miss returns the jar") {
      defer:
        val downloads = Ref.make(0).run
        val cache     = slowCache(10.millis, downloads, 5.seconds).run
        val handle    = cache.get(gav).run
        assertTrue(handle.sizeBytes > 0L)
    },
  ).provide(Client.default, MavenCentral.MavenCentralRepo.live, Scope.default) @@ TestAspect.withLiveClock @@ TestAspect.timeout(1.minute)
