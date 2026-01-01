import zio.*
import zio.http.*
import zio.redis.{CodecSupplier, Redis}
import zio.redis.embedded.EmbeddedRedis

object AppTest extends ZIOAppDefault:

  def run =
    Server.serve(App.appWithMiddleware).provide(
      App.server,
      Client.default,
      App.blockerLayer,
      App.latestCacheLayer,
      App.javadocCacheLayer,
      App.tmpDirLayer,
      EmbeddedRedis.layer,
      Redis.singleNode,
      ZLayer.succeed[CodecSupplier](SymbolSearch.ProtobufCodecSupplier),
      SymbolSearch.herokuInferenceLayer,
    )
