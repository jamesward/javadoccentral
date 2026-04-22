import com.jamesward.zio_mavencentral.MavenCentral.given
import zio.*
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.http.*
import zio.redis.embedded.EmbeddedRedis
import zio.redis.{CodecSupplier, Redis}
import zio.test.*

object AppSpec extends ZIOSpecDefault:

  def spec = suite("App")(
    test("routing"):
      val forwardedForHeader = Header.Custom("X-Forwarded-For", "192.168.1.100")

      defer:
        val groupIdResp = App.appWithMiddleware.runZIO(Request.get(URL(Path.root, queryParams = QueryParams("groupId" -> "com.jamesward"))).addHeader(forwardedForHeader)).run
        val artifactIdResp = App.appWithMiddleware.runZIO(Request.get(URL(Path.root / "com.jamesward", queryParams = QueryParams("artifactId" -> "travis-central-test"))).addHeader(forwardedForHeader)).run
        val versionResp = App.appWithMiddleware.runZIO(Request.get(URL(Path.root / "com.jamesward" / "travis-central-test", queryParams = QueryParams("version" -> "0.0.15"))).addHeader(forwardedForHeader)).run
        val latest = App.appWithMiddleware.runZIO(Request.get(URL(Path.root / "org.webjars" / "jquery" / "latest")).addHeader(forwardedForHeader)).run

        val groupIdRedir = App.appWithMiddleware.runZIO(Request.get(URL((Path.root / "com.jamesward").addTrailingSlash)).addHeader(forwardedForHeader)).run
        val artifactIdRedir = App.appWithMiddleware.runZIO(Request.get(URL((Path.root / "com.jamesward" / "travis-central-test").addTrailingSlash)).addHeader(forwardedForHeader)).run

        val indexPath = App.appWithMiddleware.runZIO(Request.get(URL(Path.root / "org.webjars" / "webjars-locator-core" / "0.52" / "index.html")).addHeader(forwardedForHeader)).run
        val filePath = App.appWithMiddleware.runZIO(Request.get(URL(Path.root / "org.webjars" / "webjars-locator-core" / "0.52" / "org" / "webjars" / "package-summary.html")).addHeader(forwardedForHeader)).run
        val notFoundFilePath = App.appWithMiddleware.runZIO(Request.get(URL(Path.root / "org.webjars" / "webjars-locator-core" / "0.52" / "asdf")).addHeader(forwardedForHeader)).run
        val notFoundGroupId = App.appWithMiddleware.runZIO(Request.get(URL(Path.root / "asdfqwerzzxcv")).addHeader(forwardedForHeader)).run

        assertTrue(
          App.appWithMiddleware.runZIO(Request.get(URL(Path.empty)).addHeader(forwardedForHeader)).run.status.isSuccess,
          App.appWithMiddleware.runZIO(Request.get(URL(Path.root)).addHeader(forwardedForHeader)).run.status.isSuccess,
          groupIdResp.status.isRedirection,
          groupIdResp.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward")),
          artifactIdResp.status.isRedirection,
          artifactIdResp.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward/travis-central-test")),
          versionResp.status.isRedirection,
          versionResp.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward/travis-central-test/0.0.15")),
          latest.status.isRedirection,
          latest.headers.get(Header.Location).exists(_.url.path == Path.decode("/org.webjars/jquery/4.0.0")),
          groupIdRedir.status.isRedirection,
          groupIdRedir.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward")),
          artifactIdRedir.status.isRedirection,
          artifactIdRedir.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward/travis-central-test")),
          indexPath.status.isSuccess,
          indexPath.header(Header.CacheControl).exists(_.renderedValue.contains("immutable")),
          indexPath.header(Header.CacheControl).exists(_.renderedValue.contains("max-age=31536000")),
          filePath.status.isSuccess,
          filePath.header(Header.CacheControl).exists(_.renderedValue.contains("immutable")),
          notFoundFilePath.status == Status.NotFound,
          notFoundGroupId.status == Status.NotFound,
          // /latest must not get immutable caching (it redirects to a changing version)
          latest.header(Header.CacheControl).forall(!_.renderedValue.contains("immutable")),
          // top-level and groupId pages don't get immutable caching
          groupIdResp.header(Header.CacheControl).forall(!_.renderedValue.contains("immutable")),
        )
    , test("version page for javadoc without index.html"):
      val forwardedForHeader = Header.Custom("X-Forwarded-For", "192.168.1.100")
      defer:
        val versionPage = App.appWithMiddleware.runZIO(
          Request.get(URL(Path.root / "tools.jackson.core" / "jackson-core" / "3.1.1"))
            .addHeader(forwardedForHeader)
            .addHeader(Header.Accept(MediaType.text.html))
        ).run
        val filePage = App.appWithMiddleware.runZIO(
          Request.get(URL(Path.root / "tools.jackson.core" / "jackson-core" / "3.1.1" / "tools.jackson.core" / "tools" / "jackson" / "core" / "tree" / "ArrayTreeNode.html"))
            .addHeader(forwardedForHeader)
        ).run
        val body = versionPage.body.asString.run
        assertTrue(
          versionPage.status.isSuccess,
          body.contains("ArrayTreeNode.html"),
          filePage.status.isSuccess,
        )
    , test("rate limit bad actors"):
      defer:
        val forwardedBadActorHeader = Header.Custom("X-Forwarded-For", "192.168.1.100")

        // Make 5 requests ending in .php - these should return not found
        val phpResponses = ZIO.foreach(1 to 5): i =>
          val request = Request.get(URL(Path.root / s"test$i.php")).addHeader(forwardedBadActorHeader)
          App.appWithMiddleware.runZIO(request)
        .run

        // The 6th request should trigger the slow gibberish response
        val forwardedBadActorMultipleHeader = Header.Custom("X-Forwarded-For", "192.168.1.101,192.168.1.100")
        val slowRequest = Request.get(URL(Path.root / "trigger.php")).addHeader(forwardedBadActorMultipleHeader)

        val slowResponse = App.appWithMiddleware.runZIO(slowRequest).run

        val bodyFork = slowResponse.body.asString.timed.fork.run

        // we can't just move the clock once as that won't trigger the interrupt
        TestClock.adjust(1.second).forever.fork.run

        val (duration, body) = bodyFork.join.run

        val forwardedGoodActorHeader = Header.Custom("X-Forwarded-For", "192.168.1.101")
        val goodActorRequest = Request.get(URL(Path.root)).addHeader(forwardedGoodActorHeader)
        val goodActorResponse = App.appWithMiddleware.runZIO(goodActorRequest).run

        assertTrue(
          phpResponses.forall(_.status == Status.NotFound),
          slowResponse.status == Status.Ok,
          duration.toSeconds >= 25,
          body.nonEmpty,
          goodActorResponse.status == Status.Ok,
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
    , test("crawler rate limit: one GAV per crawler, other GAVs get 429"):
      val forwardedForHeader = Header.Custom("X-Forwarded-For", "192.168.1.100")
      val bot = Header.Custom("User-Agent", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)")
      val gav1 = Path.root / "org.webjars" / "webjars-locator-core" / "0.52" / "index.html"
      val gav1File = Path.root / "org.webjars" / "webjars-locator-core" / "0.52" / "org" / "webjars" / "package-summary.html"
      val gav2 = Path.root / "org.webjars" / "jquery" / "3.7.1" / "index.html"
      defer:
        val firstGav = App.appWithMiddleware.runZIO(Request.get(URL(gav1)).addHeader(forwardedForHeader).addHeader(bot)).run
        val sameGavOtherFile = App.appWithMiddleware.runZIO(Request.get(URL(gav1File)).addHeader(forwardedForHeader).addHeader(bot)).run
        val otherGav = App.appWithMiddleware.runZIO(Request.get(URL(gav2)).addHeader(forwardedForHeader).addHeader(bot)).run
        // non-crawler bypasses the limiter entirely
        val nonCrawlerSameGav = App.appWithMiddleware.runZIO(Request.get(URL(gav2)).addHeader(forwardedForHeader)).run

        assertTrue(
          firstGav.status == Status.Ok,
          sameGavOtherFile.status == Status.Ok,
          otherGav.status == Status.TooManyRequests,
          otherGav.header(Header.RetryAfter).isDefined,
          nonCrawlerSameGav.status != Status.TooManyRequests,
        )

  ).provide(
    App.blockerLayer,
    App.sourcesBlockerLayer,
    App.javadocCacheLayer,
    App.sourcesCacheLayer,
    App.latestCacheLayer,
    App.tmpDirLayer,
    Client.default,
    Scope.default,
    EmbeddedRedis.layer,
    Redis.singleNode,
    ZLayer.succeed[CodecSupplier](SymbolSearch.ProtobufCodecSupplier),
    SymbolSearch.herokuInferenceLayer.orElse(MockInference.layer),
    BadActor.live,
    App.crawlerEvictionsLayer,
      App.crawlerGavLimiterLayer,
  ) @@ TestAspect.withLiveClock @@ TestAspect.withLiveRandom @@ TestAspect.withLiveSystem @@ TestAspect.sequential
