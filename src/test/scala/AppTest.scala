import com.jamesward.zio_http_guard.{BadActor, CrawlerLimiter}
import com.jamesward.zio_mavencentral.MavenCentral
import zio.*
import zio.http.*
import zio.redis.{CodecSupplier, Redis}

object AppTest extends ZIOAppDefault:

  // Dev: human-readable text logs at a DEBUG floor (mirrors prod's Loom
  // executor) so the debug-level request / MCP tools-list / notification logs
  // are visible locally. See AppLogging.scala.
  override val bootstrap =
    AppLogging.debug ++ Runtime.enableLoomBasedBlockingExecutor

  def run =
    Server.serve(Web.appWithMiddleware).provide(
      App.server,
      Client.default,
      MavenCentral.MavenCentralRepo.live,
      App.latestCacheLayer,
      App.javadocCacheLayer,
      App.sourcesCacheLayer,
      ValkeyContainer.layer,
      Redis.singleNode,
      ZLayer.succeed[CodecSupplier](SymbolSearch.ProtobufCodecSupplier),
      SymbolSearch.herokuInferenceLayer.orElse(MockInference.layer),
      BadActor.live,
      CrawlerLimiter.layer[MavenCentral.GroupArtifactVersion],
      App.symbolSearchGuardLayer,
    )
