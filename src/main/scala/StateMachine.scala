import zio.*

import scala.reflect.TypeTest

/**
 * Explicit, effectful state machines for ZIO, with compile-checked transitions.
 *
 * Written to be lifted into a library: it depends only on ZIO and the Scala 3 standard
 * library, and nothing in it knows about this application.
 *
 * =Why=
 *
 * Multi-step flows with fallbacks ("try the name index, then each symbol tier, then AI
 * search"; "compare versions, take a lock, write") tend to be written as nested
 * `if`/`orElse`/`catchAll` chains. The states exist, but only implicitly: nothing names
 * them, nothing stops a step from being skipped, and the whole flow has to be read to see
 * which paths exist. Making the states explicit gives each one a name, a type and a test
 * point, and lets the compiler check the paths.
 *
 * =The model=
 *
 * A machine has three kinds of state, each declared as a type parameter (usually a union):
 *
 *  - `Start`: where a run begins, the only state `run` accepts.
 *  - `Next`: the states a transition can lead to that still have work to do.
 *  - `Done`: the final states, one of which `run` returns.
 *
 * The '''active''' states, those with a transition, are `Start | Next`.
 *
 *  - '''Each state is a type''', usually a case class carrying exactly the data that state
 *    needs. A state that must only be reached through its transition gets a private
 *    constructor (`case class Claimed private[Indexing] (…)`), so no code can skip ahead.
 *  - '''Each transition is a plain function''' from one active state to the union of the
 *    states it may lead to:
 *    {{{
 *    def claim(stale: Stale): ZIO[SymbolSearchGuard & Scope, Nothing, Claimed | Skipped]
 *    }}}
 *    That union is the machine's topology at that state. Returning any other state is a type
 *    error, including when the target is decided at run time, because every branch must
 *    still produce one of the listed states.
 *  - '''A machine runs''' with a dispatch table from each active state to its transition:
 *    {{{
 *    StateMachine[Candidate, Stale | Claimed, Boolean | Skipped]("indexing").run(candidate):
 *      case candidate: Candidate => compare(candidate)
 *      case stale: Stale         => claim(stale)
 *      case claimed: Claimed     => write(claimed)
 *    }}}
 *    The `match` must cover every active state: a missing case is an exhaustivity warning,
 *    which fails the build under `-Werror`. The machine stops at the first final state and
 *    returns it, usually for a caller to render (`match` over the final union, also checked
 *    for exhaustiveness).
 *
 * Declaring the states up front lists them in one place and lets `run` infer the
 * environment `R` and error `E` from the transitions. Document the topology as a diagram in
 * the machine's scaladoc; see `SymbolSearch.Resolution` for the style.
 *
 * '''Entry is checked too.''' `run` takes only a `Start`, so a run can't begin partway
 * through (`search` can't start at `NotIndexed` and skip straight to paid AI search). A
 * transition returns `Next | Done`, so when `Start` is not in `Next`, no transition can lead
 * back to the start: in an acyclic machine (`A → B → C`) the start has no incoming edges, by
 * type. Private constructors complement this: they stop code ''outside'' the machine from
 * building a mid-flow state at all.
 *
 * Cycles are edges back to an active state, often to the same type with less work left
 * (`SymbolTiers(tiers.tail)`, `Detecting(formats.tail)`). A cycle through the start lists the
 * start in `Next` too: `StateMachine[SymbolTiers, SymbolTiers, Found | NotIndexed]`.
 * Composition is a transition that runs another machine (`Resolution.byName` runs
 * `NameSearch`).
 *
 * =Errors=
 *
 * Three kinds, handled in three ways:
 *
 *  - '''An expected outcome''' (nothing found, the lock is held elsewhere, the page doesn't
 *    exist) is a state, not an error: a final state, or an edge to a state that handles it.
 *  - '''A recoverable failure''' is an '''error edge''': the transition catches it and
 *    returns a state, and the union checks the target like any other edge:
 *    {{{
 *    MavenCentral.searchArtifacts(groupId).map(GroupArtifacts(groupId, _))
 *      .orElseSucceed(InvalidGroup(groupId))
 *    }}}
 *    Retry inside a transition (`.retry(schedule)`), not with an edge back to the same state:
 *    that would have no backoff and no limit. A machine whose failures are all error edges
 *    has `E = Nothing`, so `run` can't fail; `.mapError(_.error)` turns its
 *    `Failed[_, Nothing]` into `Nothing`.
 *  - '''Anything else''' fails the machine with [[Failed]], which keeps the machine's name and
 *    the state it failed in (its `toString` reads "indexing failed in Stale: …"). Callers
 *    with a public error type map it once at the end, not per transition. Defects and
 *    interruption pass through unchanged: they are not the machine's to handle.
 *
 * Choosing between an error edge and failing is a product decision. `SymbolSearch` does not
 * fall back to AI search when the index is down, because every query would then cost an AI
 * call.
 *
 * =Resources=
 *
 * Each run owns a `Scope`. A transition may acquire something that later states rely on
 * (`ZIO.acquireRelease` in `Indexing.claim`), and it is released when the machine stops,
 * whether it reached a final state, failed or was interrupted. Callers must not wrap `run` in
 * `ZIO.scoped`; the machine's environment never contains `Scope`.
 *
 * A linear indexed state monad (scalaz's `IndexedStateT`, `S1 => F[(S2, A)]`) can't give
 * this guarantee: once a step fails there is no state left to release.
 *
 * =Observability=
 *
 * Every transition logs `"State machine step"` at debug level, annotated with `machine`,
 * `from` and `to` (state type names) and `durationMs`. Only type names are logged, never
 * state data, which can be large (an artifact's whole symbol set) or sensitive.
 *
 * =Constraints=
 *
 *  - `Next` and `Done` must not overlap: a transition's result is final exactly when it is
 *    not a `Next` state. (`Start` may overlap `Next`, for a cycle through the start.)
 *  - `Next` states must be checkable at run time (a `TypeTest`). Classes, case classes and
 *    unions of them are. A generic state is too while the machine's states pin its type
 *    arguments (`Retrying[Int]`), but not when a `Next` and a `Done` state differ only in
 *    type arguments (`Box[Int]` vs `Box[String]`): erasure can't tell them apart, and the
 *    unchecked-type-test warning fails the build under `-Werror`.
 *  - State types should be distinct per machine: two machines sharing a state type works,
 *    but blurs their logs.
 *
 * =Prior art=
 *
 *  - '''crem''' (Haskell; Perone & Karachalias, "Crème de la Crem", 2023): Mealy machines
 *    whose topology is a type, with states indexed by node, so an action may branch at run
 *    time but only to allowed nodes. The union return type here is the same idea without
 *    type-level graphs. crem also composes machines and renders them as Mermaid diagrams.
 *  - '''Idris `ST`''' (Brady, "State Machines All the Way Down", 2017) and resource-dependent
 *    algebraic effects: an operation's output state may depend on its result, e.g. a login
 *    leaves you logged in or out depending on whether it succeeded. Here the result is
 *    simply the next state.
 *  - '''Typestate''' (Strom & Yemini, 1986): an object's allowed operations depend on its
 *    state. Private constructors provide the "can't skip a step" part.
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
 *  - '''Diagrams from types.''' A macro could read each transition's union return type
 *    and emit a Mermaid `stateDiagram` (crem's best feature), replacing the hand-drawn
 *    diagrams in scaladoc and keeping them in step with the code.
 *  - '''Topology tests.''' The same macro could expose the edge list, so a test can assert
 *    reachability (every final state reachable, no dead active state).
 *  - '''Per-state scopes.''' Today resources live until the machine stops. A state could own
 *    a scope closed on leaving it, for resources only some states need (a lock released
 *    before a slow, lock-free final step).
 *  - '''Step limits.''' A bound on transitions per run (dying with the path so far) would stop
 *    a buggy cycle from spinning forever. Not needed while every cycle consumes a list.
 *  - '''Path history.''' [[Failed]] could carry the states visited (type names) to show how the
 *    machine got there, and `run` could optionally return it with the final state.
 *  - '''Tracing.''' A span per transition (ZIO telemetry / OpenTelemetry) instead of, or as well
 *    as, the debug log; the log level and message could be configurable for a library.
 *  - '''Per-step timeouts.''' A machine-level deadline, or one per state, as an error edge to a
 *    timed-out state.
 *  - '''Machine-level error edges.''' A `recover: (Start | Next, E) => ZIO[R, E2, Next | Done]` hook
 *    for policies shared by every state (e.g. "the index is down: answer from cache"). Today
 *    each transition catches its own errors, which keeps the edge next to its cause.
 *  - '''Event-driven machines.''' Accepting external events (a queue) between transitions
 *    would cover long-lived entities, at which point Mechanoid's feature set (persistence,
 *    durable timeouts) becomes the comparison.
 *  - '''Library extraction.''' Package it (e.g. `zio-state-machine`), with this scaladoc as the
 *    README. Keep it free of logging backends and of this app's types.
 */
