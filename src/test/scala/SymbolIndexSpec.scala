import com.jamesward.zio_mavencentral.MavenCentral.{ArtifactId, GroupArtifact, GroupArtifactVersion, GroupId, Version}
import zio.*
import zio.direct.*
import zio.test.*

/** The Postgres symbol index against a real (Testcontainers) database: lookups, re-indexing, and artifact-name search. */
object SymbolIndexSpec extends ZIOSpecDefault:

  private def gav(g: String, a: String, v: String) = GroupArtifactVersion(GroupId(g), ArtifactId(a), Version(v))
  private def ga(g: String, a: String) = GroupArtifact(GroupId(g), ArtifactId(a))

  private val judge = ga("com.example", "judge")
  private val other = ga("org.other", "lib")

  private val seedTwo =
    SymbolIndex.replace(gav("com.example", "judge", "1.0"), Set("com.example.JevJudge", "com.example.Outer.Builder")) *>
      SymbolIndex.replace(gav("org.other", "lib", "2.0"), Set("org.other.JudgeFactory"))

  override def spec = suite("SymbolIndex")(

    test("exact lookups match a simple name, full name or package, in any case"):
      defer:
        seedTwo.run
        assertTrue(
          SymbolIndex.exact("jevjudge").run == Set(judge),
          SymbolIndex.exact("JEVJUDGE").run == Set(judge),
          SymbolIndex.exact("com.example.JevJudge").run == Set(judge),
          SymbolIndex.exact("com.example").run == Set(judge),
          SymbolIndex.exact("Judge").run.isEmpty,
        )
    ,
    test("containing lookups match any part of a full name; similar lookups match close spellings"):
      defer:
        seedTwo.run
        assertTrue(
          SymbolIndex.containing("udg").run == Set(judge, other),
          SymbolIndex.containing("Outer.Builder").run == Set(judge),
          SymbolIndex.containing("example builder").run == Set(judge),
          SymbolIndex.similar("JevJudg", 20).run == Set(judge),
          SymbolIndex.similar("JudgeFactroy", 20).run == Set(other),
        )
    ,
    test("LIKE wildcards in a query match literally"):
      defer:
        seedTwo.run
        assertTrue(
          SymbolIndex.containing("%").run.isEmpty,
          SymbolIndex.containing("_").run.isEmpty,
          SymbolIndex.exact("%").run.isEmpty,
        )
    ,
    test("re-indexing replaces an artifact's symbols, and an older version doesn't overwrite a newer one"):
      defer:
        val v2 = SymbolIndex.replace(gav("com.example", "judge", "2.0"), Set("com.example.Kept", "com.example.Removed")).run
        val v1 = SymbolIndex.replace(gav("com.example", "judge", "1.0"), Set("com.example.Old")).run
        val oldNotWritten = SymbolIndex.exact("old").run.isEmpty
        val v3 = SymbolIndex.replace(gav("com.example", "judge", "3.0"), Set("com.example.Kept")).run
        assertTrue(
          v2, !v1, oldNotWritten, v3,
          SymbolIndex.exact("kept").run == Set(judge),
          SymbolIndex.exact("removed").run.isEmpty,
          SymbolIndex.indexedVersion(judge).run.contains(Version("3.0")),
        )
    ,
    test("doc pages that aren't symbols are not indexed"):
      defer:
        SymbolIndex.replace(gav("com.example", "judge", "1.0"), Set("index-all", "package-summary", ".zio-mavencentral", "com.example.Real")).run
        assertTrue(
          SymbolIndex.exact("index-all").run.isEmpty,
          SymbolIndex.containing("summary").run.isEmpty,
          SymbolIndex.containing("mavencentral").run.isEmpty,
          SymbolIndex.exact("real").run == Set(judge),
        )
    ,
    test("artifact-name search: every part must match, and a trailing version is dropped only when the full query matches nothing"):
      val databind2 = ga("com.fasterxml.jackson.core", "jackson-databind")
      val databind3 = ga("tools.jackson.core", "jackson-databind")
      val nullable  = ga("org.openapitools", "jackson-databind-nullable")
      val springAi  = ga("org.springframework.ai", "spring-ai-openai")
      defer:
        ZIO.foreachDiscard(List(databind2, databind3, nullable, springAi)): a =>
          SymbolIndex.replace(GroupArtifactVersion(a.groupId, a.artifactId, Version("1.0")), Set("com.example.Placeholder"))
        .run
        assertTrue(
          SymbolSearch.searchGroupArtifacts("jackson-databind 3.0.0").run == Set(databind2, databind3, nullable),
          SymbolSearch.searchGroupArtifacts("tools.jackson").run == Set(databind3),
          SymbolSearch.searchGroupArtifacts("JACKSON Nullable").run == Set(nullable),
          SymbolSearch.searchGroupArtifacts("3.0.0").run.isEmpty,
          SymbolSearch.searchGroupArtifacts("(").run.isEmpty,
          SymbolSearch.searchGroupArtifacts("rubric(").run.isEmpty,
          SymbolIndex.allGroupArtifacts.run == Set(databind2, databind3, nullable, springAi),
        )
    ,
    test("symbol search falls through exact, then containing, then similar, and short queries only match exactly"):
      defer:
        seedTwo.run
        assertTrue(
          SymbolSearch.symbolArtifacts("JevJudge").run == Set(judge),
          SymbolSearch.symbolArtifacts("udg").run == Set(judge, other),
          SymbolSearch.symbolArtifacts("JevJuge").run == Set(judge),
          SymbolSearch.symbolArtifacts("ud").run.isEmpty,
        )

  ).provide(PostgresContainer.freshDatabase, SymbolIndex.layer)
