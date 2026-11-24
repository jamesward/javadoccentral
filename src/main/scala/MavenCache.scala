import Db.given
import com.augustnagro.magnum.*
import com.augustnagro.magnum.pg.PgCodec.given
import com.augustnagro.magnum.ziomagnum.*
import com.jamesward.zio_mavencentral.MavenCentral
import com.jamesward.zio_mavencentral.MavenCentral.{ArtifactId, GroupArtifact, GroupArtifactVersion, GroupId, GroupIdNotFoundError, GroupIdOrArtifactIdNotFoundError, MavenCentralRepo, Version}
import zio.*
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.http.Header

import java.time.{OffsetDateTime, ZoneOffset}
import javax.sql.DataSource

/**
 * A Postgres cache in front of Maven Central (schema: `V2__maven_cache.sql`), so
 * most requests answer in milliseconds and repo1 sees at most a conditional
 * revalidation per artifact per hour:
 *
 *  - '''Versions''' (`maven-metadata.xml`): served from Postgres; a row older than
 *    [[metadataTtl]] (or [[fallbackTtl]] when the GCS mirror served it) is served
 *    and refreshed in the background with a conditional request. A version the row
 *    doesn't list refreshes it at once, at most every [[unknownVersionRefresh]].
 *  - '''Group listings''': the same, with [[listingTtl]] and [[unknownArtifactRefresh]].
 *  - '''Not found''': only repo1's answer (the library fails with a temporary error
 *    otherwise), kept for [[notFoundTtl]], or [[malformedTtl]] for coordinates Maven
 *    Central can't parse. A missing javadoc / sources jar is kept for
 *    [[missingJarTtl]] when its version is published, else [[notFoundTtl]].
 *
 * One refresh per key runs at a time. If Postgres is unavailable, lookups go to
 * Maven Central directly (logged). A stale row is served when Maven Central
 * can't be reached.
 */
case class MavenCache(dataSource: DataSource, refreshing: ConcurrentMap[String, Promise[Nothing, Unit]])

