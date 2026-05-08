import com.jamesward.zio_mavencentral.MavenCentral
import zio.*
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.test.*

/**
 * Tests the per-crawler GAV limiter in isolation. The limiter is independent
 * of the disk cache's eviction: a crawler's slot is released by time alone,
 * based on inactivity since its last request for that slot's GAV.
 */
object CrawlerGavLimiterSpec extends ZIOSpecDefault:

  private val gav1 = MavenCentral.gav("g", "one", "1")
  private val gav2 = MavenCentral.gav("g", "two", "1")
  private val crawler = "googlebot"
  private val hold = 10.minutes

  private val makeLimiter: ZIO[Any, Nothing, Web.CrawlerGavLimiter] =
    ConcurrentMap.empty[String, Web.CrawlerGavSlot].map(Web.CrawlerGavLimiter(_))

  def spec = suite("CrawlerGavLimiter")(

    test("first request for a crawler claims the slot") {
      defer:
        val limiter = makeLimiter.run
        val allowed = limiter.tryClaim(crawler, gav1, hold).run
        assertTrue(allowed)
    },

    test("same crawler requesting the same GAV keeps getting allowed") {
      defer:
        val limiter = makeLimiter.run
        val first = limiter.tryClaim(crawler, gav1, hold).run
        val second = limiter.tryClaim(crawler, gav1, hold).run
        val third = limiter.tryClaim(crawler, gav1, hold).run
        assertTrue(first, second, third)
    },

    test("same crawler requesting a different GAV gets denied while slot is active") {
      defer:
        val limiter = makeLimiter.run
        val first = limiter.tryClaim(crawler, gav1, hold).run
        val otherGav = limiter.tryClaim(crawler, gav2, hold).run
        assertTrue(first, !otherGav)
    },

    test("different crawlers have independent slots") {
      defer:
        val limiter = makeLimiter.run
        val bot1Gav1 = limiter.tryClaim("bot1", gav1, hold).run
        val bot2Gav2 = limiter.tryClaim("bot2", gav2, hold).run
        assertTrue(bot1Gav1, bot2Gav2)
    },

    test("slot is released after hold duration of inactivity") {
      defer:
        val limiter = makeLimiter.run
        limiter.tryClaim(crawler, gav1, hold).run
        // Blocked on a different GAV while slot is active
        val beforeHold = limiter.tryClaim(crawler, gav2, hold).run
        TestClock.adjust(hold + 1.second).run
        // After hold duration, crawler can move to a new GAV
        val afterHold = limiter.tryClaim(crawler, gav2, hold).run
        assertTrue(!beforeHold, afterHold)
    },

    test("active requests for the same GAV refresh the hold window") {
      defer:
        val limiter = makeLimiter.run
        limiter.tryClaim(crawler, gav1, hold).run
        // Keep hitting the same GAV with advances that stay inside the hold
        TestClock.adjust(hold.minus(1.second)).run
        limiter.tryClaim(crawler, gav1, hold).run // refresh
        TestClock.adjust(hold.minus(1.second)).run
        // Total elapsed is ~2 * (hold - 1s), well past `hold`, but the
        // refresh means the slot is still held. A different GAV should
        // still be denied.
        val blocked = limiter.tryClaim(crawler, gav2, hold).run
        assertTrue(!blocked)
    },

  )
