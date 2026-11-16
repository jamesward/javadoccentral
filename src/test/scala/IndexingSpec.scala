import com.jamesward.zio_mavencentral.MavenCentral.{ArtifactId, GroupArtifact, GroupArtifactVersion, GroupId, Version}
import zio.*
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.test.*

import java.sql.Connection
import javax.sql.DataSource

import SymbolSearch.Indexing
import SymbolSearch.Indexing.Skipped

/**
 * The symbol-indexing state machine (`SymbolSearch.Indexing`) against a real (Testcontainers)
 * database. Only the machine is public, so each outcome is produced through it: `Busy` by
 * holding the artifact's guard entry, `Superseded` by indexing a newer version while the
 * machine's write waits on the artifact's row lock.
 */
object IndexingSpec extends ZIOSpecDefault:

  private def gav(v: String) = GroupArtifactVersion(GroupId("com.example"), ArtifactId("judge"), Version(v))
  private val judge = GroupArtifact(GroupId("com.example"), ArtifactId("judge"))
  private def content(fqn: String) = Extractor.Content(fqn, false, fqn, "", "", "")
  private def candidate(v: String, fqns: String*) = Indexing.Candidate(gav(v), fqns.map(content).toSet)

  private val guardLayer: ULayer[SymbolSearch.SymbolSearchGuard] =
    ZLayer(ConcurrentMap.empty[GroupArtifact, Unit].map(SymbolSearch.SymbolSearchGuard(_)))

  override def spec = suite("SymbolSearch.Indexing")(

    test("writes a new artifact, skips the same or an older version, and writes a newer one"):
      defer:
        val first = Indexing.run(candidate("2.0", "com.example.JevJudge")).run
        val same = Indexing.run(candidate("2.0", "com.example.Other")).run
        val older = Indexing.run(candidate("1.0", "com.example.Old")).run
        val newer = Indexing.run(candidate("3.0", "com.example.Newer")).run
        val guard = ZIO.serviceWithZIO[SymbolSearch.SymbolSearchGuard](_.active.toList).run
        assertTrue(
          written(first).contains("2.0"),
          upToDate(same).contains("2.0"),
          upToDate(older).contains("2.0"),
          written(newer).contains("3.0"),
          SymbolIndex.exact("newer").run == Set(judge),
          SymbolIndex.exact("jevjudge").run.isEmpty,
          guard.isEmpty,
        )
    ,
    test("is Busy while another fiber holds the artifact's claim"):
      defer:
        val guard = ZIO.service[SymbolSearch.SymbolSearchGuard].run.active
        guard.put(judge, ()).run
        val result = Indexing.run(candidate("1.0", "com.example.A")).run
        assertTrue(result == (Skipped.Busy: Skipped), SymbolIndex.exact("a").run.isEmpty)
    ,
    test("a write is Superseded when a newer version is indexed while it waits for the row lock"):
      ZIO.scoped:
        defer:
          Indexing.run(candidate("1.0", "com.example.Old")).run
          val dataSource = ZIO.serviceWith[SymbolIndex](_.dataSource).run
          val lock = connection(dataSource).run
          execute(lock, "select id from artifact where group_id = 'com.example' and artifact_id = 'judge' for update").run
          val fiber = Indexing.run(candidate("2.0", "com.example.Middle")).fork.run
          lockWaiters(dataSource).repeat(Schedule.recurUntil[Int](_ > 0) && Schedule.spaced(20.millis)).timeoutFail("never waited")(10.seconds).run
          execute(lock, "update artifact set indexed_version = '3.0' where group_id = 'com.example' and artifact_id = 'judge'").run
          commit(lock).run
          val result = fiber.join.run
          assertTrue(result == (Skipped.Superseded: Skipped), SymbolIndex.exact("middle").run.isEmpty)
    @@ TestAspect.withLiveClock

  ).provide(PostgresContainer.freshDatabase, SymbolIndex.layer, guardLayer)

  // JDBC helpers outside `defer`, which rejects the blocking Java calls' mutable results.
  private def connection(dataSource: DataSource): ZIO[Scope, Throwable, Connection] =
    ZIO.acquireRelease(
      ZIO.attemptBlocking:
        val connection = dataSource.getConnection.nn
        connection.setAutoCommit(false)
        connection
    )(connection => ZIO.succeedBlocking(connection.close()))

  private def execute(connection: Connection, sql: String): Task[Unit] =
    ZIO.attemptBlocking(connection.createStatement().nn.execute(sql)).unit

  private def commit(connection: Connection): Task[Unit] =
    ZIO.attemptBlocking(connection.commit())

  /** Sessions in this database waiting on a lock. */
  private def lockWaiters(dataSource: DataSource): Task[Int] =
    ZIO.scoped:
      connection(dataSource).flatMap: connection =>
        ZIO.attemptBlocking:
          val results = connection.createStatement().nn.executeQuery(
            "select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()"
          ).nn
          results.next()
          results.getInt(1)

  // Final states can't be built here (private constructors), so tests read them instead.
  private def written(result: Indexing.Written | Skipped): Option[String] = result match
    case written: Indexing.Written => Some(written.gav.version.toString)
    case _                         => None

  private def upToDate(result: Indexing.Written | Skipped): Option[String] = result match
    case upToDate: Skipped.UpToDate => Some(upToDate.indexed.toString)
    case _                          => None

  given writtenOrSkipped: CanEqual[Indexing.Written | Skipped, Skipped] = CanEqual.derived
