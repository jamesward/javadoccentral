import com.jamesward.zio_mavencentral.MavenCentral
import zio.*
import zio.cache.{ScopedCache, ScopedLookup}
import zio.direct.*
import zio.test.*

import java.io.File
import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * Tests for the disk-backed cache implementation.
 *
 * The cache is backed by `zio.cache.ScopedCache`: each entry owns a `Scope`
 * whose finalizer deletes the extracted directory. Eviction — whether
 * triggered by capacity overflow, TTL expiry, or explicit invalidation —
 * closes the scope, which runs the finalizer. Per-entry reference counting
 * inside `ScopedCache` ensures a concurrent reader's directory is not
 * deleted while still in use.
 */
object DiskCacheEvictionSpec extends ZIOSpecDefault:

  private def recursivelyDelete(f: File): Unit =
    if f.isDirectory then
      val cs = f.listFiles
      if cs != null then
        var i = 0
        while i < cs.length do
          val c = cs(i)
          if c != null then recursivelyDelete(c)
          i += 1
    f.delete()
    ()

  private def countMarkers(tmp: File): ZIO[Any, Nothing, Int] =
    ZIO.attempt:
      import java.nio.file.Files as NioFiles
      val stream = NioFiles.walk(tmp.toPath).nn
      try
        stream.iterator.nn.asScala.count(p => p.getFileName.nn.toString == "marker.txt")
      finally stream.close()
    .orDie

  def spec = suite("DiskCache eviction")(

    test("exceeding cache capacity evicts the oldest entry's directory from disk") {
      // Reproduces the production disk-leak bug: without finalizer-based
      // cleanup, zio-cache silently removed the LRU entry from its internal
      // map but left the extracted directory on disk. `ScopedCache` wires
      // the Scope to the cache entry so eviction runs the finalizer and
      // deletes the directory.
      val capacity = 2
      val gavs = (1 to 5).toList.map(i => Extractor.gav("g", s"a$i", "1"))

      val body = defer:
        val tmp = ZIO.service[Extractor.TmpDir].run.dir
        ZIO.addFinalizer(ZIO.attempt(recursivelyDelete(tmp)).ignore).run

        // Pre-create the extraction dirs so `Extractor.javadoc` skips the download.
        ZIO.foreachDiscard(gavs): g =>
          ZIO.attempt:
            val d = File(tmp, g.toString)
            d.mkdirs()
            Files.writeString(File(d, "marker.txt").toPath, g.toString)
            ()
          .orDie
        .run

        val cache = ScopedCache.makeWith(capacity, ScopedLookup(Extractor.javadoc)):
          case Exit.Success(_) => Duration.Infinity
          case Exit.Failure(_) => Duration.Zero
        .run
        val jc = Extractor.JavadocCache(cache)

        // Pull each GAV through the cache inside its own scope, so each
        // caller releases its owner reference after reading. Once we exceed
        // capacity, the oldest entry is evicted — and its finalizer deletes
        // the directory.
        ZIO.foreachDiscard(gavs): g =>
          ZIO.scoped:
            jc.getDir(g).unit
          .catchAll(e => ZIO.dieMessage(s"unexpected error: $e"))
        .run

        val cachedEntries = cache.size.run
        val markersOnDisk = countMarkers(tmp).run

        assertTrue(
          cachedEntries == capacity,
          // Only the entries still in the cache should remain on disk.
          markersOnDisk == capacity,
        )

      body.provide(
        ZLayer.fromZIO(
          ZIO.attempt(Files.createTempDirectory("cap-overflow-test").nn.toFile).orDie.map(Extractor.TmpDir(_))
        ),
        App.fetchBlockerLayer,
        zio.http.Client.default,
        Scope.default,
      )
    },

    test("invalidating a cache entry deletes its directory") {
      val gav = Extractor.gav("g", "a", "1")

      val body = defer:
        val tmp = ZIO.service[Extractor.TmpDir].run.dir
        ZIO.addFinalizer(ZIO.attempt(recursivelyDelete(tmp)).ignore).run

        // Pre-create the extraction dir.
        val gavDir = File(tmp, gav.toString)
        ZIO.attempt:
          gavDir.mkdirs()
          Files.writeString(File(gavDir, "marker.txt").toPath, "hello")
          ()
        .orDie.run

        val cache = ScopedCache.makeWith(10, ScopedLookup(Extractor.javadoc)):
          case Exit.Success(_) => Duration.Infinity
          case Exit.Failure(_) => Duration.Zero
        .run
        val jc = Extractor.JavadocCache(cache)

        // Populate the cache.
        ZIO.scoped(jc.getDir(gav).unit).run
        val existedBefore = gavDir.exists()

        // Explicit invalidation should also delete the dir.
        cache.invalidate(gav).run
        val existedAfter = gavDir.exists()

        assertTrue(existedBefore, !existedAfter)

      body.provide(
        ZLayer.fromZIO(
          ZIO.attempt(Files.createTempDirectory("invalidate-test").nn.toFile).orDie.map(Extractor.TmpDir(_))
        ),
        App.fetchBlockerLayer,
        zio.http.Client.default,
        Scope.default,
      )
    },

    test("concurrent lookups for the same key run the scoped lookup only once") {
      // Validates that ScopedCache deduplicates concurrent lookups via its
      // `Pending` state — the behavior that replaced the explicit FetchBlocker
      // (ConcurrentMap[GAV, Promise]) pattern.
      val gav = Extractor.gav("g", "a", "1")
      val fibers = 20

      val body = defer:
        val tmp = ZIO.service[Extractor.TmpDir].run.dir
        ZIO.addFinalizer(ZIO.attempt(recursivelyDelete(tmp)).ignore).run

        val callCount = Ref.make(0).run

        // A custom lookup that counts invocations and produces a temp dir.
        // Intentionally slow so all fibers race on the Pending state.
        val lookup: ScopedLookup[MavenCentral.GroupArtifactVersion, Any, MavenCentral.NotFoundError, File] =
          ScopedLookup: key =>
            defer:
              callCount.update(_ + 1).run
              ZIO.sleep(100.millis).run
              val dir = File(tmp, key.toString)
              ZIO.attempt:
                dir.mkdirs()
                Files.writeString(File(dir, "marker.txt").toPath, "hello")
                ()
              .orDie.run
              ZIO.addFinalizer(ZIO.attempt(recursivelyDelete(dir)).ignore).run
              dir

        val cache = ScopedCache.makeWith(10, lookup):
          case Exit.Success(_) => Duration.Infinity
          case Exit.Failure(_) => Duration.Zero
        .run

        // Race `fibers` concurrent calls for the same key.
        ZIO.foreachPar(1 to fibers): _ =>
          ZIO.scoped(cache.get(gav).unit)
        .run

        val observedCalls = callCount.get.run

        assertTrue(observedCalls == 1)

      body.provide(
        ZLayer.fromZIO(
          ZIO.attempt(Files.createTempDirectory("concurrent-test").nn.toFile).orDie.map(Extractor.TmpDir(_))
        ),
        Scope.default,
      )
    },

  ) @@ TestAspect.withLiveRandom @@ TestAspect.withLiveClock
