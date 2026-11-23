import zio.*
import zio.direct.*
import zio.http.*
import zio.test.*

object UpstreamStatsSpec extends ZIOSpecDefault:

  def spec = suite("UpstreamStats")(
    test("counts requests per host and outcome, with the bytes of successes, then resets") {
      defer:
        val port = Server.install(Routes(
          Method.GET / "ok"      -> Handler.fromResponse(Response.text("x" * 4096)),
          Method.GET / "missing" -> Handler.status(Status.NotFound),
          Method.GET / "same"    -> Handler.status(Status.NotModified),
          Method.GET / "down"    -> Handler.status(Status.ServiceUnavailable),
        )).run
        UpstreamStats.snapshotAndReset.run
        val client = ZIO.service[Client].run @@ UpstreamStats.aspect
        val base   = s"http://localhost:$port"
        ZIO.foreachDiscard(List("ok", "ok", "missing", "same", "down")): p =>
          client.batched(Request.get(URL.decode(s"$base/$p").toOption.get))
        .run
        client.batched(Request.get(URL.decode("http://localhost:1/nothing").toOption.get)).ignore.run
        val first  = UpstreamStats.snapshotAndReset.run
        val second = UpstreamStats.snapshotAndReset.run
        assertTrue(
          first.contains("localhost:2xx=2,304=1,404=1,5xx=1,kb=8"),
          first.contains("err=1"),
          second.isEmpty,
        )
    },
  ).provide(Server.defaultWith(_.onAnyOpenPort), Client.default) @@ TestAspect.withLiveClock @@ TestAspect.sequential
