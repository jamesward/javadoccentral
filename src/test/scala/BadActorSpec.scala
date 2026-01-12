import BadActor.Status.*
import zio.*
import zio.direct.*
import zio.test.*

import java.time.Instant

object BadActorSpec extends ZIOSpecDefault:

  def spec = suite("BadActor")(
    test("user with 5 suspect requests in last 10 seconds gets banned"):
      defer:
        val ips = List("192.168.1.1")
        val baseTime = Instant.now()

        // Make 5 suspect requests within 10 seconds (at seconds 0, 1, 2, 3, 4)
        val instants = (0 until 5).map(i => baseTime.plusSeconds(i.toLong)).toList
        val results = ZIO.foreach(instants)(instant => BadActor.checkReq(ips, instant, suspect = true)).run

        // 6th request should trigger ban check
        val finalResult = BadActor.checkReq(ips, baseTime.plusSeconds(5), suspect = true).run

        assertTrue(
          results.forall(_ == Allowed),
          finalResult == Banned,
        )

    , test("user that has no suspicious requests gets allowed"):
      defer:
        val ips = List("192.168.1.2")
        val now = Instant.now()

        // make 10 non-suspect requests spread over 5 seconds
        val instants = (0 until 10).map(i => now.plusMillis(i.toLong * 500)).toList
        val results = ZIO.foreach(instants)(instant => BadActor.checkReq(ips, instant, suspect = false)).run

        assertTrue(
          results.forall(_ == Allowed),
        )

    , test("user with suspect requests spread over more than 10 seconds gets allowed"):
      defer:
        val ips = List("192.168.1.3")
        val baseTime = Instant.now()

        // Make 5 suspect requests spread over more than 10 seconds (at 0, 3, 6, 9, 12 seconds)
        val instants = (0 until 5).map(i => baseTime.plusSeconds(i.toLong * 3)).toList
        val results = ZIO.foreach(instants)(instant => BadActor.checkReq(ips, instant, suspect = true)).run

        // 6th request - queue has 5 items but spread over 12 seconds (> 10s window)
        val finalResult = BadActor.checkReq(ips, baseTime.plusSeconds(15), suspect = true).run

        assertTrue(
          results.forall(_ == Allowed),
          finalResult == Allowed,
        )
  ).provide(
    BadActor.live
  )
