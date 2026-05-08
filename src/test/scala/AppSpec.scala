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
        val groupIdResp = Web.appWithMiddleware.runZIO(Request.get(URL(Path.root, queryParams = QueryParams("groupId" -> "com.jamesward"))).addHeader(forwardedForHeader)).run
        val artifactIdResp = Web.appWithMiddleware.runZIO(Request.get(URL(Path.root / "com.jamesward", queryParams = QueryParams("artifactId" -> "travis-central-test"))).addHeader(forwardedForHeader)).run
        val versionResp = Web.appWithMiddleware.runZIO(Request.get(URL(Path.root / "com.jamesward" / "travis-central-test", queryParams = QueryParams("version" -> "0.0.15"))).addHeader(forwardedForHeader)).run
        val latest = Web.appWithMiddleware.runZIO(Request.get(URL(Path.root / "org.webjars" / "jquery" / "latest")).addHeader(forwardedForHeader)).run

        val groupIdRedir = Web.appWithMiddleware.runZIO(Request.get(URL((Path.root / "com.jamesward").addTrailingSlash)).addHeader(forwardedForHeader)).run
        val artifactIdRedir = Web.appWithMiddleware.runZIO(Request.get(URL((Path.root / "com.jamesward" / "travis-central-test").addTrailingSlash)).addHeader(forwardedForHeader)).run

        val indexPath = Web.appWithMiddleware.runZIO(Request.get(URL(Path.root / "org.webjars" / "webjars-locator-core" / "0.52" / "index.html")).addHeader(forwardedForHeader)).run
        val filePath = Web.appWithMiddleware.runZIO(Request.get(URL(Path.root / "org.webjars" / "webjars-locator-core" / "0.52" / "org" / "webjars" / "package-summary.html")).addHeader(forwardedForHeader)).run
        val notFoundFilePath = Web.appWithMiddleware.runZIO(Request.get(URL(Path.root / "org.webjars" / "webjars-locator-core" / "0.52" / "asdf")).addHeader(forwardedForHeader)).run
        val notFoundGroupId = Web.appWithMiddleware.runZIO(Request.get(URL(Path.root / "asdfqwerzzxcv")).addHeader(forwardedForHeader)).run

        assertTrue(
          Web.appWithMiddleware.runZIO(Request.get(URL(Path.empty)).addHeader(forwardedForHeader)).run.status.isSuccess,
          Web.appWithMiddleware.runZIO(Request.get(URL(Path.root)).addHeader(forwardedForHeader)).run.status.isSuccess,
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
          indexPath.header(Header.ETag).contains(Header.ETag.Weak("/org.webjars/webjars-locator-core/0.52/index.html")),
          indexPath.header(Header.LastModified).map(_.value.toInstant).contains(java.time.Instant.EPOCH),
          filePath.status.isSuccess,
          filePath.header(Header.CacheControl).exists(_.renderedValue.contains("immutable")),
          notFoundFilePath.status == Status.NotFound,
          notFoundGroupId.status == Status.NotFound,
          // /latest must not get immutable caching (it redirects to a changing version)
          latest.header(Header.CacheControl).forall(!_.renderedValue.contains("immutable")),
          // top-level and groupId pages don't get immutable caching
          groupIdResp.header(Header.CacheControl).forall(!_.renderedValue.contains("immutable")),
        )
    , test("304 Not Modified for immutable assets with If-None-Match or If-Modified-Since"):
      val forwardedForHeader = Header.Custom("X-Forwarded-For", "192.168.1.100")
      // Use a GAV that doesn't exist on Maven Central — the whole point is to prove
      // we never try to download it, returning 304 before any route/download runs.
      val neverDownloadedPath = Path.root / "com.example.nonexistent" / "no-such-artifact" / "99.99.99" / "index.html"
      defer:
        val withIfNoneMatch = Web.appWithMiddleware.runZIO(
          Request.get(URL(neverDownloadedPath))
            .addHeader(forwardedForHeader)
            .addHeader(Header.IfNoneMatch.Any)
        ).run
        val withIfModifiedSince = Web.appWithMiddleware.runZIO(
          Request.get(URL(neverDownloadedPath))
            .addHeader(forwardedForHeader)
            .addHeader(Header.IfModifiedSince(java.time.ZonedDateTime.ofInstant(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC)))
        ).run
        assertTrue(
          withIfNoneMatch.status == Status.NotModified,
          withIfNoneMatch.header(Header.ETag).isDefined,
          withIfNoneMatch.header(Header.LastModified).map(_.value.toInstant).contains(java.time.Instant.EPOCH),
          withIfModifiedSince.status == Status.NotModified,
        )
    , test("version page for javadoc without index.html"):
      val forwardedForHeader = Header.Custom("X-Forwarded-For", "192.168.1.100")
      defer:
        val versionPage = Web.appWithMiddleware.runZIO(
          Request.get(URL(Path.root / "tools.jackson.core" / "jackson-core" / "3.1.1"))
            .addHeader(forwardedForHeader)
            .addHeader(Header.Accept(MediaType.text.html))
        ).run
        val filePage = Web.appWithMiddleware.runZIO(
          Request.get(URL(Path.root / "tools.jackson.core" / "jackson-core" / "3.1.1" / "tools.jackson.core" / "tools" / "jackson" / "core" / "tree" / "ArrayTreeNode.html"))
            .addHeader(forwardedForHeader)
        ).run
        val body = versionPage.body.asString.run
        assertTrue(
          versionPage.status.isSuccess,
          body.contains("ArrayTreeNode.html"),
          filePage.status.isSuccess,
        )
    , test("HEAD behaves like GET but returns no body"):
      val forwardedForHeader = Header.Custom("X-Forwarded-For", "192.168.1.100")
      val assetPath = Path.root / "org.webjars" / "webjars-locator-core" / "0.52" / "org" / "webjars" / "package-summary.html"
      defer:
        val getResp = Web.appWithMiddleware.runZIO(
          Request.get(URL(assetPath)).addHeader(forwardedForHeader)
        ).run
        val headResp = Web.appWithMiddleware.runZIO(
          Request.head(URL(assetPath)).addHeader(forwardedForHeader)
        ).run
        val getBody = getResp.body.asString.run
        val headBody = headResp.body.asString.run
        assertTrue(
          getResp.status.isSuccess,
          headResp.status == getResp.status,
          headBody.isEmpty,
          getBody.nonEmpty,
          // HEAD should expose the same Content-Length as GET would
          headResp.header(Header.ContentLength).map(_.length).contains(getBody.length.toLong),
          // Other significant headers should match
          headResp.header(Header.ContentType).map(_.renderedValue) == getResp.header(Header.ContentType).map(_.renderedValue),
          headResp.header(Header.ETag).map(_.renderedValue) == getResp.header(Header.ETag).map(_.renderedValue),
          headResp.header(Header.CacheControl).map(_.renderedValue) == getResp.header(Header.CacheControl).map(_.renderedValue),
        )
    , test("HEAD /mcp does not return 500"):
      val forwardedForHeader = Header.Custom("X-Forwarded-For", "192.168.1.100")
      defer:
        val headResp = Web.appWithMiddleware.runZIO(
          Request.head(URL(Path.root / "mcp")).addHeader(forwardedForHeader)
        ).run
        val headBody = headResp.body.asString.run
        assertTrue(
          headResp.status != Status.InternalServerError,
          // GET /mcp returns 405 (MCP is POST-only); HEAD should mirror that.
          headResp.status == Status.MethodNotAllowed,
          headBody.isEmpty,
        )
    , test("HEAD for unknown path returns 404 like GET (no body)"):
      val forwardedForHeader = Header.Custom("X-Forwarded-For", "192.168.1.100")
      defer:
        val headResp = Web.appWithMiddleware.runZIO(
          Request.head(URL(Path.root / "asdfqwerzzxcv")).addHeader(forwardedForHeader)
        ).run
        val headBody = headResp.body.asString.run
        assertTrue(
          headResp.status == Status.NotFound,
          headBody.isEmpty,
        )
    , test("rate limit bad actors"):
      defer:
        val forwardedBadActorHeader = Header.Custom("X-Forwarded-For", "192.168.1.100")

        // Make 5 requests ending in .php - these should return not found
        val phpResponses = ZIO.foreach(1 to 5): i =>
          val request = Request.get(URL(Path.root / s"test$i.php")).addHeader(forwardedBadActorHeader)
          Web.appWithMiddleware.runZIO(request)
        .run

        // The 6th request should trigger the slow gibberish response
        val forwardedBadActorMultipleHeader = Header.Custom("X-Forwarded-For", "192.168.1.101,192.168.1.100")
        val slowRequest = Request.get(URL(Path.root / "trigger.php")).addHeader(forwardedBadActorMultipleHeader)

        val slowResponse = Web.appWithMiddleware.runZIO(slowRequest).run

        val bodyFork = slowResponse.body.asString.timed.fork.run

        // we can't just move the clock once as that won't trigger the interrupt
        TestClock.adjust(1.second).forever.fork.run

        val (duration, body) = bodyFork.join.run

        val forwardedGoodActorHeader = Header.Custom("X-Forwarded-For", "192.168.1.101")
        val goodActorRequest = Request.get(URL(Path.root)).addHeader(forwardedGoodActorHeader)
        val goodActorResponse = Web.appWithMiddleware.runZIO(goodActorRequest).run

        assertTrue(
          phpResponses.forall(_.status == Status.NotFound),
          slowResponse.status == Status.Ok,
          duration.toSeconds >= 25,
          body.nonEmpty,
          goodActorResponse.status == Status.Ok,
        )
    , test("gibberish"):
      defer:
        val gibberishFromStreamFork = Web.gibberishStream.runCollect.timed.fork.run
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
        val firstGav = Web.appWithMiddleware.runZIO(Request.get(URL(gav1)).addHeader(forwardedForHeader).addHeader(bot)).run
        val sameGavOtherFile = Web.appWithMiddleware.runZIO(Request.get(URL(gav1File)).addHeader(forwardedForHeader).addHeader(bot)).run
        val otherGav = Web.appWithMiddleware.runZIO(Request.get(URL(gav2)).addHeader(forwardedForHeader).addHeader(bot)).run
        // non-crawler bypasses the limiter entirely
        val nonCrawlerSameGav = Web.appWithMiddleware.runZIO(Request.get(URL(gav2)).addHeader(forwardedForHeader)).run

        assertTrue(
          firstGav.status == Status.Ok,
          sameGavOtherFile.status == Status.Ok,
          otherGav.status == Status.TooManyRequests,
          otherGav.header(Header.RetryAfter).isDefined,
          nonCrawlerSameGav.status != Status.TooManyRequests,
        )

    , test("loading an index.html populates the symbol index so later search finds the artifact"):
      // Reproduces: user loads <gav>/index.html, then searches for a substring
      // of the artifact id (e.g. "zio") and expects the artifact in the results.
      // `withFile` forks `SymbolSearch.indexJavadocContents` as a daemon when
      // the request path is `index.html`; that daemon calls `Extractor.javadocContents`
      // (which requires `JavadocCache.getDir` + scope), reads the parsed symbols,
      // and writes them to Redis.
      val forwardedForHeader = Header.Custom("X-Forwarded-For", "192.168.1.100")
      // Any modular-javadoc artifact will do; zio_3 is small and well-behaved.
      val gid = "com.jamesward"
      val aid = "zio-http-mcp_3"
      val ver = "0.0.7"
      val indexPath = Path.root / gid / aid / ver / "index.html"
      defer:
        val indexResp = Web.appWithMiddleware.runZIO(
          Request.get(URL(indexPath)).addHeader(forwardedForHeader)
        ).run
        // Drain the body so the scope on the response closes (mirroring what a
        // real HTTP client does). Only then does the background indexing fiber
        // see its parent scope release.
        indexResp.body.asArray.run

        // Wait up to 30s for the daemon fiber to finish indexing. The artifact
        // id `zio_3` appears in the Redis `_groupArtifacts` set as soon as the
        // index write completes.
        val redis = ZIO.service[Redis].run
        val groupArtifact = com.jamesward.zio_mavencentral.MavenCentral.GroupArtifact(
          com.jamesward.zio_mavencentral.MavenCentral.GroupId(gid),
          com.jamesward.zio_mavencentral.MavenCentral.ArtifactId(aid),
        )
        redis.sIsMember(SymbolSearch.groupArtifactsKey, groupArtifact)
          .repeatUntil(identity)
          .timeoutFail(new RuntimeException("index never populated"))(30.seconds)
          .orDie.run

        // Now search for "zio" — should return the artifact.
        val searchResp = Web.appWithMiddleware.runZIO(
          Request.get(URL(Path.root, queryParams = zio.http.QueryParams("zio" -> "")))
            .addHeader(forwardedForHeader)
            .addHeader(Header.Accept(MediaType.text.markdown))
        ).run
        val searchBody = searchResp.body.asString.run

        assertTrue(
          indexResp.status.isSuccess,
          searchResp.status.isSuccess,
          searchBody.contains(s"$gid:$aid"),
        )

  ).provide(
    App.javadocCacheLayer,
    App.sourcesCacheLayer,
    App.latestCacheLayer,
    Client.default,
    EmbeddedRedis.layer,
    Redis.singleNode,
    ZLayer.succeed[CodecSupplier](SymbolSearch.ProtobufCodecSupplier),
    SymbolSearch.herokuInferenceLayer.orElse(MockInference.layer),
    BadActor.live,
    Web.crawlerGavLimiterLayer,
      App.symbolSearchGuardLayer,
    // Test-level Scope: `Handler#runZIO` returns `ZIO[Scope & R, ...]`,
    // so invoking the app from test bodies needs a `Scope`.
    Scope.default,
  ) @@ TestAspect.withLiveClock @@ TestAspect.withLiveRandom @@ TestAspect.withLiveSystem @@ TestAspect.sequential
