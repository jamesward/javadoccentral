import Db.given
import com.augustnagro.magnum.*
import com.augustnagro.magnum.ziomagnum.*
import com.jamesward.zio_mavencentral.{JarCache, MavenCentral}
import com.jamesward.zio_mavencentral.MavenCentral.{ArtifactId, GroupArtifact, GroupArtifactVersion, GroupId, JarMeta, Listings, MavenCentralRepo, Mirror, NotFoundError}
import nl.vroste.rezilience.Bulkhead
import zio.*
import zio.direct.*
import zio.http.{Client, Handler, MediaType, Method, Request, Response, Routes, Server, Status, URL, trailing}
import zio.test.*

import java.io.File
import java.nio.file.Files
import javax.sql.DataSource

/**
 * The Postgres cache in front of Maven Central, against a fake repo1 and GCS
 * mirror on a local server: what is served from Postgres, what reaches
 * upstream (and when), and that only repo1's "not found" is remembered.
 */
object MavenCacheSpec extends ZIOSpecDefault:

  given CanEqual[String, String] = CanEqual.derived

  type Answer = (String, Request) => Response

  /** The fake mirrors: answers per mirror, and every request they got (mirror, path). */
  final case class Fakes(answers: Ref[Map[String, Answer]], seen: Ref[Chunk[(String, String)]], port: Int):
    def set(mirror: String)(answer: Answer): UIO[Unit] = answers.update(_ + (mirror -> answer))
    def asked(mirror: String): UIO[Chunk[String]] = seen.get.map(_.collect { case (m, p) if m == mirror => p })
    def clearSeen: UIO[Unit] = seen.set(Chunk.empty)
    def url(mirror: String): URL = URL.decode(s"http://localhost:$port/$mirror/maven2/").toOption.get

  private val fakesLayer: ZLayer[Server, Nothing, Fakes] =
    ZLayer.fromZIO:
      defer:
        val answers = Ref.make(Map.empty[String, Answer]).run
        val seen    = Ref.make(Chunk.empty[(String, String)]).run
        val handler = Handler.fromFunctionZIO[Request]: request =>
          val segments = request.path.segments
          val mirror   = segments.headOption.getOrElse("")
          val path     = segments.drop(2).mkString("/") + (if request.path.hasTrailingSlash then "/" else "")
          seen.update(_ :+ (mirror -> path)) *> answers.get.map(_.get(mirror).fold(Response.status(Status.NotFound))(_(path, request)))
        val port = Server.install(Routes(Method.ANY / trailing -> handler)).run
        Fakes(answers, seen, port)

  private val repoLayer: ZLayer[Client & Fakes, Nothing, MavenCentralRepo] =
    ZLayer.service[Fakes].flatMap: env =>
      val fakes = env.get
      MavenCentralRepo.custom(
        mirrors = List(
          // Rows from this fake repo1 are marked `from_fallback` (only the real repo1's URL counts),
          // which only shortens their TTL; the tests age rows explicitly.
          Mirror(fakes.url("repo1"), authoritative = true, Listings.Html),
          Mirror(fakes.url("gcs"), authoritative = false, Listings.Unsupported),
        ),
        requestTimeout = 2.seconds,
      )

  private def metadata(versions: String*): String =
    s"<metadata><versioning><versions>${versions.map(v => s"<version>$v</version>").mkString}</versions></versioning></metadata>"

  private val ga = GroupArtifact(GroupId("com.foo"), ArtifactId("bar"))
  private val metadataPath = "com/foo/bar/maven-metadata.xml"

  /** repo1's HTML listing of `com/foo/` with these artifactIds. */
  private def listing(names: String*): Answer = (p, _) =>
    if p == "com/foo/" then Response.text(names.map(n => s"<a href=\"$n/\">$n/</a>").mkString("\n")).contentType(MediaType.text.html)
    else Response.status(Status.NotFound)

  private def failure[E](exit: Exit[E, ?]): Option[E] = exit.causeOption.flatMap(_.failureOption)

  /** Ages the cached rows, as if fetched `ago` ago. */
  private def age(ago: Duration): ZIO[DataSource, Throwable, Unit] =
    ZIO.serviceWithZIO[DataSource]: ds =>
      given DataSource = ds
      val secs = ago.toSeconds
      sql"update artifact_metadata set fetched_at = now() - make_interval(secs => $secs)".zUpdate *>
        sql"update group_listing set fetched_at = now() - make_interval(secs => $secs)".zUpdate.unit

  private def clearDb: ZIO[DataSource, Throwable, Unit] =
    ZIO.serviceWithZIO[DataSource]: ds =>
      given DataSource = ds
      sql"truncate artifact_metadata, group_listing, not_found".zUpdate.unit

  /** Waits until upstream has had `n` requests for `path` (a background refresh). */
  private def awaitAsked(fakes: Fakes, mirror: String, path: String, n: Int): UIO[Unit] =
    fakes.asked(mirror).repeatUntil(_.count(_ == path) >= n).unit.timeout(5.seconds).unit

  def spec = suite("MavenCache")(
    test("versions: the second read comes from Postgres, not upstream") {
      defer:
        val fakes = ZIO.service[Fakes].run
        clearDb.run
        fakes.set("repo1")(((_, _) => Response.text(metadata("1.0", "1.1")))).run
        val first  = MavenCache.versions(ga).run
        fakes.clearSeen.run
        val second = MavenCache.versions(ga).run
        val asked  = fakes.asked("repo1").run
        assertTrue(first.map(_.toString) == Seq("1.1", "1.0"), second == first, asked.isEmpty)
    },
    test("a stale row is served at once and revalidated in the background with a 304") {
      defer:
        val fakes = ZIO.service[Fakes].run
        clearDb.run
        val answer: Answer = (_, request) =>
          if request.headers.get("If-None-Match").contains("\"v1\"") then Response.status(Status.NotModified)
          else Response.text(metadata("1.0")).addHeader("ETag", "\"v1\"")
        fakes.set("repo1")(answer).run
        MavenCache.versions(ga).run
        age(2.hours).run
        fakes.clearSeen.run
        val (took, stale) = MavenCache.versions(ga).timed.run
        awaitAsked(fakes, "repo1", metadataPath, 1).run
        // Give the refresh a moment to store its result.
        val refreshed = ZIO.serviceWithZIO[DataSource] { ds =>
          given DataSource = ds
          sql"select count(*) from artifact_metadata where fetched_at > now() - interval '1 minute'".zQuery[Long].map(_.head)
        }.repeatUntil(_ == 1L).timeout(5.seconds).run
        assertTrue(stale.map(_.toString) == Seq("1.0"), took < 1.second, refreshed.contains(1L))
    },
    test("a version the cached row doesn't list refreshes it at once, but not more than every 5 minutes") {
      defer:
        val fakes = ZIO.service[Fakes].run
        clearDb.run
        fakes.set("repo1")(((_, _) => Response.text(metadata("1.0")))).run
        MavenCache.versions(ga).run
        fakes.set("repo1")(((_, _) => Response.text(metadata("1.0", "2.0")))).run
        // Fresh row: no refresh yet.
        val tooSoon = MavenCache.versionsIncluding(ga, MavenCentral.Version("2.0")).run
        age(6.minutes).run
        val refreshed = MavenCache.versionsIncluding(ga, MavenCentral.Version("2.0")).run
        assertTrue(!tooSoon.contains(MavenCentral.Version("2.0")), refreshed.contains(MavenCentral.Version("2.0")))
    },
    test("repo1's 'not found' is remembered; GCS's isn't") {
      defer:
        val fakes = ZIO.service[Fakes].run
        clearDb.run
        fakes.set("repo1")(((_, _) => Response.status(Status.NotFound))).run
        val first = MavenCache.versions(ga).exit.run
        fakes.clearSeen.run
        val second = MavenCache.versions(ga).exit.run
        val askedAgain = fakes.asked("repo1").run
        // repo1 down, GCS doesn't have it: unavailable, and nothing remembered.
        val other = GroupArtifact(GroupId("com.foo"), ArtifactId("new"))
        fakes.set("repo1")(((_, _) => Response.status(Status.ServiceUnavailable))).run
        val onFallback = MavenCache.versions(other).exit.run
        fakes.set("repo1")(((_, _) => Response.text(metadata("0.1")))).run
        val afterRecovery = MavenCache.versions(other).run
        assertTrue(
          failure(first).exists(_.isInstanceOf[MavenCentral.GroupIdOrArtifactIdNotFoundError]),
          failure(second).exists(_.isInstanceOf[MavenCentral.GroupIdOrArtifactIdNotFoundError]),
          askedAgain.isEmpty,
          failure(onFallback).exists(_.isInstanceOf[MavenCache.Unavailable]),
          afterRecovery.map(_.toString) == Seq("0.1"),
        )
    },
    test("Maven Central down: a stale row is still served") {
      defer:
        val fakes = ZIO.service[Fakes].run
        clearDb.run
        fakes.set("repo1")(((_, _) => Response.text(metadata("1.0")))).run
        MavenCache.versions(ga).run
        age(6.minutes).run
        fakes.set("repo1")(((_, _) => Response.status(Status.ServiceUnavailable))).run
        fakes.set("gcs")(((_, _) => Response.status(Status.ServiceUnavailable))).run
        val served = MavenCache.versionsIncluding(ga, MavenCentral.Version("9.9")).run
        assertTrue(served.map(_.toString) == Seq("1.0"))
    },
    test("listings: cached, and refreshed for an artifact the cached listing doesn't have") {
      defer:
        val fakes = ZIO.service[Fakes].run
        clearDb.run
        fakes.set("repo1")(listing("bar")).run
        val first = MavenCache.artifacts(GroupId("com.foo")).run
        fakes.set("repo1")(listing("bar", "baz")).run
        val cached = MavenCache.artifacts(GroupId("com.foo")).run
        age(11.minutes).run
        val refreshed = MavenCache.artifactsIncluding(GroupId("com.foo"), ArtifactId("baz")).run
        assertTrue(first.map(_.toString) == Seq("bar"), cached == first, refreshed.map(_.toString) == Seq("bar", "baz"))
    },
    test("a missing jar is remembered, so the next request doesn't download") {
      defer:
        clearDb.run
        val downloads = Ref.make(0).run
        val dir       = ZIO.attemptBlockingIO(Files.createTempDirectory("maven-cache-spec").nn.toFile).orDie.run
        val download: (GroupArtifactVersion, File) => ZIO[Client & MavenCentralRepo, JarCache.DownloadError, JarMeta] =
          (gav, _) => downloads.update(_ + 1) *> ZIO.fail(NotFoundError(gav.groupId, gav.artifactId, gav.version))
        val jarCache  = JarCache.make(dir, download).run
        val misses    = Bulkhead.make(4, 4).run
        val mavenCache = ZIO.service[MavenCache].run
        val cache     = Extractor.JavadocCache(jarCache, misses, missing = Some(Extractor.MissingJars(mavenCache, "javadoc")))
        val gav       = GroupArtifactVersion(ga.groupId, ga.artifactId, MavenCentral.Version("1.0"))
        val first     = cache.get(gav).exit.run
        val second    = cache.get(gav).exit.run
        val count     = downloads.get.run
        assertTrue(
          failure(first).exists(_.isInstanceOf[NotFoundError]),
          failure(second).exists(_.isInstanceOf[NotFoundError]),
          count == 1,
        )
    },
    test("prune drops rows not read in 30 days and expired 'not found's") {
      defer:
        val fakes = ZIO.service[Fakes].run
        clearDb.run
        fakes.set("repo1")(((_, _) => Response.text(metadata("1.0")))).run
        MavenCache.versions(ga).run
        ZIO.serviceWithZIO[DataSource] { ds =>
          given DataSource = ds
          sql"update artifact_metadata set accessed_at = now() - interval '31 days'".zUpdate *>
            sql"insert into not_found (kind, key, expires_at) values ('artifact', 'x:y', now() - interval '1 second')".zUpdate
        }.run
        MavenCache.prune.run
        val counts = ZIO.serviceWithZIO[DataSource] { ds =>
          given DataSource = ds
          sql"select count(*) from artifact_metadata".zQuery[Long] <*> sql"select count(*) from not_found".zQuery[Long]
        }.run
        assertTrue(counts._1.head == 0L, counts._2.head == 0L)
    },
  ).provide(
    Server.defaultWith(_.onAnyOpenPort),
    Client.default,
    fakesLayer,
    repoLayer,
    PostgresContainer.freshDatabase,
    MavenCache.layer,
    Scope.default,
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(3.minutes)
