import com.jamesward.zio_http_guard.{BadActor, CrawlerLimiter}
import com.jamesward.zio_mavencentral.MavenCentral
import zio.*
import zio.http.*
import zio.redis.embedded.EmbeddedRedis
import zio.redis.{CodecSupplier, Redis}

object AppTest extends ZIOAppDefault:

  def run =
    Server.serve(Web.appWithMiddleware).provide(
      App.server,
      Client.default,
      MavenCentral.MavenCentralRepo.live,
      App.latestCacheLayer,
      App.javadocCacheLayer,
      App.sourcesCacheLayer,
      EmbeddedRedis.layer,
      Redis.singleNode,
      ZLayer.succeed[CodecSupplier](SymbolSearch.ProtobufCodecSupplier),
      SymbolSearch.herokuInferenceLayer.orElse(MockInference.layer),
      BadActor.live,
      CrawlerLimiter.layer[MavenCentral.GroupArtifactVersion],
      App.symbolSearchGuardLayer,
    )
