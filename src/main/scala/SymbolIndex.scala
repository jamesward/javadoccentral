import Db.given
import com.augustnagro.magnum.*
import com.augustnagro.magnum.pg.PgCodec.given
import com.augustnagro.magnum.ziomagnum.*
import com.jamesward.zio_mavencentral.MavenCentral
import com.jamesward.zio_mavencentral.MavenCentral.{ArtifactId, GroupArtifact, GroupArtifactVersion, GroupId, Version}
import zio.*
import zio.direct.*

import javax.sql.DataSource

/** The Postgres-backed symbol index (schema: `db/migration`). Lookups are case-insensitive. */
case class SymbolIndex(dataSource: DataSource)

object SymbolIndex:

  val layer: ZLayer[DataSource, Nothing, SymbolIndex] = ZLayer.fromFunction(SymbolIndex(_))

  private case class Row(groupId: String, artifactId: String) derives DbCodec:
    def groupArtifact: GroupArtifact = GroupArtifact(GroupId(groupId), ArtifactId(artifactId))

  /**
   * Only names that can be symbols are indexed. Doc jars also list pages that
   * aren't: the last-resort `.html` listing yields `index-all` / `package-summary`,
   * and Scaladoc lists static pages with a leading dot (`.zio-mavencentral`).
   * No JVM identifier contains `-`, `/` or whitespace.
   */
  def indexable(fqn: String): Boolean =
    fqn.nonEmpty && fqn.length <= 1000 && !fqn.startsWith(".") && !fqn.exists(c => c == '-' || c == '/' || c.isWhitespace)

  /** `true` when `candidate` should replace the indexed version: only a newer or equal (re-index) version does. */
  def supersedes(candidate: Version, indexed: Option[Version]): Boolean =
    indexed.forall(current => MavenCentral.newestFirst(Seq(candidate, current)).headOption.contains(candidate))

  /** A LIKE pattern matching `text` literally. */
  def escapeLike(text: String): String =
    text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

  /** `%a%b%` for the query `a b`: every whitespace-separated part, in order, anywhere in the name. */
  def containsPattern(query: String): Option[String] =
    val parts = "\\S+".r.findAllIn(query).toList
    Option.when(parts.nonEmpty)(parts.map(escapeLike).mkString("%", "%", "%"))

  private def withDataSource[A](f: DataSource ?=> Task[A]): ZIO[SymbolIndex, Throwable, A] =
    ZIO.serviceWithZIO[SymbolIndex](index => f(using index.dataSource))

  def indexedVersion(groupArtifact: GroupArtifact): ZIO[SymbolIndex, Throwable, Option[Version]] =
    withDataSource:
      sql"""select indexed_version from artifact
            where group_id = ${groupArtifact.groupId.toString} and artifact_id = ${groupArtifact.artifactId.toString}"""
        .zQuery[String].map(_.headOption.map(Version(_)))

  /**
   * Replaces the artifact's symbols with `fqns` (non-symbol names dropped), in one
   * transaction, unless a newer version is already indexed. Returns whether it wrote.
   */
  def replace(gav: GroupArtifactVersion, fqns: Set[String]): ZIO[SymbolIndex, Throwable, Boolean] =
    withDataSource:
      transaction("replace symbols"):
        replaceInTransaction(gav, fqns.filter(indexable).toSeq)

  private def replaceInTransaction(gav: GroupArtifactVersion, fqns: Seq[String])(using DataSource): Task[Boolean] =
    val groupId = gav.groupId.toString
    val artifactId = gav.artifactId.toString
    val version = gav.version.toString
    defer:
      // Row lock: concurrent replaces of one artifact (two dynos, or the backfill) serialize here.
      val indexed = sql"""select indexed_version from artifact
                          where group_id = $groupId and artifact_id = $artifactId for update"""
        .zQuery[String].run.headOption.map(Version(_))
      if !supersedes(gav.version, indexed) then false
      else
        val id = sql"""insert into artifact (group_id, artifact_id, indexed_version) values ($groupId, $artifactId, $version)
                       on conflict (group_id, artifact_id) do update set indexed_version = excluded.indexed_version, indexed_at = now()
                       returning id""".zQuery[Int].run.head
        sql"delete from symbol where artifact_id = $id".zUpdate.run
        sql"insert into symbol (artifact_id, fqn) select $id, unnest($fqns::text[])".zUpdate.run
        true

  private def artifacts(frag: Frag): ZIO[SymbolIndex, Throwable, Set[GroupArtifact]] =
    withDataSource(frag.zQuery[Row].map(_.map(_.groupArtifact).toSet))

  def allGroupArtifacts: ZIO[SymbolIndex, Throwable, Set[GroupArtifact]] =
    artifacts(sql"select group_id, artifact_id from artifact")

  /** Every indexed groupId (the sitemap index). */
  def allGroupIds: ZIO[SymbolIndex, Throwable, Set[GroupId]] =
    withDataSource(sql"select distinct group_id from artifact".zQuery[String].map(_.map(GroupId(_)).toSet))

  /** The indexed artifacts of one groupId (a group's sitemap), via the `(group_id, artifact_id)` unique index. */
  def groupArtifactsIn(groupId: GroupId): ZIO[SymbolIndex, Throwable, Set[GroupArtifact]] =
    artifacts(sql"select group_id, artifact_id from artifact where group_id = ${groupId.toString}")

  /** Artifacts whose `groupId:artifactId` contains every part (any case). No parts match nothing. */
  def groupArtifactsContainingAll(parts: List[String]): ZIO[SymbolIndex, Throwable, Set[GroupArtifact]] =
    if parts.isEmpty then ZIO.succeed(Set.empty)
    else
      val patterns = parts.map(part => "%" + escapeLike(part) + "%")
      artifacts(sql"select group_id, artifact_id from artifact where (group_id || ':' || artifact_id) ilike all($patterns::text[])")

  /**
   * The symbol's simple name (`ObjectMapper`) or full name (`tools.jackson.databind.ObjectMapper`) is the
   * query, or, for a dotted query, the query is its package. An undotted query is not treated as a
   * package: `IO` would otherwise match every class under `io.`.
   */
  def exact(query: String): ZIO[SymbolIndex, Throwable, Set[GroupArtifact]] =
    val lower = query.trim.toLowerCase
    val packagePrefix = if lower.contains('.') then escapeLike(lower) + ".%" else ""
    artifacts(
      sql"""select distinct a.group_id, a.artifact_id from symbol s join artifact a on a.id = s.artifact_id
            where s.name = $lower or lower(s.fqn) = $lower or ($packagePrefix <> '' and lower(s.fqn) like $packagePrefix)"""
    )

  /** A symbol's full name contains the query (its whitespace-separated parts, in order). */
  def containing(query: String): ZIO[SymbolIndex, Throwable, Set[GroupArtifact]] =
    containsPattern(query).fold(ZIO.succeed(Set.empty)): pattern =>
      artifacts(
        sql"""select distinct a.group_id, a.artifact_id from symbol s join artifact a on a.id = s.artifact_id
              where s.fqn ilike $pattern"""
      )

  /** How much less similar than the closest match a name may be and still count. */
  private val similarBand = 0.1

  /**
   * Artifacts with a symbol whose simple name is spelled like the query (pg_trgm similarity): only
   * those whose closest name is within [[similarBand]] of the best match overall, closest first, at
   * most `limit`. Without the band, a typo such as `ObjectMaper` would also return every artifact with
   * a loosely similar name (`ObjectMapperFactory`, `Mapper`).
   */
  def similar(query: String, limit: Int): ZIO[SymbolIndex, Throwable, Set[GroupArtifact]] =
    val lower = query.trim.toLowerCase
    artifacts(
      sql"""with m as (
              select s.artifact_id, max(similarity(s.name, $lower)) as score
              from symbol s where s.name % $lower group by s.artifact_id
            )
            select a.group_id, a.artifact_id from m join artifact a on a.id = m.artifact_id
            where m.score >= (select max(score) from m) - $similarBand
            order by m.score desc
            limit $limit"""
    )
