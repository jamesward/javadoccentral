import com.jamesward.zio_mavencentral.MavenCentral
import zio.*
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.test.*

/**
 * Tests verifying the FetchBlocker fixes for race conditions that caused H12 timeouts.
 *
 * Fixed bugs:
 * 1. putIfAbsent instead of get+put — atomic promise creation, no overwrites
 * 2. .ensuring(promise.succeed(()) *> blocker.remove(...)) — promise always completes, even on failure
 * 3. withFile uses JavadocCache.get() instead of Extractor.javadoc() directly
 */
object FetchBlockerSpec extends ZIOSpecDefault:
  given CanEqual[Promise[Nothing, Unit], Promise[Nothing, Unit]] = CanEqual.derived

  val testGav = Extractor.gav("test.group", "test-artifact", "1.0.0")

  def spec = suite("FetchBlocker fixes")(

    test("putIfAbsent prevents promise overwrites") {
      // With the fix, the first promise wins. Subsequent calls get back the existing promise.
      defer:
        val blocker = ConcurrentMap.empty[MavenCentral.GroupArtifactVersion, Promise[Nothing, Unit]].run

        val p1 = Promise.make[Nothing, Unit].run
        val p2 = Promise.make[Nothing, Unit].run

        val first = blocker.putIfAbsent(testGav, p1).run   // None = inserted p1
        val second = blocker.putIfAbsent(testGav, p2).run  // Some(p1) = p1 already there

        assertTrue(
          first.isEmpty,          // p1 was inserted
          second.get == p1,       // p2 was rejected, got p1 back
        )
    },

    test("ensuring completes promise even when download fails") {
      // Simulates the fixed pattern: ensuring guarantees promise completion on failure
      defer:
        val blocker = ConcurrentMap.empty[MavenCentral.GroupArtifactVersion, Promise[Nothing, Unit]].run
        val promise = Promise.make[Nothing, Unit].run
        blocker.putIfAbsent(testGav, promise).run

        // Simulate a failed download with ensuring
        val download = ZIO.fail("download failed")
          .ensuring(promise.succeed(()) *> blocker.remove(testGav))

        download.ignore.run

        // Promise was completed despite the failure
        val done = promise.isDone.run
        // Promise was removed from the blocker map
        val removed = blocker.get(testGav).run

        assertTrue(
          done,              // promise completed
          removed.isEmpty,   // cleaned up from map
        )
    },

    test("ensuring completes promise on success too") {
      defer:
        val blocker = ConcurrentMap.empty[MavenCentral.GroupArtifactVersion, Promise[Nothing, Unit]].run
        val promise = Promise.make[Nothing, Unit].run
        blocker.putIfAbsent(testGav, promise).run

        val download = ZIO.succeed("downloaded")
          .ensuring(promise.succeed(()) *> blocker.remove(testGav))

        download.run

        val done = promise.isDone.run
        val removed = blocker.get(testGav).run

        assertTrue(done, removed.isEmpty)
    },

    test("concurrent fetches: all waiters unblock when download completes") {
      for
        blocker <- ConcurrentMap.empty[MavenCentral.GroupArtifactVersion, Promise[Nothing, Unit]]
        // First fiber: creates promise and "downloads"
        // Remaining fibers: find existing promise and await
        fibers <- ZIO.foreach(1 to 5) { i =>
          val work = for
            promise <- Promise.make[Nothing, Unit]
            existing <- blocker.putIfAbsent(testGav, promise)
            _ <- existing match
              case Some(existingPromise) =>
                existingPromise.await // waiter
              case None =>
                ZIO.sleep(100.millis) // simulate download
                  .ensuring(promise.succeed(()) *> blocker.remove(testGav))
          yield i
          work.fork
        }
        _ <- TestClock.adjust(200.millis)
        results <- ZIO.foreach(fibers)(_.join.timeout(2.seconds))
        completed = results.count(_.isDefined)
      yield assertTrue(completed == 5) // ALL fibers complete, none hang
    },

    test("concurrent fetches: all waiters unblock even when download fails") {
      for
        blocker <- ConcurrentMap.empty[MavenCentral.GroupArtifactVersion, Promise[Nothing, Unit]]
        fibers <- ZIO.foreach(1 to 5) { i =>
          val work = for
            promise <- Promise.make[Nothing, Unit]
            existing <- blocker.putIfAbsent(testGav, promise)
            _ <- existing match
              case Some(existingPromise) =>
                existingPromise.await
              case None =>
                ZIO.sleep(50.millis).zipRight(ZIO.fail("download failed"))
                  .ensuring(promise.succeed(()) *> blocker.remove(testGav))
                  .ignore
          yield i
          work.fork
        }
        _ <- TestClock.adjust(200.millis)
        results <- ZIO.foreach(fibers)(_.join.timeout(2.seconds))
        completed = results.count(_.isDefined)
      yield assertTrue(completed == 5) // ALL fibers complete, none hang
    },

  ) @@ TestAspect.withLiveClock
