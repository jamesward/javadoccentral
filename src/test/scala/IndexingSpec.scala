import com.jamesward.zio_mavencentral.MavenCentral.{ArtifactId, GroupArtifact, GroupArtifactVersion, GroupId, Version}
import zio.*
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.test.*

import SymbolSearch.Indexing
import SymbolSearch.Indexing.Skipped

/** The symbol-indexing state machine (`SymbolSearch.Indexing`) against a real (Testcontainers) database. */
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
        assertTrue(
          first == true,
          same == Skipped.UpToDate(Some(Version("2.0"))),
          older == Skipped.UpToDate(Some(Version("2.0"))),
          newer == true,
          SymbolIndex.exact("newer").run == Set(judge),
          SymbolIndex.exact("jevjudge").run.isEmpty,
        )
    ,
    test("a second claim on an artifact is Busy, and the guard is released when the first claim's scope closes"):
      defer:
        val stale = Indexing.compare(candidate("1.0", "com.example.A")).flatMap:
          case stale: Indexing.Stale => ZIO.succeed(stale)
          case other => ZIO.dieMessage(s"expected Stale, got $other")
        .run
        val second = ZIO.scoped(Indexing.claim(stale) *> Indexing.claim(stale)).run
        val afterwards = ZIO.scoped(Indexing.claim(stale)).run
        val guard = ZIO.serviceWithZIO[SymbolSearch.SymbolSearchGuard](_.active.toList).run
        assertTrue(
          second == Skipped.Busy,
          afterwards.isInstanceOf[Indexing.Claimed],
          guard.isEmpty,
        )

  ).provide(PostgresContainer.freshDatabase, SymbolIndex.layer, guardLayer)

  given CanEqual[Boolean | Skipped, Boolean | Skipped] = CanEqual.derived
  given CanEqual[Indexing.Claimed | Skipped, Skipped] = CanEqual.derived
