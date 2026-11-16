import zio.test.*

import scala.compiletime.testing.typeChecks

/**
 * The app's state machines keep invalid states unrepresentable. Outside a machine's object
 * only its input can be built and only the whole machine can be run: intermediate states,
 * transitions and inner machines are private, and output states have private constructors,
 * so no code can start partway, run a step on its own, or forge a result. Checked by the
 * compiler, at test compile time. (`???` stands for any argument: `typeChecks` doesn't run
 * the code, and explicit nulls would reject `null` for an unrelated reason.)
 */
object MachineStatesSpec extends ZIOSpecDefault:

  override def spec = suite("State machine states")(
    test("a machine's input can be built and the machine run"):
      assertTrue(
        typeChecks("""SymbolSearch.Indexing.run(SymbolSearch.Indexing.Candidate(???, Set.empty))"""),
        typeChecks("""SymbolSearch.Resolution.machine.run(SymbolSearch.Resolution.Query("ObjectMapper"))"""),
        typeChecks("""SymbolSearch.Resolution.symbols.run("ObjectMapper")"""),
        typeChecks("""SymbolSearch.NameSearch.machine.run(SymbolSearch.NameSearch.ByName("jackson-databind 3.0.0"))"""),
        typeChecks("""Extractor.Detection.machine.run(Extractor.Detection.Jar(???, ???))"""),
        typeChecks("""Web.ArtifactPage.machine.run(Web.ArtifactPage.Lookup(???, ???))"""),
        typeChecks("""Web.VersionPage.machine.run(Web.VersionPage.Open(???))"""),
      )
    ,
    test("output states can only be built by their machine"):
      assertTrue(
        !typeChecks("""SymbolSearch.Indexing.Written(???)"""),
        !typeChecks("""SymbolSearch.Indexing.Skipped.UpToDate(???)"""),
        !typeChecks("""SymbolSearch.Resolution.Found(Set.empty)"""),
        !typeChecks("""SymbolSearch.NameSearch.Matched(Set.empty)"""),
        !typeChecks("""Extractor.Detection.Parsed(Set.empty)"""),
        !typeChecks("""Web.ArtifactPage.SplitGroup(???)"""),
        !typeChecks("""Web.VersionPage.IndexPage(???)"""),
      )
    ,
    test("intermediate states, transitions and inner machines are private"):
      assertTrue(
        !typeChecks("""val s: SymbolSearch.Indexing.Stale = ???"""),
        !typeChecks("""SymbolSearch.Indexing.compare(???)"""),
        !typeChecks("""SymbolSearch.Indexing.write(???)"""),
        !typeChecks("""val s: SymbolSearch.Resolution.NotIndexed = ???"""),
        !typeChecks("""SymbolSearch.Resolution.byAi(???)"""),
        !typeChecks("""SymbolSearch.Resolution.tiers"""),
        !typeChecks("""SymbolSearch.Resolution.tiersFor("ObjectMapper")"""),
        !typeChecks("""val s: SymbolSearch.NameSearch.WithoutVersions = ???"""),
        !typeChecks("""val s: Extractor.Detection.Listing = ???"""),
        !typeChecks("""Extractor.Detection.detect(???)"""),
        !typeChecks("""val s: Web.ArtifactPage.NotAnArtifact = ???"""),
        !typeChecks("""Web.ArtifactPage.splitGroup(???)"""),
        !typeChecks("""val s: Web.VersionPage.NoJavadoc = ???"""),
        !typeChecks("""Web.VersionPage.open(???)"""),
      )
  )
