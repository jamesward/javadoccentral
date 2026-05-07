import zio.*
import zio.http.*
import zio.redis.embedded.EmbeddedRedis
import zio.redis.{CodecSupplier, Redis}

object AppTest extends ZIOAppDefault:

  def run =
    Server.serve(Web.appWithMiddleware).provide(
      App.server,
      Client.default,
      Scope.default,
      App.latestCacheLayer,
      App.javadocCacheLayer,
      App.sourcesCacheLayer,
      App.tmpDirLayer,
      App.fetchBlockerLayer,
      EmbeddedRedis.layer,
      Redis.singleNode,
      ZLayer.succeed[CodecSupplier](SymbolSearch.ProtobufCodecSupplier),
      SymbolSearch.herokuInferenceLayer.orElse(MockInference.layer),
      BadActor.live,
      Web.crawlerGavLimiterLayer,
      App.symbolSearchGuardLayer,
    )
