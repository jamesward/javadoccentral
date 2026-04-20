import com.jamesward.zio_mavencentral.MavenCentral
import com.jamesward.zio_mavencentral.MavenCentral.*
import zio.*
import zio.cache.Cache
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.http.Client
import zio.redis.embedded.EmbeddedRedis
import zio.redis.{CodecSupplier, Redis}
import zio.test.*

object SymbolSearchSpec extends ZIOSpecDefault:

  def spec = suite("SymbolSearch")(
    suite("embedded")(
      test("search") {
        defer:
          val springai = Extractor.gav("org.springframework.ai", "spring-ai-mcp", "1.0.1")
          val doccontents = Extractor.javadocContents(springai).run
          SymbolSearch.update(springai.noVersion, doccontents).run

//          val redis = ZIO.service[Redis].run
//          redis.keys("*").returning[String].debug.run

          val notThere = SymbolSearch.search("asdf").run
          val fqnMatch = SymbolSearch.search("org.springframework.ai.mcp.AsyncMcpToolCallback").run
          val preMatch = SymbolSearch.search("org.springframework.ai.mcp").run
          val sufMatch = SymbolSearch.search("AsyncMcpToolCallback").run

          assertTrue(
            notThere.isEmpty,
            fqnMatch == Set(springai.noVersion),
            preMatch == Set(springai.noVersion),
            sufMatch == Set(springai.noVersion),
          )
      },
      test("inference") {
        defer:
          val herokuInference = ZIO.service[SymbolSearch.HerokuInference].run

          val response = herokuInference.req("say hello").run

          val body = response.body.asJson[SymbolSearch.MessageResponse].run

          assertTrue(
            response.status.isSuccess,
            body.choices.head.message.content.toLowerCase.contains("hello"),
          )
      },
      test("search skips symbol search when gaResults is non-empty") {
        defer:
          val redis = ZIO.service[Redis].run

          // Create a group artifact that matches the query "zio-json"
          val gaZioJson = MavenCentral.GroupArtifact(GroupId("dev.zio"), ArtifactId("zio-json_3"))
          redis.sAdd(SymbolSearch.groupArtifactsKey, gaZioJson).run

          // Create a symbol key that also matches "*zio-json*" but points to a different artifact
          val otherGa = MavenCentral.GroupArtifact(GroupId("com.other"), ArtifactId("other-lib"))
          redis.sAdd("com.example.zio-json-utils.Foo", otherGa).run

          val results = SymbolSearch.search("zio-json").run

          // gaResults is non-empty (matches zio-json_3), so symbol search should be skipped
          // and otherGa should NOT appear in results
          assertTrue(
            results.contains(gaZioJson),
            !results.contains(otherGa),
          )
      },
      test("searchGroupArtifacts handles spaces by matching all parts") {
        defer:
          val redis = ZIO.service[Redis].run

          val gaZioJson = MavenCentral.GroupArtifact(GroupId("dev.zio"), ArtifactId("zio-json_3"))
          val gaZioCache = MavenCentral.GroupArtifact(GroupId("dev.zio"), ArtifactId("zio-cache_3"))
          redis.sAdd(SymbolSearch.groupArtifactsKey, gaZioJson, gaZioCache).run

          val spaceResults = SymbolSearch.searchGroupArtifacts("zio json").run
          val noMatchResults = SymbolSearch.searchGroupArtifacts("zio nonexistent").run
          val singleResults = SymbolSearch.searchGroupArtifacts("zio").run

          assertTrue(
            spaceResults == Set(gaZioJson),
            noMatchResults.isEmpty,
            singleResults == Set(gaZioJson, gaZioCache),
          )
      },
      test("aiSearch") {
        defer:
          val knowResults = SymbolSearch.aiSearch("zio.cache.Cache").run
          val ambigResults = SymbolSearch.aiSearch("zxcvzxcv").run

          assertTrue(
            knowResults.contains(MavenCentral.GroupArtifact(GroupId("dev.zio"), ArtifactId("zio-cache_3"))),
            ambigResults.isEmpty
          )
      }
    ).provide(
      Scope.default,
      Client.default,
      App.javadocCacheLayer,
      App.blockerLayer,
      App.tmpDirLayer,
      EmbeddedRedis.layer,
      Redis.singleNode,
      ZLayer.succeed[CodecSupplier](SymbolSearch.ProtobufCodecSupplier),
      SymbolSearch.herokuInferenceLayer.orElse(MockInference.layer),
    ),
    suite("integration")(
      test("search with real Redis") {
        defer:
          val springai = Extractor.gav("org.springframework.ai", "spring-ai-mcp", "1.0.1")
          val doccontents = Extractor.javadocContents(springai).run
          SymbolSearch.update(springai.noVersion, doccontents).run

          val notExist = SymbolSearch.search("asdfasdfzxcvzxcv12412421").run
          val fqnMatch = SymbolSearch.search("org.springframework.ai.mcp.AsyncMcpToolCallback").run
          val preMatch = SymbolSearch.search("org.springframework.ai.mcp").run
          val sufMatch = SymbolSearch.search("AsyncMcpToolCallback").run

          assertTrue(
            notExist.isEmpty,
            fqnMatch.contains(springai.noVersion),
            preMatch.contains(springai.noVersion),
            sufMatch.contains(springai.noVersion),
          )
      }
    ).provide(
      Scope.default,
      Client.default,
      App.javadocCacheLayer,
      App.blockerLayer,
      App.tmpDirLayer,
      App.redisConfigLayer,
      App.redisAuthLayer,
      ZLayer.succeed[CodecSupplier](SymbolSearch.ProtobufCodecSupplier),
      SymbolSearch.herokuInferenceLayer.orElse(MockInference.layer),
    ) @@ TestAspect.ifEnvSet("REDIS_URL") @@ TestAspect.timeout(10.seconds),
  ) @@ TestAspect.withLiveSystem @@ TestAspect.sequential
