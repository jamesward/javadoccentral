import zio.test.*

import scala.compiletime.testing.typeChecks

/**
 * The app's state machines keep invalid states unrepresentable: only a machine's start state
 * can be built outside it. Mid-flow and final states have private constructors, so no code
 * can skip a step or forge a result. Checked by the compiler, at test compile time.
 */
object MachineStatesSpec extends ZIOSpecDefault:

  override def spec = suite("State machine states")(
    test("start states can be built by callers"):
      assertTrue(
        typeChecks("""SymbolSearch.Indexing.Candidate(???, Set.empty)"""),
        typeChecks("""SymbolSearch.Resolution.Query("ObjectMapper")"""),
        typeChecks("""SymbolSearch.NameSearch.ByName("jackson-databind 3.0.0")"""),
        typeChecks("""Web.ArtifactPage.Lookup(???, ???)"""),
        typeChecks("""Web.VersionPage.Open(???)"""),
      )
    ,
    test("mid-flow and final states can only be built by their machine"):
      assertTrue(
        !typeChecks("""SymbolSearch.Indexing.Stale(SymbolSearch.Indexing.Candidate(???, Set.empty))"""),
        !typeChecks("""SymbolSearch.Indexing.Written(???)"""),
        !typeChecks("""SymbolSearch.Indexing.Skipped.UpToDate(???)"""),
        !typeChecks("""SymbolSearch.Resolution.NotIndexed("ObjectMapper")"""),
        !typeChecks("""SymbolSearch.Resolution.Found(Set.empty)"""),
        !typeChecks("""SymbolSearch.NameSearch.WithoutVersions(Nil)"""),
        !typeChecks("""Extractor.Detection.Listing(???)"""),
        !typeChecks("""Web.ArtifactPage.SplitGroup(???)"""),
        !typeChecks("""Web.VersionPage.IndexPage(???)"""),
      )
    ,
    test("a start state with an invariant is built by its smart constructor"):
      assertTrue(
        !typeChecks("""SymbolSearch.Resolution.SymbolTiers("x", ::(SymbolSearch.Match.Similar, Nil))"""),
        typeChecks("""SymbolSearch.Resolution.tiersFor("ObjectMapper")"""),
        !typeChecks("""Extractor.Detection.Detecting(???, ???, ::(Extractor.Format.Javadoc, Nil))"""),
        typeChecks("""Extractor.Detection.start(???, ???)"""),
      )
  )
