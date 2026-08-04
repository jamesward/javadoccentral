import com.jamesward.zio_http_guard.{BadActor, BadActorMiddleware, CrawlerLimiter}
import com.jamesward.zio_mavencentral.MavenCentral
import com.jamesward.zio_mavencentral.MavenCentral.given
import zio.*
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.http.*
import zio.json.*
import zio.redis.{CodecSupplier, Redis}
import zio.test.*

object AppSpec extends ZIOSpecDefault:

  // Helpers kept outside `defer` bodies: zio-direct disallows mutable `Array`
  // vals inside a defer clause, so byte work lives in plain methods.
  private def utf8(chunk: Chunk[Byte]): String =
    new String(chunk.toArray, java.nio.charset.StandardCharsets.UTF_8)
  private def sha256Hex(chunk: Chunk[Byte]): String =
    "sha256:" + java.security.MessageDigest.getInstance("SHA-256")
      .digest(chunk.toArray).map(b => f"${b & 0xff}%02x").mkString

  private def apiUrl(endpoint: String, first: (String, String), rest: (String, String)*): URL =
    URL(Path.root / "api" / endpoint, queryParams = QueryParams(first, rest*))

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
    , test("badge redirects to shields.io with the latest version"):
      val forwardedForHeader = Header.Custom("X-Forwarded-For", "192.168.1.100")
      defer:
        val badge = Web.appWithMiddleware.runZIO(
          Request.get(URL(Path.root / "com.jamesward" / "travis-central-test" / "badge.svg")).addHeader(forwardedForHeader)
        ).run
        // Unknown artifact -> still a redirect, but to a "not found" badge.
        val notFound = Web.appWithMiddleware.runZIO(
          Request.get(URL(Path.root / "asdfqwerzzxcv" / "no-such-artifact" / "badge.svg")).addHeader(forwardedForHeader)
        ).run

        val location   = badge.headers.get(Header.Location).map(_.renderedValue).getOrElse("")
        val locationNF = notFound.headers.get(Header.Location).map(_.renderedValue).getOrElse("")

        assertTrue(
          badge.status.isRedirection,
          location.startsWith("https://img.shields.io/static/v1?"),
          location.contains("label=javadocs.dev"),
          // A real version string should land in the message; we don't pin a
          // specific version (it could change), just assert it's present and
          // non-empty.
          location.contains("&message="),
          !location.contains("&message=&"),
          location.contains("&color=blue"),
          badge.header(Header.CacheControl).exists(_.renderedValue.contains("max-age=3600")),
          badge.header(Header.CacheControl).exists(_.renderedValue.contains("public")),

          notFound.status.isRedirection,
          locationNF.startsWith("https://img.shields.io/static/v1?"),
          locationNF.contains("label=javadocs.dev"),
          locationNF.contains("&color=red"),
          notFound.header(Header.CacheControl).exists(_.renderedValue.contains("max-age=300")),
        )
    , test("latest with Accept: application/json returns just the latest version"):
      val forwardedForHeader = Header.Custom("X-Forwarded-For", "192.168.1.100")
      defer:
        val resp = Web.appWithMiddleware.runZIO(
          Request.get(URL(Path.root / "org.webjars" / "jquery" / "latest"))
            .addHeader(forwardedForHeader)
            .addHeader(Header.Accept(MediaType.application.json))
        ).run
        val body = resp.body.asString.run
        assertTrue(
          resp.status.isSuccess,
          resp.header(Header.ContentType).exists(_.mediaType.matches(MediaType.application.json)),
          body == """{"version":"4.0.0"}""",
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
        val gibberishFromStreamFork = BadActorMiddleware.gibberishStream.runCollect.timed.fork.run
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
        val groupArtifact = com.jamesward.zio_mavencentral.MavenCentral.GroupArtifact(
          com.jamesward.zio_mavencentral.MavenCentral.GroupId(gid),
          com.jamesward.zio_mavencentral.MavenCentral.ArtifactId(aid),
        )
        // Poll via SymbolSearch's own read path, which decodes with the compact
        // string schema Redis is actually written with. (Checking membership
        // with `redis.sIsMember` here would encode `groupArtifact` with the
        // ambient *record* schema and never match the stored string bytes.)
        SymbolSearch.allGroupArtifacts
          .map(_.contains(groupArtifact))
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

    , test("agent-readiness endpoints: Content-Signal, MCP server card, homepage Link header"):
      val ff = Header.Custom("X-Forwarded-For", "192.168.1.100")
      defer:
        val robotsResp = Web.appWithMiddleware.runZIO(Request.get(URL(Path.root / "robots.txt")).addHeader(ff)).run
        val robotsBody = robotsResp.body.asString.run

        val cardResp = Web.appWithMiddleware.runZIO(Request.get(URL(Path.root / ".well-known" / "mcp" / "server-card.json")).addHeader(ff)).run
        val cardBody = cardResp.body.asString.run

        val homeResp = Web.appWithMiddleware.runZIO(Request.get(URL(Path.root)).addHeader(ff)).run
        val linkHeader = homeResp.rawHeader("Link")
        val homeBody = homeResp.body.asString.run

        // Agents may probe with HEAD; per RFC 9110 it must return the same
        // headers as GET (minus body). Same handler + headStripBody should do this.
        val headResp = Web.appWithMiddleware.runZIO(Request.get(URL(Path.root)).copy(method = Method.HEAD).addHeader(ff)).run
        val headBody = headResp.body.asString.run

        assertTrue(
          // (1) Content Signals declared in robots.txt -> reaches "Bot-Aware"
          robotsResp.status.isSuccess,
          robotsBody.contains("Content-Signal:"),
          robotsBody.contains("search=yes"),
          robotsBody.contains("ai-input=yes"),
          robotsBody.contains("ai-train=yes"),
          // (2) MCP Server Card discoverable at the well-known path; schema-derived
          //     record still emits the non-identifier keys via @fieldName
          cardResp.status.isSuccess,
          cardResp.header(Header.ContentType).exists(_.renderedValue.contains("application/json")),
          cardBody.contains("\"$schema\""),
          cardBody.contains("\"type\":\"streamable-http\"") || cardBody.contains("\"type\": \"streamable-http\""),
          cardBody.contains("https://www.javadocs.dev/mcp"),
          cardBody.contains("server-card.schema.json"),
          // (3) Homepage advertises a Link HEADER (what scanners read) ...
          linkHeader.exists(_.contains("mcp-server-card")),
          linkHeader.exists(_.contains("alternate")),
          linkHeader.exists(_.contains("service-desc")),
          linkHeader.exists(_.contains("openapi.json")),
          linkHeader.exists(_.contains("agent-skills")),
          // ... and also emits the links as <link> tags in the page <head>
          homeBody.contains("rel=\"mcp-server-card\""),
          homeBody.contains("rel=\"alternate\""),
          homeBody.contains("rel=\"service-desc\""),
          homeBody.contains("rel=\"agent-skills\""),
          // (4) HEAD / carries the same Link header, with an empty body
          headResp.rawHeader("Link").exists(_.contains("mcp-server-card")),
          headResp.rawHeader("Link") == linkHeader,
          headBody.isEmpty,
        )

    , test("agent skills: /SKILL.md and discovery index with a matching digest"):
      val ff = Header.Custom("X-Forwarded-For", "192.168.1.100")
      defer:
        val skillResp  = Web.appWithMiddleware.runZIO(Request.get(URL(Path.root / "SKILL.md")).addHeader(ff)).run
        val skillChunk = skillResp.body.asChunk.run
        val skillBody  = utf8(skillChunk)

        val idxResp = Web.appWithMiddleware.runZIO(Request.get(URL(Path.root / ".well-known" / "agent-skills" / "index.json")).addHeader(ff)).run
        val idxBody = idxResp.body.asString.run

        val llmsResp = Web.appWithMiddleware.runZIO(Request.get(URL(Path.root / "llms.txt")).addHeader(ff)).run
        val llmsBody = llmsResp.body.asString.run

        // Independently recompute the digest of the served SKILL.md bytes.
        val digest = sha256Hex(skillChunk)

        assertTrue(
          // /SKILL.md is a valid Agent Skill (markdown + required frontmatter)
          skillResp.status.isSuccess,
          skillResp.header(Header.ContentType).exists(_.renderedValue.contains("markdown")),
          skillBody.contains("name: javadocs"),
          skillBody.contains("description:"),
          // discovery index has the v0.2.0 shape and required per-skill fields
          idxResp.status.isSuccess,
          idxResp.header(Header.ContentType).exists(_.renderedValue.contains("application/json")),
          idxBody.contains("\"$schema\":\"https://schemas.agentskills.io/discovery/0.2.0/schema.json\""),
          idxBody.contains("\"type\":\"skill-md\""),
          idxBody.contains("\"url\":\"/SKILL.md\""),
          idxBody.contains("\"name\":\"javadocs\""),
          // the index digest matches the exact bytes served at /SKILL.md
          idxBody.contains(s"\"digest\":\"$digest\""),
          // llms.txt advertises the skill + discovery index
          llmsResp.status.isSuccess,
          llmsBody.contains("https://www.javadocs.dev/SKILL.md"),
          llmsBody.contains("/.well-known/agent-skills/index.json"),
          llmsBody.contains("https://www.javadocs.dev/openapi.json"),
          llmsBody.contains("https://www.javadocs.dev/api/doc"),
        )

    , test("REST API mirrors all eight MCP operations with GET query parameters"):
      val ff = Header.Custom("X-Forwarded-For", "192.168.1.100")
      val gid = "org.webjars"
      val aid = "webjars-locator-core"
      val ver = "0.52"
      defer:
        // Deterministic Redis data for both search endpoints; no inference needed.
        val seededGav = MavenCentral.GroupArtifactVersion(
          MavenCentral.GroupId("com.example"),
          MavenCentral.ArtifactId("api-fixture"),
          MavenCentral.Version("1.0.0"),
        )
        SymbolSearch.update(
          seededGav,
          Set(Extractor.Content("com/example/ApiFixture.html", false, "com.example.ApiFixture", "class ApiFixture", "class", "")),
        ).run

        val latestResp = Web.appWithMiddleware.runZIO(Request.get(apiUrl(
          "latest-version", "groupId" -> "org.webjars", "artifactId" -> "jquery",
        )).addHeader(ff)).run
        val latestBody = latestResp.body.asString.run

        val indexResp = Web.appWithMiddleware.runZIO(Request.get(apiUrl(
          "javadoc-index", "groupId" -> gid, "artifactId" -> aid, "version" -> ver,
        )).addHeader(ff)).run
        val indexBody = indexResp.body.asString.run

        val listResp = Web.appWithMiddleware.runZIO(Request.get(apiUrl(
          "javadoc-content-list", "groupId" -> gid, "artifactId" -> aid, "version" -> ver,
        )).addHeader(ff)).run
        val listBody = listResp.body.asString.run

        val symbolResp = Web.appWithMiddleware.runZIO(Request.get(apiUrl(
          "javadoc-symbol-contents", "groupId" -> gid, "artifactId" -> aid,
          "version" -> ver, "link" -> "index.html",
        )).addHeader(ff)).run
        val symbolBody = symbolResp.body.asString.run

        val sourceListResp = Web.appWithMiddleware.runZIO(Request.get(apiUrl(
          "list-source-contents", "groupId" -> gid, "artifactId" -> aid, "version" -> ver,
        )).addHeader(ff)).run
        val sourceListBody = sourceListResp.body.asString.run
        val sourcePaths = ZIO.fromEither(sourceListBody.fromJson[Set[String]])
          .orDieWith(message => new RuntimeException(message)).run
        val sourceLink = ZIO.fromOption(sourcePaths.find(path => path.endsWith(".java") || path.endsWith(".scala")))
          .orDieWith(_ => new RuntimeException("sources jar contained no Java/Scala source file")).run

        val sourceResp = Web.appWithMiddleware.runZIO(Request.get(apiUrl(
          "source-contents", "groupId" -> gid, "artifactId" -> aid,
          "version" -> ver, "link" -> sourceLink,
        )).addHeader(ff)).run
        val sourceBody = sourceResp.body.asString.run

        val searchResp = Web.appWithMiddleware.runZIO(Request.get(apiUrl(
          "search-artifacts", "query" -> "api-fixture",
        )).addHeader(ff)).run
        val searchBody = searchResp.body.asString.run

        val symbolToArtifactResp = Web.appWithMiddleware.runZIO(Request.get(apiUrl(
          "symbol-to-artifact", "query" -> "com.example.ApiFixture",
        )).addHeader(ff)).run
        val symbolToArtifactBody = symbolToArtifactResp.body.asString.run

        assertTrue(
          // The old global redirect middleware would turn this into a redirect;
          // 200 proves /api query params reach the Endpoint decoder.
          latestResp.status.isSuccess,
          latestResp.header(Header.ContentType).exists(_.renderedValue.contains("application/json")),
          latestBody.startsWith("\"") && latestBody.endsWith("\""),
          indexResp.status.isSuccess,
          indexBody.length > 2,
          listResp.status.isSuccess,
          listBody.contains("\"link\""),
          symbolResp.status.isSuccess,
          symbolBody.length > 2,
          sourceListResp.status.isSuccess,
          sourcePaths.nonEmpty,
          sourceResp.status.isSuccess,
          sourceBody.contains("class") || sourceBody.contains("package"),
          searchResp.status.isSuccess,
          searchBody.contains("api-fixture"),
          symbolToArtifactResp.status.isSuccess,
          symbolToArtifactBody.contains("api-fixture"),
        )

    , test("OpenAPI, SwaggerUI, and RFC 9264 API catalog describe the REST API"):
      val ff = Header.Custom("X-Forwarded-For", "192.168.1.100")
      defer:
        val openApiResp = Web.appWithMiddleware.runZIO(Request.get(URL(Path.root / "openapi.json")).addHeader(ff)).run
        val openApiBody = openApiResp.body.asString.run

        val swaggerResp = Web.appWithMiddleware.runZIO(Request.get(URL(Path.root / "api" / "doc")).addHeader(ff)).run
        val swaggerBody = swaggerResp.body.asString.run

        val catalogResp = Web.appWithMiddleware.runZIO(Request.get(URL(Path.root / ".well-known" / "api-catalog")).addHeader(ff)).run
        val catalogBody = catalogResp.body.asString.run

        assertTrue(
          openApiResp.status.isSuccess,
          openApiResp.header(Header.ContentType).exists(_.renderedValue.contains("application/json")),
          openApiBody.contains("\"openapi\""),
          openApiBody.contains("/api/latest-version"),
          openApiBody.contains("/api/javadoc-index"),
          openApiBody.contains("/api/javadoc-content-list"),
          openApiBody.contains("/api/javadoc-symbol-contents"),
          openApiBody.contains("/api/list-source-contents"),
          openApiBody.contains("/api/source-contents"),
          openApiBody.contains("/api/search-artifacts"),
          openApiBody.contains("/api/symbol-to-artifact"),
          openApiBody.contains(MCP.Descriptions.getLatest.take(40)),
          swaggerResp.status.isSuccess,
          swaggerBody.toLowerCase.contains("swagger"),
          catalogResp.status.isSuccess,
          catalogResp.header(Header.ContentType).exists(_.renderedValue.contains("application/linkset+json")),
          catalogBody.contains("\"service-desc\""),
          catalogBody.contains("https://www.javadocs.dev/openapi.json"),
          catalogBody.contains("\"service-doc\""),
          catalogBody.contains("https://www.javadocs.dev/llms.txt"),
        )

  ).provide(
    App.javadocCacheLayer,
    App.sourcesCacheLayer,
    App.latestCacheLayer,
    Client.default,
    MavenCentral.MavenCentralRepo.live,
    ValkeyContainer.layer,
    Redis.singleNode,
    ZLayer.succeed[CodecSupplier](SymbolSearch.ProtobufCodecSupplier),
    SymbolSearch.herokuInferenceLayer.orElse(MockInference.layer),
    BadActor.live,
    CrawlerLimiter.layer[MavenCentral.GroupArtifactVersion],
      App.symbolSearchGuardLayer,
    // Test-level Scope: `Handler#runZIO` returns `ZIO[Scope & R, ...]`,
    // so invoking the app from test bodies needs a `Scope`.
    Scope.default,
  ) @@ TestAspect.withLiveClock @@ TestAspect.withLiveRandom @@ TestAspect.withLiveSystem @@ TestAspect.sequential
