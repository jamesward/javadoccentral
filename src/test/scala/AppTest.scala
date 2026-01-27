import zio.*
import zio.http.*
import zio.redis.embedded.EmbeddedRedis
import zio.redis.{CodecSupplier, Redis}

object AppTest extends ZIOAppDefault:

  def run =
    Server.serve(App.appWithMiddleware).provide(
      App.server,
      Client.default,
      App.blockerLayer,
      App.sourcesBlockerLayer,
      App.latestCacheLayer,
      App.javadocCacheLayer,
      App.sourcesCacheLayer,
      App.tmpDirLayer,
      EmbeddedRedis.layer,
      Redis.singleNode,
      ZLayer.succeed[CodecSupplier](SymbolSearch.ProtobufCodecSupplier),
      SymbolSearch.herokuInferenceLayer.orElse(MockInference.layer),
      BadActor.live,
    )
