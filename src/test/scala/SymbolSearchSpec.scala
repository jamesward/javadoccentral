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
