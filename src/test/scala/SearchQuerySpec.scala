import com.jamesward.zio_mavencentral.MavenCentral.{ArtifactId, GroupArtifact, GroupId}
import zio.test.*

/**
 * Pure (no Redis, no network) tests for the query handling behind
 * `search_artifacts`, `symbol_to_artifact` and `list_javadoc_symbols`.
 * The cases come from agent evals where these tools returned nothing and the
 * agent retried the same kind of query dozens of times.
 */
object SearchQuerySpec extends ZIOSpecDefault:

  private def ga(g: String, a: String) = GroupArtifact(GroupId(g), ArtifactId(a))

  private val catalog = Set(
    ga("com.fasterxml.jackson.core", "jackson-databind"),
    ga("tools.jackson.core", "jackson-databind"),
    ga("org.openapitools", "jackson-databind-nullable"),
    ga("org.springframework.ai", "spring-ai-openai"),
  )

  private def content(fqn: String) = Extractor.Content(fqn.replace('.', '/') + ".html", false, fqn, "", "", "")

  override def spec = suite("search query handling")(

    test("a version appended to a name query is ignored when the full query matches nothing"):
      val r = SymbolSearch.searchGroupArtifactsIn(catalog, "jackson-databind 3.0.0")
      assertTrue(r == Set(ga("com.fasterxml.jackson.core", "jackson-databind"), ga("tools.jackson.core", "jackson-databind"),
        ga("org.openapitools", "jackson-databind-nullable")))
    ,
    test("version-like tokens"):
      assertTrue(
        List("3", "3.0", "3.0.0", "2.1.0-m1", "v1.2", "2.18.8").forall(SymbolSearch.isVersionToken),
        List("jackson", "databind", "spring-ai", "zio_3", "openai").forall(!SymbolSearch.isVersionToken(_)),
      )
    ,
    test("a query that matches as written is not widened"):
      assertTrue(SymbolSearch.searchGroupArtifactsIn(catalog, "tools.jackson") == Set(ga("tools.jackson.core", "jackson-databind")))
    ,
    test("a version-only or punctuation-only query matches nothing rather than everything"):
      assertTrue(
        SymbolSearch.searchGroupArtifactsIn(catalog, "3.0.0").isEmpty,
        SymbolSearch.searchGroupArtifactsIn(catalog, "(").isEmpty,
        SymbolSearch.searchGroupArtifactsIn(catalog, "rubric(").isEmpty,
      )
    ,
    test("symbol patterns: exact case, and a case-insensitive fallback glob"):
      assertTrue(
        SymbolSearch.symbolPattern("JevJudge", caseInsensitive = false) == "*JevJudge*",
        SymbolSearch.symbolPattern("jev.J1", caseInsensitive = true) == "*[jJ][eE][vV].[jJ]1*",
        SymbolSearch.symbolPattern("Foo Bar", caseInsensitive = false) == "*Foo*Bar*",
      )
    ,
    test("filterContents narrows by fqn, case-insensitively, requiring every term"):
      val all = Set(
        content("tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator"),
        content("tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator.Builder"),
        content("tools.jackson.databind.jsontype.PolymorphicTypeValidator"),
        content("tools.jackson.databind.ObjectMapper"),
      )
      assertTrue(
        Extractor.filterContents(all, Some("polymorphictypevalidator")).size == 3,
        Extractor.filterContents(all, Some("Basic builder")).map(_.fqn) == Set("tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator.Builder"),
        Extractor.filterContents(all, None).map(_.fqn) == all.map(_.fqn),
        Extractor.filterContents(all, Some("  ")).map(_.fqn) == all.map(_.fqn),
        Extractor.filterContents(all, Some("NoSuchType")).isEmpty,
      )
  )
