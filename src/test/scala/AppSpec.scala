import com.jamesward.zio_mavencentral.MavenCentral.given
import zio.*
import zio.cache.Cache
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.http.*
import zio.redis.embedded.EmbeddedRedis
import zio.redis.{CodecSupplier, Redis}
import zio.test.*

object AppSpec extends ZIOSpecDefault:

  def spec = suite("App")(
    test("routing"):
      defer:
        val groupIdResp = App.appWithMiddleware.runZIO(Request.get(URL(Path.root, queryParams = QueryParams("groupId" -> "com.jamesward")))).run
        val artifactIdResp = App.appWithMiddleware.runZIO(Request.get(URL(Path.root / "com.jamesward", queryParams = QueryParams("artifactId" -> "travis-central-test")))).run
        val versionResp = App.appWithMiddleware.runZIO(Request.get(URL(Path.root / "com.jamesward" / "travis-central-test", queryParams = QueryParams("version" -> "0.0.15")))).run
        val latest = App.appWithMiddleware.runZIO(Request.get(URL(Path.root / "org.webjars" / "jquery" / "latest"))).run

        val groupIdRedir = App.appWithMiddleware.runZIO(Request.get(URL((Path.root / "com.jamesward").addTrailingSlash))).run
        val artifactIdRedir = App.appWithMiddleware.runZIO(Request.get(URL((Path.root / "com.jamesward" / "travis-central-test").addTrailingSlash))).run

        val indexPath = App.appWithMiddleware.runZIO(Request.get(URL(Path.root / "org.webjars" / "webjars-locator-core" / "0.52" / "index.html"))).run
        val filePath = App.appWithMiddleware.runZIO(Request.get(URL(Path.root / "org.webjars" / "webjars-locator-core" / "0.52" / "org" / "webjars" / "package-summary.html"))).run
        val notFoundFilePath = App.appWithMiddleware.runZIO(Request.get(URL(Path.root / "org.webjars" / "webjars-locator-core" / "0.52" / "asdf"))).run
        val notFoundGroupId = App.appWithMiddleware.runZIO(Request.get(URL(Path.root / "asdfqwerzzxcv"))).run

        assertTrue(
          App.appWithMiddleware.runZIO(Request.get(URL(Path.empty))).run.status.isSuccess,
          App.appWithMiddleware.runZIO(Request.get(URL(Path.root))).run.status.isRedirection,
          groupIdResp.status.isRedirection,
          groupIdResp.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward")),
          artifactIdResp.status.isRedirection,
          artifactIdResp.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward/travis-central-test")),
          versionResp.status.isRedirection,
          versionResp.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward/travis-central-test/0.0.15")),
          latest.status.isRedirection,
          latest.headers.get(Header.Location).exists(_.url.path == Path.decode("/org.webjars/jquery/3.7.1")),
          groupIdRedir.status.isRedirection,
          groupIdRedir.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward")),
          artifactIdRedir.status.isRedirection,
          artifactIdRedir.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward/travis-central-test")),
          indexPath.status.isSuccess,
          filePath.status.isSuccess,
          notFoundFilePath.status == Status.NotFound,
          notFoundGroupId.status == Status.NotFound,
        )
    , test("rate limit bad actors"):
      defer:
        val badActorIps = List("192.168.1.100")
        val forwardedHeader = Header.Forwarded(forValues = badActorIps)

        // Make 5 requests ending in .php - these should return not found
        val phpResponses = ZIO.foreach(1 to 5): i =>
          val request = Request.get(URL(Path.root / s"test$i.php")).addHeader(forwardedHeader)
          App.appWithMiddleware.runZIO(request)
        .run

        // The 6th request should trigger the slow gibberish response
        val slowRequest = Request.get(URL(Path.root / "trigger.php")).addHeader(forwardedHeader)

        val slowResponse = App.appWithMiddleware.runZIO(slowRequest).debug.run

        val bodyFork = slowResponse.body.asString.timed.fork.run

        // we can't just move the clock once as that won't trigger the interrupt
        TestClock.adjust(1.second).forever.fork.run

        val (duration, body) = bodyFork.join.run

        assertTrue(
          phpResponses.forall(_.status == Status.NotFound),
          slowResponse.status == Status.TooManyRequests,
          duration.toSeconds >= 25,
          body.nonEmpty,
        )
    , test("gibberish"):
      defer:
        val gibberishFromStreamFork = App.gibberishStream.runCollect.timed.fork.run
        // we can't just move the clock once as that won't trigger the interrupt
        TestClock.adjust(1.second).forever.fork.run
        val (duration, gibberish) = gibberishFromStreamFork.join.run

        assertTrue(
          duration.toSeconds >= 30,
          !gibberish.isEmpty,
        )

  ).provide(
    App.blockerLayer,
    App.javadocCacheLayer,
    App.latestCacheLayer,
    App.tmpDirLayer,
    Client.default,
    Scope.default,
    EmbeddedRedis.layer,
    Redis.singleNode,
    ZLayer.succeed[CodecSupplier](SymbolSearch.ProtobufCodecSupplier),
    SymbolSearch.herokuInferenceLayer.orElse(MockInference.layer),
    BadActor.live,
  ) @@ TestAspect.withLiveRandom @@ TestAspect.withLiveSystem
