import zio.*

import scala.reflect.TypeTest

/**
 * Explicit, effectful state machines for ZIO, built by composition, with compile-checked
 * transitions.
 *
 * Written to be lifted into a library: it depends only on ZIO and the Scala 3 standard
 * library, and nothing in it knows about this application.
 *
 * =Design goal: make invalid states unrepresentable=
 *
 * The primary goal is that a state the flow can't really be in, or a move it can't really
 * make, can't be written down: the compiler rejects it, rather than code checking for it at
 * run time or a comment asking callers not to. Every part of the design serves that goal:
 *
 *  - being in a state the flow never reached: intermediate states are private types, and
 *    output states have private constructors;
 *  - a result the machine didn't produce: private constructors on output states;
 *  - running a step on its own, or starting partway: transitions and inner machines are
 *    private, and a machine's only entry is its input type;
 *  - a state carrying impossible data: each state holds exactly its valid data;
 *  - a move the flow doesn't allow: a step returns a union of the allowed states;
 *  - a state nothing handles: it would appear in the machine's output type, which then
 *    doesn't match the declared one;
 *  - an edge back to an acyclic machine's input: composition has no back edges, and `loop`
 *    only repeats its own input type;
 *  - a resource still held after the machine stops: each run owns a `Scope`.
 *
 * When designing a machine, check each state against this list. In particular:
 *
 *  - '''Only the input and the outputs are public.''' A machine lives in its own object.
 *    Its input type is public (prefer raw input any value of which is valid:
 *    `NameSearch.ByName(query)`); intermediate states, transitions, smart constructors and
 *    inner machines are `private`; output types are public with `private[Machine]`
 *    constructors, so callers can match on a result but not forge one. The public surface
 *    is a `val machine: StateMachine[R, E, In, Out]` (and maybe a `run` helper).
 *  - '''Group states with a sealed trait, not an enum''': enum cases can't have private
 *    constructors (`Indexing.Skipped`). Enums are fine for plain data inside states.
 *  - '''No data that some path makes meaningless.''' Each distinct situation is its own state
 *    or case, not a flag or an `Option` that only some paths fill in:
 *     - not `UpToDate(indexed: Option[Version])` when nothing-indexed always means stale, but
 *       `UpToDate(indexed: Version)`;
 *     - not a final `Boolean` "written", but `Written` or `Skipped.Superseded`;
 *     - not `Detecting(formats = Nil)` meaning "list the pages", but a non-empty `::[Format]`
 *       and an explicit `Listing` state;
 *     - not `notFound: Option[ArtifactId]`, which could name an artifact nobody asked for,
 *       but a reason (`ArtifactPage.Unknown`) from which the page derives it.
 *  - '''Non-empty where empty is meaningless''' (`::[Match]`, `::[Format]`).
 *
 * `MachineStatesSpec` checks the app's machines against the first rule at compile time.
 * What still relies on a run-time assumption is listed under ''Constraints'' below.
 *
 * =Why explicit states=
 *
 * Multi-step flows with fallbacks ("try the name index, then each symbol tier, then AI
 * search"; "compare versions, take a lock, write") tend to be written as nested
 * `if`/`orElse`/`catchAll` chains. The states exist, but only implicitly: nothing names
 * them, nothing stops a step from being skipped, and the whole flow has to be read to see
 * which paths exist. An implicit state can't be made unrepresentable; an explicit one, a
 * type, can.
 *
 * =The model=
 *
 * A `StateMachine[R, E, In, Out]` takes an `In` state to an `Out` state (each usually a
 * union of case classes), needing `R` and failing with [[Failed]]`[E]`. Only `In` and `Out`
 * appear in its type: whatever states it passes through inside are its own business, which
 * is what lets them be private. Machines are values, built from steps and combined:
 *
 *  - '''`step(f)`''' lifts one transition, a plain function from a state to the union of the
 *    states it may lead to:
 *    {{{
 *    def claim(stale: Stale): ZIO[SymbolSearchGuard & Scope, Nothing, Claimed | Skipped]
 *    }}}
 *    That union is the machine's topology at that state. Returning any other state is a type
 *    error, including when the target is decided at run time, because every branch must
 *    still produce one of the listed states.
 *  - '''`m1.andThen(m2)`''' (or `>>>`) runs `m2` on `m1`'s output.
 *  - '''`m.on[A, Rest](m2)`''' runs `m2` on the `A` states of `m`'s output and passes the
 *    `Rest` through. `Rest` is spelled out because Scala can't subtract a type from a union;
 *    the compiler checks that `A | Rest` covers the output.
 *  - '''`m.loop[A, B]`''' repeats `m` while its output is an `A` and returns the first `B`
 *    (the symbol tiers, the doc formats). The loop's input is `A`, so it can't be entered at
 *    any other state.
 *  - '''`m.contramap(f)` / `m.map(f)`''' adapt the input or output without a logged step.
 *  - '''`m.named(name)`''' names a machine in its log lines and its failures.
 *  - '''`m.run(in)`''' runs it.
 *
 * {{{
 * val machine: StateMachine[SymbolIndex & SymbolSearchGuard, Throwable, Candidate, Written | Skipped] =
 *   step(compare)
 *     .on[Stale, Skipped](step(claim))
 *     .on[Claimed, Skipped](step(write))
 *     .named("indexing")
 * }}}
 *
 * The declared type is the check that every state is handled: forget the `write` branch
 * and the output is `Claimed | Skipped`, which doesn't conform. Document the topology as a
 * diagram in the machine's scaladoc; see `SymbolSearch.Resolution` for the style.
 *
 * '''Inner machines.''' A machine is a value, so a bigger one is built from smaller ones and
 * the smaller ones stay private: `Resolution.tiers` (a `loop` over the symbol tiers) is
 * private, and both `Resolution.machine` (search) and `Resolution.symbols` (tiers alone)
 * are built from it. A transition may also run another machine (`Resolution.byName` runs
 * `NameSearch`) when its output doesn't feed straight into the next input.
 *
 * '''Why not a monad?''' A machine is a function `In => ZIO[R, E, Out]` (a Kleisli arrow
 * over ZIO). ZIO is already the monad; what machines need is the other composition, output
 * into input (`andThen`), plus choice (`on`) and feedback (`loop`): the same operators crem
 * provides (Kleisli/Sequential, Alternative, Feedback). For a fixed input a machine is also
 * a monad in its output, but that bind re-feeds the same input to every machine, which no
 * flow here wants, so it is left out.
 *
 * =Errors=
 *
 * Three kinds, handled in three ways:
 *
 *  - '''An expected outcome''' (nothing found, the lock is held elsewhere, the page doesn't
 *    exist) is a state, not an error: an output state, or an edge to a state that handles it.
 *  - '''A recoverable failure''' is an '''error edge''': the transition catches it and
 *    returns a state, and the union checks the target like any other edge:
 *    {{{
 *    MavenCentral.searchArtifacts(groupId).map(GroupArtifacts(groupId, _))
 *      .orElseSucceed(InvalidGroup(groupId))
 *    }}}
 *    Retry inside a transition (`.retry(schedule)`), not with a loop back to the same state:
 *    that would have no backoff and no limit. A machine whose failures are all error edges
 *    has `E = Nothing`, so `run` can't fail; `.mapError(_.error)` turns its
 *    `Failed[Nothing]` into `Nothing`.
 *  - '''Anything else''' fails the machine with [[Failed]], which keeps the type name of the
 *    state it failed in and the named machines it was running in, outermost first: its
 *    `toString` reads "search > symbol-search failed in SymbolTiers: …". It keeps the name,
 *    not the state, so intermediate types stay private. Callers with a public error type
 *    map it once at the end, not per transition. Defects and interruption pass through
 *    unchanged: they are not the machine's to handle.
 *
 * Choosing between an error edge and failing is a product decision. `SymbolSearch` does not
 * fall back to AI search when the index is down, because every query would then cost an AI
 * call.
 *
 * =Resources=
 *
 * Each run owns a `Scope`, shared by every step of the run, inner machines included. A
 * transition may acquire something that later states rely on (`ZIO.acquireRelease` in
 * `Indexing.claim`), and it is released when the machine stops, whether it reached an
 * output state, failed or was interrupted. Callers must not wrap `run` in `ZIO.scoped`; a
 * machine's `R` never contains `Scope`.
 *
 * A linear indexed state monad (scalaz's `IndexedStateT`, `S1 => F[(S2, A)]`) can't give
 * this guarantee: once a step fails there is no state left to release.
 *
 * =Parallelism=
 *
 * There are deliberately no parallel combinators (`foreachPar`, `zipPar` / `&&&`, `race`).
 * Parallelism belongs to ZIO at the call site, and a machine's job is one item's flow: where a
 * per-item flow deserves a machine, run it with `ZIO.foreachPar(items)(machine.run)` (inside
 * a transition, if the items come from a state). Decided Nov 2026, because a combinator
 * would:
 *
 *  - add nothing to the design goal: private states, checked edges and testing through `run`
 *    come with the machine however it is called;
 *  - make resource lifetimes worse: branches inside one run would share its `Scope`, holding
 *    whatever a branch acquired until the whole run ends, where a `run` per item gives each
 *    item its own `Scope`, released as soon as that item finishes;
 *  - not express background work: work that outlives the response (`forkDaemon`, like AI
 *    search's index loads) can't live inside a run, whose `Scope` closes when it returns;
 *  - re-specify what ZIO already defines: interruption on failure, `withParallelism`,
 *    collecting failures (`validatePar`).
 *
 * `race` in particular doesn't fit the fallbacks here: they are ordered by precedence (a name
 * match beats a symbol match beats AI search), so racing them changes the answer, and racing
 * AI search would pay for an AI call on every query.
 *
 * Reconsider if machines come to render themselves from their composition (where a parallel
 * split would be part of the drawing), or if parallel branches must share resources acquired
 * earlier in the same run.
 *
 * =Observability=
 *
 * Every step logs `"State machine step"` at debug level, annotated with `from` and `to`
 * (state type names) and `durationMs`. `named` adds a `machine` annotation to every log
 * line inside it, the steps' and the transitions' own (the innermost name wins). Only type
 * names are logged, never state data, which can be large (an artifact's whole symbol set)
 * or sensitive.
 *
 * =Constraints=
 *
 * Where the design goal is not (yet) met by the compiler, and a run-time assumption remains:
 *
 *  - The two sides of a split must not overlap: `A` and `Rest` in `on[A, Rest]`, `A` and `B`
 *    in `loop[A, B]`. A value is routed to `Rest` (or `B`) exactly when it is not an `A`, and
 *    the combinator casts on that basis. An overlapping split compiles; see
 *    ''Possible improvements''.
 *  - Private members bind outside the machine's object, not inside it: code within
 *    `object Indexing` could still build a `Claimed`. Keep a machine's object to its states,
 *    its transitions and its machines.
 *  - The `A` of a split must be checkable at run time (a `TypeTest`). Classes, case classes
 *    and unions of them are. A generic state is too while the types pin its arguments
 *    (`Retrying[Int]`), but not when the two sides differ only in type arguments (`Box[Int]`
 *    vs `Box[String]`): erasure can't tell them apart, and the unchecked-type-test warning
 *    fails the build under `-Werror`.
 *  - State types should be distinct per machine: two machines sharing a state type works,
 *    but blurs their logs.
 *
 * =Prior art=
 *
 *  - '''crem''' (Haskell; Perone & Karachalias, "Crème de la Crem", 2023): Mealy machines
 *    whose topology is a type, with states indexed by node, so an action may branch at run
 *    time but only to allowed nodes, composed with Sequential, Kleisli, Alternative and
 *    Feedback operators, and rendered as Mermaid diagrams. `step`'s union return type is
 *    the same topology idea without type-level graphs, and `andThen` / `on` / `loop` are its
 *    composition operators.
 *  - '''Idris `ST`''' (Brady, "State Machines All the Way Down", 2017) and resource-dependent
 *    algebraic effects: an operation's output state may depend on its result, e.g. a login
 *    leaves you logged in or out depending on whether it succeeded. Here the result is
 *    simply the next state.
 *  - '''Typestate''' (Strom & Yemini, 1986): an object's allowed operations depend on its
 *    state. Private states and transitions provide the "can't skip a step" part.
 *  - '''Arrows''' (Hughes, 2000): `andThen`, `on` and `loop` are the category, choice
 *    (`ArrowChoice`'s `left`, on unions instead of `Either`) and feedback (`ArrowLoop`,
 *    here a `tailRecM`) operations on Kleisli arrows.
 *  - '''IndexedStateT''' (scalaz) / zio-prelude's `ZPure`: indexed state monads. Tried and
 *    rejected: they are linear, so after a branch every later step must accept and pass on
 *    the whole union; they lose state (and resources) on failure; and `ZPure` can't run
 *    effects.
 *  - '''Mechanoid''' (Scala 3 + ZIO) and Effect-TS machines: event-driven, long-lived machines
 *    (a transition table of state + event → target, persistence, timeouts, diagrams). A
 *    better fit for durable entities; this is for single-run flows driven by the machine
 *    itself.
 *
 * =Possible improvements=
 *
 *  - '''Disjoint splits at compile time.''' A macro (or a `NotGiven` on each pair of union
 *    members) could reject an overlapping `on[A, Rest]` or `loop[A, B]`, removing the last
 *    run-time assumption.
 *  - '''Inferred `Rest`.''' A macro could compute "the output minus `A`" so `on[A](m)` needs no
 *    spelled-out remainder (Scala has no union subtraction).
 *  - '''Private states by default.''' A macro or linter rule flagging a public intermediate
 *    state or a public output constructor, so the first design rule can't be forgotten
 *    (today `MachineStatesSpec` lists them by hand).
 *  - '''Diagrams from the composition.''' Each combinator could record its edge (step type
 *    names, `on` branches, `loop` back edges) in the machine value, so a machine can render
 *    itself as a Mermaid `stateDiagram` (crem's best feature), replacing the hand-drawn
 *    diagrams in scaladoc and keeping them in step with the code.
 *  - '''Topology tests.''' The same recorded edges would let a test assert reachability
 *    (every output state reachable, no dead state).
 *  - '''Per-machine scopes.''' Today resources live until the whole run stops. A `scoped`
 *    combinator could close an inner machine's scope when it finishes, for resources only
 *    part of the flow needs (a lock released before a slow, lock-free last step).
 *  - '''Step limits.''' A bound on `loop` iterations (dying with the path so far) would stop a
 *    buggy cycle from spinning forever. Not needed while every loop consumes a list.
 *  - '''Path history.''' [[Failed]] could carry the states visited (type names) to show how the
 *    machine got there, and `run` could optionally return it with the output.
 *  - '''Tracing.''' A span per step (ZIO telemetry / OpenTelemetry) instead of, or as well as,
 *    the debug log; the log level and message could be configurable for a library.
 *  - '''Timeouts.''' A `timeout` combinator on a machine, as an error edge to a timed-out state.
 *  - '''Machine-level error edges.''' A `recover` combinator for policies shared by every step
 *    of a machine (e.g. "the index is down: answer from cache"). Today each transition
 *    catches its own errors, which keeps the edge next to its cause.
 *  - '''Parallel composition''' (crem's `Parallel`, ZIO's `Par` family): not planned; see
 *    ''Parallelism'' for why, and when to reconsider.
 *  - '''Event-driven machines.''' Accepting external events (a queue) between steps would
 *    cover long-lived entities, at which point Mechanoid's feature set (persistence, durable
 *    timeouts) becomes the comparison.
 *  - '''Library extraction.''' Package it (e.g. `zio-state-machine`), with this scaladoc as the
 *    README. Keep it free of logging backends and of this app's types.
 */
