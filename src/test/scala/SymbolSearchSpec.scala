import com.jamesward.zio_mavencentral.MavenCentral
import zio.*
import zio.direct.*
import zio.redis.{CodecSupplier, Redis}
import zio.test.*
import zio.test.TestAspect.*

/**
 * Regression test for the Redis schema incident: if the `_groupArtifacts`
 * index contains a member written with an incompatible schema, `sMembers`
 * fails to decode the whole set. `allGroupArtifacts` should degrade to empty
 * (so search stays available) — and must NOT delete the set, since it usually
 * holds far more good entries than bad; the corrupt members are removed
 * surgically out-of-band.
 */
object SymbolSearchSpec extends ZIOSpecDefault:

  override def spec =
    suite("SymbolSearch.allGroupArtifacts graceful degradation")(

      test("an undecodable member degrades to empty without deleting the good entries"):
        defer:
          val redis = ZIO.service[Redis].run
          // A good member in the compact string form the app actually writes.
          redis.sAdd(SymbolSearch.groupArtifactsKey, "com.example:good").run
          // A bad member written with the record schema (what the buggy deploy
          // wrote) — the compact string reader can't decode a record.
          val bad = MavenCentral.GroupArtifact(MavenCentral.GroupId("dev.zio"), MavenCentral.ArtifactId("zio_3"))
          redis.sAdd(SymbolSearch.groupArtifactsKey, bad).run
          val before = redis.sCard(SymbolSearch.groupArtifactsKey).run

          val result = SymbolSearch.allGroupArtifacts.run
          val after  = redis.sCard(SymbolSearch.groupArtifactsKey).run

          assertTrue(
            before == 2L,   // both members stored
            result.isEmpty, // atomic decode failed -> degraded to empty (no throw)
            after == 2L,    // set NOT deleted -> good entries preserved
          )

    ).provide(
      ValkeyContainer.layer,
      Redis.singleNode,
      ZLayer.succeed[CodecSupplier](SymbolSearch.ProtobufCodecSupplier),
    ) @@ withLiveClock @@ sequential