object StateMachine:

  /** The machine failed with `error` while leaving `state`. */
  case class Failed[+S, +E](machine: String, state: S, error: E):
    /** The state's type name: the state itself can hold a lot of data. */
    def stateName: String = StateMachine.name(state)
    override def toString: String = s"$machine failed in $stateName: $error"

  /**
   * A machine that starts at a `Start` state, whose transitions lead to `Next` (more work)
   * or `Done` (final) states. Each is usually a union. `Next` and `Done` must not overlap.
   * `Start` is listed in `Next` only if a transition can lead back to it (a cycle through
   * the start). `machine` names the machine in logs and failures.
   */
  def apply[Start, Next, Done](machine: String): Machine[Start, Next, Done] = Machine(machine)

  final class Machine[Start, Next, Done](machine: String):
    /**
     * Runs `step` from `start` until it reaches a `Done` state, in a `Scope` that closes when
     * the machine stops. Each transition logs `"State machine step"` at debug level, with the
     * `machine` name, the `from` and `to` state types and `durationMs`.
     */
    def run[R, E](start: Start)(step: (Start | Next) => ZIO[R & Scope, E, Next | Done])(
      using next: TypeTest[Next | Done, Next]
    ): ZIO[R, Failed[Start | Next, E], Done] =
      def transition(from: Start | Next): ZIO[R & Scope, Failed[Start | Next, E], Next | Done] =
        logged(machine, from, step(from).mapError(Failed(machine, from, _)))
      def loop(state: Next | Done): ZIO[R & Scope, Failed[Start | Next, E], Done] =
        state match
          case next(current) => transition(current).flatMap(loop)
          // Not a Next state, so (as Next and Done don't overlap) a Done state.
          case done => ZIO.succeed(done.asInstanceOf[Done])
      ZIO.scoped[R](transition(start).flatMap(loop))

  private def logged[R, E, A](machine: String, from: Any, transition: ZIO[R, E, A]): ZIO[R, E, A] =
    transition.timed.flatMap: (duration, to) =>
      ZIO.logAnnotate(
        LogAnnotation("machine", machine),
        LogAnnotation("from", name(from)),
        LogAnnotation("to", name(to)),
        LogAnnotation("durationMs", duration.toMillis.toString),
      )(ZIO.logDebug("State machine step")).as(to)

  /** A state's type name, never its data (which can be a whole artifact's symbols). */
  private def name(state: Any): String =
    state.getClass.getSimpleName.nn.stripSuffix("$")