final class StateMachine[-R, +E, -In, +Out] private (
  private val transition: In => ZIO[R & Scope, StateMachine.Failed[E], Out]
):
  import StateMachine.*

  /** This machine, then `next` on its output. */
  def andThen[R1 <: R, E1 >: E, Out2](next: StateMachine[R1, E1, Out, Out2]): StateMachine[R1, E1, In, Out2] =
    StateMachine(in => transition(in).flatMap(next.transition))

  /** Alias for [[andThen]]. */
  def >>>[R1 <: R, E1 >: E, Out2](next: StateMachine[R1, E1, Out, Out2]): StateMachine[R1, E1, In, Out2] =
    andThen(next)

  /**
   * Runs `next` on the `A` states of this machine's output and passes the `Rest` through:
   * `compare.on[Stale, Skipped](claim)`. `A | Rest` must cover the output, and the two must
   * not overlap. (`Rest` is written out because Scala can't subtract a type from a union.)
   */
  def on[A, Rest](using covers: Out <:< (A | Rest), branch: TypeTest[A | Rest, A])[R1 <: R, E1 >: E, Out2](
    next: StateMachine[R1, E1, A, Out2]
  ): StateMachine[R1, E1, In, Out2 | Rest] =
    StateMachine: in =>
      transition(in).flatMap: out =>
        covers(out) match
          case branch(a) => next.transition(a)
          // Not an A, so (as A and Rest don't overlap) a Rest.
          case rest => ZIO.succeed(rest.asInstanceOf[Rest])

  /**
   * Repeats this machine while its output is an `A`, and returns the first `B`:
   * `step(bySymbol).loop[SymbolTiers, Found | NotIndexed]`. `A | B` must cover the output,
   * and the two must not overlap. The loop's input is its `A`, so it can only start there.
   */
  def loop[A, B](using covers: Out <:< (A | B), again: TypeTest[A | B, A], input: A <:< In): StateMachine[R, E, A, B] =
    def go(a: A): ZIO[R & Scope, Failed[E], B] =
      transition(input(a)).flatMap: out =>
        covers(out) match
          case again(next) => go(next)
          // Not an A, so (as A and B don't overlap) a B.
          case done => ZIO.succeed(done.asInstanceOf[B])
    StateMachine(go)

  /** Builds this machine's input from an `In2` without a logged step (a pure entry point). */
  def contramap[In2](f: In2 => In): StateMachine[R, E, In2, Out] =
    StateMachine(in2 => transition(f(in2)))

  /** Transforms the output without a logged step (for renaming or wrapping a result). */
  def map[Out2](f: Out => Out2): StateMachine[R, E, In, Out2] =
    StateMachine(in => transition(in).map(f))

  /** Names the machine in its steps' logs (`machine`) and in its [[Failed]] path. */
  def named(machine: String): StateMachine[R, E, In, Out] =
    StateMachine: in =>
      ZIO.logAnnotate(LogAnnotation("machine", machine)):
        transition(in).mapError(failed => failed.copy(machines = machine :: failed.machines))

  /** Runs the machine from `in` to its output, in a `Scope` that closes when it stops. */
  def run(in: In): ZIO[R, Failed[E], Out] =
    ZIO.scoped[R](transition(in))