object MavenCache:

  val metadataTtl: Duration           = 1.hour
  val fallbackTtl: Duration           = 10.minutes
  val listingTtl: Duration            = 24.hours
  val unknownVersionRefresh: Duration = 5.minutes
  val unknownArtifactRefresh: Duration = 10.minutes
  val notFoundTtl: Duration           = 1.hour
  val malformedTtl: Duration          = 1.day
  val missingJarTtl: Duration         = 7.days
  /** Rows not read for this long are pruned. */
  val unusedFor: Duration             = 30.days

  /** Maven Central couldn't be reached, and nothing usable is cached. */
  case class Unavailable(reason: String)

  type VersionsError = GroupIdOrArtifactIdNotFoundError | Unavailable
  type ArtifactsError = GroupIdNotFoundError | Unavailable

  val layer: ZLayer[DataSource, Nothing, MavenCache] =
    ZLayer.fromZIO:
      defer:
        val ds = ZIO.service[DataSource].run
        MavenCache(ds, ConcurrentMap.empty[String, Promise[Nothing, Unit]].run)

  private case class MetadataRow(versions: Vector[String], etag: Option[String], lastModified: Option[OffsetDateTime], fromFallback: Boolean, fetchedAt: OffsetDateTime) derives DbCodec
  private case class ListingRow(artifactIds: Vector[String], fromFallback: Boolean, fetchedAt: OffsetDateTime) derives DbCodec

  /** A cached value and how old it is. */
  private case class Cached[A](value: A, age: Duration, fromFallback: Boolean)

  // ---- Coordinates Maven Central can't parse (#270): kept as "not found" for longer. ----

  def looksMalformed(s: String): Boolean =
    s.length > 300 || s.exists(c => c.isControl || c == '\uFFFD')

  /** Postgres `text` can't hold NUL, so such keys skip the cache and go to Maven Central. */
  private def storable(parts: Any*): Boolean = !parts.exists(_.toString.contains('\u0000'))

  // ---- Versions ----

  /** An artifact's versions, newest first. */
  def versions(ga: GroupArtifact): ZIO[MavenCache & MavenCentralRepo, VersionsError, Seq[Version]] =
    cachedVersions(ga, None)

  /**
   * An artifact's versions, refreshed at once if they don't include `version`
   * and haven't been fetched in the last [[unknownVersionRefresh]]: a version
   * released after the cached copy shows up the first time someone asks for it.
   */
  def versionsIncluding(ga: GroupArtifact, version: Version): ZIO[MavenCache & MavenCentralRepo, VersionsError, Seq[Version]] =
    cachedVersions(ga, Some(version))

  /** The latest release (or version of any kind, with `includePreReleases`). */
  def latest(ga: GroupArtifact, includePreReleases: Boolean = false): ZIO[MavenCache & MavenCentralRepo, Extractor.LatestError, Version] =
    versions(ga)
      .mapError:
        case e: GroupIdOrArtifactIdNotFoundError => e
        case Unavailable(reason)                 => Extractor.LatestUnavailable(ga, reason)
      .flatMap(vs => ZIO.fromOption(MavenCentral.latestOf(vs, includePreReleases)).orElseFail(Extractor.LatestNotFound(ga)))

  private def gaKey(ga: GroupArtifact): String = s"${ga.groupId}:${ga.artifactId}"

  private def cachedVersions(ga: GroupArtifact, wanted: Option[Version]): ZIO[MavenCache & MavenCentralRepo, VersionsError, Seq[Version]] =
    if !storable(ga.groupId, ga.artifactId) then uncachedVersions(ga)
    else defer:
      val missing = isNotFound("artifact", gaKey(ga)).run
      if missing then ZIO.fail(GroupIdOrArtifactIdNotFoundError(ga.groupId, ga.artifactId)).run
      else
        readMetadata(ga).run match
          case None => fetchVersions(ga, None).run
          case Some(cached) =>
            val versions = cached.value.versions.map(Version(_))
            val ttl = if cached.fromFallback then fallbackTtl else metadataTtl
            val unknown = wanted.exists(v => !versions.contains(v)) && cached.age > unknownVersionRefresh
            if unknown then fetchVersions(ga, Some(cached.value)).orElseSucceed(versions).run
            else
              if cached.age > ttl then refreshInBackground(s"metadata $ga", fetchVersions(ga, Some(cached.value)).unit).run
              versions

  private def uncachedVersions(ga: GroupArtifact): ZIO[MavenCentralRepo, VersionsError, Seq[Version]] =
    MavenCentral.mavenMetadata(ga.groupId, ga.artifactId).map(m => MavenCentral.versionsOf(m.value)).catchAll:
      case e: GroupIdOrArtifactIdNotFoundError => ZIO.fail(e)
      case t: Throwable                        => ZIO.fail(Unavailable(Option(t.getMessage).getOrElse(t.getClass.getSimpleName.nn)))

  /** Fetches (or revalidates) from Maven Central and stores the result; one at a time per artifact. */
  private def fetchVersions(ga: GroupArtifact, cached: Option[MetadataRow]): ZIO[MavenCache & MavenCentralRepo, VersionsError, Seq[Version]] =
    val etag = cached.flatMap(_.etag).flatMap(e => Header.ETag.parse(e).toOption)
    val lastModified = cached.flatMap(_.lastModified).map(_.atZoneSameInstant(ZoneOffset.UTC).nn)
    MavenCentral.mavenMetadataIfModified(ga.groupId, ga.artifactId, etag, lastModified)
      .foldZIO(
        {
          case e: GroupIdOrArtifactIdNotFoundError =>
            val ttl = if looksMalformed(ga.groupId.toString) || looksMalformed(ga.artifactId.toString) then malformedTtl else notFoundTtl
            recordNotFound("artifact", gaKey(ga), ttl) *> deleteMetadata(ga) *> ZIO.fail(e)
          case t: Throwable =>
            // Maven Central can't be reached: serve what's cached, however old.
            cached.fold(ZIO.fail(Unavailable(Option(t.getMessage).getOrElse(t.getClass.getSimpleName.nn))))(row => ZIO.succeed(row.versions.map(Version(_))))
        },
        {
          case None =>
            touchMetadata(ga).as(cached.fold(Seq.empty[Version])(_.versions.map(Version(_))))
          case Some(meta) =>
            val versions = MavenCentral.versionsOf(meta.value)
            val fromFallback = !meta.servedBy.exists(_.encode.startsWith(MavenCentral.primaryUri.encode))
            writeMetadata(ga, versions, meta.maybeEtag.map(_.renderedValue), meta.maybeLastModified.map(_.toOffsetDateTime.nn), fromFallback).as(versions)
        },
      )

  // ---- Group listings ----

  /** A groupId's artifactIds. */
  def artifacts(groupId: GroupId): ZIO[MavenCache & MavenCentralRepo, ArtifactsError, Seq[ArtifactId]] =
    cachedArtifacts(groupId, None)

  /** A groupId's artifactIds, refreshed at once if they don't include `artifactId` (at most every [[unknownArtifactRefresh]]). */
  def artifactsIncluding(groupId: GroupId, artifactId: ArtifactId): ZIO[MavenCache & MavenCentralRepo, ArtifactsError, Seq[ArtifactId]] =
    cachedArtifacts(groupId, Some(artifactId))

  private def cachedArtifacts(groupId: GroupId, wanted: Option[ArtifactId]): ZIO[MavenCache & MavenCentralRepo, ArtifactsError, Seq[ArtifactId]] =
    if !storable(groupId) then
      MavenCentral.searchArtifacts(groupId).map(_.value).catchAll:
        case e: GroupIdNotFoundError => ZIO.fail(e)
        case t: Throwable            => ZIO.fail(Unavailable(Option(t.getMessage).getOrElse(t.getClass.getSimpleName.nn)))
    else defer:
      val missing = isNotFound("group", groupId.toString).run
      if missing then ZIO.fail(GroupIdNotFoundError(groupId)).run
      else
        readListing(groupId).run match
          case None => fetchArtifacts(groupId, None).run
          case Some(cached) =>
            val ids = cached.value.artifactIds.map(ArtifactId(_))
            val ttl = if cached.fromFallback then fallbackTtl else listingTtl
            val unknown = wanted.exists(a => !ids.contains(a)) && cached.age > unknownArtifactRefresh
            if unknown then fetchArtifacts(groupId, Some(cached.value)).orElseSucceed(ids).run
            else
              if cached.age > ttl then refreshInBackground(s"listing $groupId", fetchArtifacts(groupId, Some(cached.value)).unit).run
              ids

  private def fetchArtifacts(groupId: GroupId, cached: Option[ListingRow]): ZIO[MavenCache & MavenCentralRepo, ArtifactsError, Seq[ArtifactId]] =
    MavenCentral.searchArtifacts(groupId).foldZIO(
      {
        case e: GroupIdNotFoundError =>
          val ttl = if looksMalformed(groupId.toString) then malformedTtl else notFoundTtl
          recordNotFound("group", groupId.toString, ttl) *> deleteListing(groupId) *> ZIO.fail(e)
        case t: Throwable =>
          cached.fold(ZIO.fail(Unavailable(Option(t.getMessage).getOrElse(t.getClass.getSimpleName.nn))))(row => ZIO.succeed(row.artifactIds.map(ArtifactId(_))))
      },
      listing =>
        val fromFallback = !listing.servedBy.exists(_.encode.startsWith(MavenCentral.primaryUri.encode))
        writeListing(groupId, listing.value, fromFallback).as(listing.value),
    )

  // ---- Missing jars ----

  /** Whether repo1 recently said this version has no `classifier` jar. */
  def isMissingJar(classifier: String, gav: GroupArtifactVersion): ZIO[MavenCache, Nothing, Boolean] =
    if storable(gav) then isNotFound(classifier, gav.toString) else ZIO.succeed(false)

  /** Remembers that repo1 has no `classifier` jar for this version: for [[missingJarTtl]] when the version is published, else [[notFoundTtl]]. */
  def recordMissingJar(classifier: String, gav: GroupArtifactVersion): ZIO[MavenCache, Nothing, Unit] =
    if !storable(gav) then ZIO.unit
    else defer:
      val cached = readMetadata(gav.noVersion).run
      val published = cached.exists(_.value.versions.contains(gav.version.toString))
      recordNotFound(classifier, gav.toString, if published then missingJarTtl else notFoundTtl).run

  // ---- Background refresh: one per key ----

  private def refreshInBackground(key: String, refresh: ZIO[MavenCache & MavenCentralRepo, Any, Unit]): ZIO[MavenCache & MavenCentralRepo, Nothing, Unit] =
    defer:
      val cache   = ZIO.service[MavenCache].run
      val promise = Promise.make[Nothing, Unit].run
      val running = cache.refreshing.putIfAbsent(key, promise).run
      if running.isEmpty then
        refresh.ignoreLogged
          .ensuring(cache.refreshing.remove(key) *> promise.succeed(()))
          .forkDaemon.run
      ()

  // ---- Postgres ----

  private def withDb[A](what: String, fallback: => A)(f: DataSource ?=> Task[A]): ZIO[MavenCache, Nothing, A] =
    ZIO.serviceWithZIO[MavenCache](cache => f(using cache.dataSource))
      .catchAllCause(cause => ZIO.logWarningCause(s"Maven cache $what failed", cause).as(fallback))

  private def age(at: OffsetDateTime): UIO[Duration] =
    Clock.instant.map(now => Duration.fromInterval(at.toInstant.nn, now))

  private def readMetadata(ga: GroupArtifact): ZIO[MavenCache, Nothing, Option[Cached[MetadataRow]]] =
    defer:
      val row = withDb("read", Option.empty[MetadataRow]):
        sql"""select versions, etag, last_modified, from_fallback, fetched_at from artifact_metadata
              where group_id = ${ga.groupId.toString} and artifact_id = ${ga.artifactId.toString}""".zQuery[MetadataRow].map(_.headOption)
      .run
      ZIO.foreachDiscard(row)(_ => touchAccessed(ga)).run
      ZIO.foreach(row)(r => age(r.fetchedAt).map(Cached(r, _, r.fromFallback))).run

  private def writeMetadata(ga: GroupArtifact, versions: Seq[Version], etag: Option[String], lastModified: Option[OffsetDateTime], fromFallback: Boolean): ZIO[MavenCache, Nothing, Unit] =
    val vs = versions.map(_.toString).toVector
    withDb("write", ()):
      sql"""insert into artifact_metadata (group_id, artifact_id, versions, etag, last_modified, from_fallback, fetched_at)
            values (${ga.groupId.toString}, ${ga.artifactId.toString}, $vs, $etag, $lastModified, $fromFallback, now())
            on conflict (group_id, artifact_id) do update set versions = excluded.versions, etag = excluded.etag,
              last_modified = excluded.last_modified, from_fallback = excluded.from_fallback, fetched_at = now()""".zUpdate.unit

  private def touchMetadata(ga: GroupArtifact): ZIO[MavenCache, Nothing, Unit] =
    withDb("touch", ()):
      sql"""update artifact_metadata set fetched_at = now()
            where group_id = ${ga.groupId.toString} and artifact_id = ${ga.artifactId.toString}""".zUpdate.unit

  private def deleteMetadata(ga: GroupArtifact): ZIO[MavenCache, Nothing, Unit] =
    withDb("delete", ()):
      sql"""delete from artifact_metadata where group_id = ${ga.groupId.toString} and artifact_id = ${ga.artifactId.toString}""".zUpdate.unit

  /** Marks a row read, at most once a day (so a hot row doesn't write on every read). */
  private def touchAccessed(ga: GroupArtifact): ZIO[MavenCache, Nothing, Unit] =
    withDb("touch", ()):
      sql"""update artifact_metadata set accessed_at = now()
            where group_id = ${ga.groupId.toString} and artifact_id = ${ga.artifactId.toString}
              and accessed_at < now() - interval '1 day'""".zUpdate.unit

  private def readListing(groupId: GroupId): ZIO[MavenCache, Nothing, Option[Cached[ListingRow]]] =
    defer:
      val row = withDb("read", Option.empty[ListingRow]):
        sql"""select artifact_ids, from_fallback, fetched_at from group_listing where group_id = ${groupId.toString}""".zQuery[ListingRow].map(_.headOption)
      .run
      ZIO.foreachDiscard(row): _ =>
        withDb("touch", ()):
          sql"""update group_listing set accessed_at = now()
                where group_id = ${groupId.toString} and accessed_at < now() - interval '1 day'""".zUpdate.unit
      .run
      ZIO.foreach(row)(r => age(r.fetchedAt).map(Cached(r, _, r.fromFallback))).run

  private def writeListing(groupId: GroupId, artifactIds: Seq[ArtifactId], fromFallback: Boolean): ZIO[MavenCache, Nothing, Unit] =
    val ids = artifactIds.map(_.toString).toVector
    withDb("write", ()):
      sql"""insert into group_listing (group_id, artifact_ids, from_fallback, fetched_at)
            values (${groupId.toString}, $ids, $fromFallback, now())
            on conflict (group_id) do update set artifact_ids = excluded.artifact_ids, from_fallback = excluded.from_fallback, fetched_at = now()""".zUpdate.unit

  private def deleteListing(groupId: GroupId): ZIO[MavenCache, Nothing, Unit] =
    withDb("delete", ()):
      sql"""delete from group_listing where group_id = ${groupId.toString}""".zUpdate.unit

  private def isNotFound(kind: String, key: String): ZIO[MavenCache, Nothing, Boolean] =
    withDb("read", false):
      sql"""select 1 from not_found where kind = $kind and key = $key and expires_at > now()""".zQuery[Int].map(_.nonEmpty)

  private def recordNotFound(kind: String, key: String, ttl: Duration): ZIO[MavenCache, Nothing, Unit] =
    val seconds = ttl.toSeconds
    withDb("write", ()):
      sql"""insert into not_found (kind, key, expires_at) values ($kind, $key, now() + make_interval(secs => $seconds))
            on conflict (kind, key) do update set expires_at = excluded.expires_at""".zUpdate.unit

  /** Deletes rows not read in [[unusedFor]] and expired "not found"s. */
  val prune: ZIO[MavenCache, Nothing, Unit] =
    val days = unusedFor.toDays.toInt
    defer:
      val metadata = withDb("prune", 0)(sql"delete from artifact_metadata where accessed_at < now() - make_interval(days => $days)".zUpdate).run
      val listings = withDb("prune", 0)(sql"delete from group_listing where accessed_at < now() - make_interval(days => $days)".zUpdate).run
      val notFound = withDb("prune", 0)(sql"delete from not_found where expires_at < now()".zUpdate).run
      ZIO.logAnnotate(
        LogAnnotation("metadata", metadata.toString), LogAnnotation("listings", listings.toString), LogAnnotation("notFound", notFound.toString),
      )(ZIO.logInfo("Maven cache pruned")).run
