import com.jamesward.zio_mavencentral.MavenCentral.Version
import zio.test.*

/**
 * Pure (no database, no network) tests for the query handling behind
 * `search_artifacts`, `symbol_to_artifact` and `list_javadoc_symbols`.
 * The cases come from agent evals where these tools returned nothing and the
 * agent retried the same kind of query dozens of times. The database-backed
 * behavior is in SymbolIndexSpec.
 */
object SearchQuerySpec extends ZIOSpecDefault:

  private def content(fqn: String) = Extractor.Content(fqn.replace('.', '/') + ".html", false, fqn, "", "", "")

  override def spec = suite("search query handling")(

    test("version-like tokens"):
      assertTrue(
        List("3", "3.0", "3.0.0", "2.1.0-m1", "v1.2", "2.18.8").forall(SymbolSearch.isVersionToken),
        List("jackson", "databind", "spring-ai", "zio_3", "openai").forall(!SymbolSearch.isVersionToken(_)),
      )
    ,
    test("containing patterns: parts in order, LIKE wildcards escaped"):
      assertTrue(
        SymbolIndex.containsPattern("JevJudge").contains("%JevJudge%"),
        SymbolIndex.containsPattern(" Foo  Bar ").contains("%Foo%Bar%"),
        SymbolIndex.containsPattern("100%_done\\").contains("%100\\%\\_done\\\\%"),
        SymbolIndex.containsPattern("   ").isEmpty,
      )
    ,
    test("only symbol-like names are indexed"):
      assertTrue(
        List("com.example.Foo", "Foo", "zio.ZIO$", "com.example.Outer.Builder", "zio.ZIO.<*>").forall(SymbolIndex.indexable),
        List("", "index-all", "allclasses-frame", ".zio-mavencentral", "a/b", "a b").forall(!SymbolIndex.indexable(_)),
      )
    ,
    test("a version supersedes the indexed one only when it is newer or the same"):
      assertTrue(
        SymbolIndex.supersedes(Version("1.0"), None),
        SymbolIndex.supersedes(Version("2.0"), Some(Version("1.10"))),
        SymbolIndex.supersedes(Version("1.10"), Some(Version("1.9"))),
        SymbolIndex.supersedes(Version("1.0"), Some(Version("1.0"))),
        !SymbolIndex.supersedes(Version("1.9"), Some(Version("1.10"))),
      )
    ,
    test("DATABASE_URL is converted to JDBC, requiring TLS unless the URL says otherwise"):
      assertTrue(
        Db.parse("postgres://u:p%40ss@host.example:5433/db") == Right(Db.Jdbc("jdbc:postgresql://host.example:5433/db?sslmode=require", "u", "p@ss")),
        Db.parse("postgresql://u:p@localhost/db?sslmode=disable") == Right(Db.Jdbc("jdbc:postgresql://localhost:5432/db?sslmode=disable", "u", "p")),
        Db.parse("redis://u:p@host/db").isLeft,
        Db.parse("postgres://host/db").isLeft,
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