object StateMachine:

  /**
   * The machine failed with `error` in the state named `state` (its type name: the state
   * itself can hold a lot of data). `machines` are the named machines it ran in, outermost
   * first (`search > symbol-tiers`).
   */
  case class Failed[+E](state: String, error: E, machines: List[String] = Nil):
    override def toString: String =
      val machine = if machines.isEmpty then "state machine" else machines.mkString(" > ")
      s"$machine failed in $state: $error"

  /** One transition: from an `A` to whatever `f` returns (usually a union of states). */
  def step[R, E, A, B](f: A => ZIO[R & Scope, E, B]): StateMachine[R, E, A, B] =
    StateMachine: a =>
      logged(a, f(a).mapError(Failed(name(a), _)))

  private def logged[R, E, A](from: Any, transition: ZIO[R, E, A]): ZIO[R, E, A] =
    transition.timed.flatMap: (duration, to) =>
      ZIO.logAnnotate(
        LogAnnotation("from", name(from)),
        LogAnnotation("to", name(to)),
        LogAnnotation("durationMs", duration.toMillis.toString),
      )(ZIO.logDebug("State machine step")).as(to)

  /** A state's type name, never its data (which can be a whole artifact's symbols). */
  private def name(state: Any): String =
    state.getClass.getSimpleName.nn.stripSuffix("$")
