import com.jamesward.zio_mavencentral.MavenCentral
import zio.*
import zio.redis.{CodecSupplier, Redis, RedisType}
import zio.direct.*
import zio.schema.Schema
import zio.schema.codec.{BinaryCodec, ProtobufCodec}
import zio.stream.ZStream

object SymbolSearch:

  object ProtobufCodecSupplier extends CodecSupplier:
    def get[A: Schema]: BinaryCodec[A] = ProtobufCodec.protobufCodec

  def stringToGroupArtifact(s: String): MavenCentral.GroupArtifact =
    // todo: parse failure handling
    val parts = s.split(':')
    MavenCentral.GroupArtifact(MavenCentral.GroupId(parts(0)), MavenCentral.ArtifactId(parts(1)))

  def groupArtifactToString(ga: MavenCentral.GroupArtifact): String = s"${ga.groupId}:${ga.artifactId}"

  given Schema[MavenCentral.GroupArtifact] = Schema.primitive[String].transform(stringToGroupArtifact, groupArtifactToString)

  def search(symbol: String): ZIO[Redis, Throwable, Set[MavenCentral.GroupArtifact]] =
    defer:
      val redis = ZIO.service[Redis].run
      val pattern = "*" + symbol + "*"

      val allKeys = ZStream.paginateZIO(0L): cursor =>
        redis.scan(cursor, Some(pattern)).returning[String].debug.map:
          case (nextCursor, keys) =>
            val next = if (nextCursor == 0L) None else Some(nextCursor)
            (keys, next)
      .runCollect.run.flatten

      ZIO.foreachPar(allKeys): key =>
        redis.sMembers(key).returning[MavenCentral.GroupArtifact]
      .run
      .flatten
      .toSet

  def update(groupArtifact: MavenCentral.GroupArtifact, symbols: Set[Extractor.Content]): ZIO[Redis, Throwable, Unit] =
    defer:
      val redis = ZIO.service[Redis].run
      ZIO.foreachPar(symbols):
        symbol =>
          redis.sAdd(symbol.fqn, groupArtifact)
      .unit
      .run
