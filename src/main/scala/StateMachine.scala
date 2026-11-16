import zio.*

import scala.reflect.TypeTest

/**
 * Explicit, effectful state machines.
 *
 * Each state is its own type, and each transition is a plain function from one state to
 * the union of the states it may lead to (`Stale => ZIO[R, E, Claimed | Skipped]`). The
 * union is the machine's topology for that state, so the compiler checks every edge, and a
 * transition that decides its target at run time is still checked: it can only return one of
 * the listed states. This is crem's typed topology (Haskell) and Idris ST's result-dependent
 * output states, in the shape Scala 3 unions give for free. A state with a private
 * constructor can only be reached through its transition, so a step can't be skipped.
 *
 * `StateMachine[Active, Done](name).run(start)(step)` drives a machine: `step` is the dispatch table from each active state to its
 * transition (a `match` over the union, checked for exhaustiveness), and the machine stops
 * at the first `Done` state. Cycles (retries, tiers) are just edges back to an active state.
 *
 * An IndexedStateT-style monad (`S1 => ZIO[R, E, (S2, A)]`) is a straight line: after a branch
 * every later step has to accept and pass on the whole union, so it adds nothing here.
 */
object StateMachine:

  /**
   * A machine whose non-final states are `Active` and whose final states are `Done` (each
   * usually a union; they must not overlap). Declaring both up front lists the machine's
   * states in one place, and lets `run` infer the environment and error from `step`.
   */
  def apply[Active, Done](machine: String): Machine[Active, Done] = Machine(machine)

  final class Machine[Active, Done](machine: String):
    /**
     * Runs `step` from `start` until it reaches a `Done` state. Each transition logs
     * `"State machine step"` at debug level, with the `machine` name, the `from` and `to`
     * state types and `durationMs`.
     */
    def run[R, E](start: Active | Done)(step: Active => ZIO[R, E, Active | Done])(
      using active: TypeTest[Active | Done, Active]
    ): ZIO[R, E, Done] =
      def loop(state: Active | Done): ZIO[R, E, Done] =
        state match
          case active(current) => logged(machine, current, step(current)).flatMap(loop)
          // Not active, so (as Active and Done don't overlap) a Done state.
          case done => ZIO.succeed(done.asInstanceOf[Done])
      loop(start)

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
