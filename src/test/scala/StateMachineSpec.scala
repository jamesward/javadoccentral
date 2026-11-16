import zio.*
import zio.test.*

import scala.compiletime.testing.typeChecks

/** The generic [[StateMachine]] driver: cycles, final start states, errors, resources and step logs. */
object StateMachineSpec extends ZIOSpecDefault:

  case class Counting(n: Int)
  case class Stopped(at: Int)
  case class Boom(reason: String)

  private val countdown = StateMachine[Counting, Counting, Stopped]("countdown")

  // An acyclic machine: A → B → C.
  case class A()
  case class B(fromA: Boolean)
  case class C(fromB: Boolean)
  private val dag = StateMachine[A, B, C]("dag")
  private def step(state: Counting): UIO[Counting | Stopped] =
    ZIO.succeed(if state.n == 0 then Stopped(0) else Counting(state.n - 1))

  /** Counting(2) acquires a resource (counted in `held`) that later steps rely on. */
  private def acquiringStep(held: Ref[Int], last: Counting => ZIO[Any, Boom, Stopped])(state: Counting): ZIO[Scope, Boom, Counting | Stopped] =
    state.n match
      case 2 => ZIO.acquireRelease(held.update(_ + 1))(_ => held.update(_ - 1)).as(Counting(1))
      case _ => last(state)

  override def spec = suite("StateMachine")(
    test("follows edges back to an active state until a final state"):
      for result <- countdown.run(Counting(3))(step)
      yield assertTrue(result == Stopped(0))
    ,
    test("an acyclic machine runs from its start to its final state"):
      val run = dag.run(A()):
        case _: A => ZIO.succeed(B(fromA = true))
        case b: B => ZIO.succeed(C(fromB = b.fromA))
      for result <- run
      yield assertTrue(result == C(fromB = true))
    ,
    test("a run can't begin at a later state, and no transition can lead back to the start"):
      // Checked by the compiler, here at test compile time.
      val laterStart = typeChecks("dag.run(B(true))(_ => ZIO.succeed(C(true)))")
      val backToStart = typeChecks("dag.run(A())(_ => ZIO.succeed(A()))")
      val valid = typeChecks("dag.run(A())(_ => ZIO.succeed(C(true)))")
      assertTrue(!laterStart, !backToStart, valid)
    ,
    test("an unhandled error fails the machine with the state it failed in"):
      for result <- countdown.run(Counting(3))(state => if state.n == 1 then ZIO.fail(Boom("boom")) else step(state)).either
      yield assertTrue(
        result == Left(StateMachine.Failed("countdown", Counting(1), Boom("boom"))),
        result.left.toOption.map(_.toString).contains("countdown failed in Counting: Boom(boom)"),
      )
    ,
    test("an error edge recovers to a state of the topology"):
      for result <- countdown.run(Counting(1))(state => ZIO.fail(Boom("boom")).catchAll(_ => ZIO.succeed(Stopped(state.n))))
      yield assertTrue(result == Stopped(1))
    ,
    test("a resource acquired in one step is held by later steps and released when the machine stops"):
      for
        held      <- Ref.make(0)
        heldLater <- Ref.make(-1)
        result    <- countdown.run(Counting(2))(acquiringStep(held, _ => held.get.flatMap(heldLater.set).as(Stopped(1))))
        after     <- held.get
        during    <- heldLater.get
      yield assertTrue(result == Stopped(1), during == 1, after == 0)
    ,
    test("the resource is released when a later step fails or the machine is interrupted"):
      for
        held        <- Ref.make(0)
        failed      <- countdown.run(Counting(2))(acquiringStep(held, _ => ZIO.fail(Boom("boom")))).either
        afterFail   <- held.get
        started     <- Promise.make[Nothing, Unit]
        fiber       <- countdown.run(Counting(2))(acquiringStep(held, _ => started.succeed(()) *> ZIO.never)).fork
        _           <- started.await
        _           <- fiber.interrupt
        afterInterr <- held.get
      yield assertTrue(failed.isLeft, afterFail == 0, afterInterr == 0)
    ,
    test("logs each step with the machine and state type names, never state data"):
      for
        _    <- countdown.run(Counting(1))(step)
        logs <- ZTestLogger.logOutput
        steps = logs.filter(_.message() == "State machine step").map(_.annotations)
      yield assertTrue(
        steps.map(a => (a("machine"), a("from"), a("to"))) ==
          Chunk(("countdown", "Counting", "Counting"), ("countdown", "Counting", "Stopped")),
      )
  ) @@ TestAspect.withLiveClock

  given CanEqual[Stopped, Stopped] = CanEqual.derived
  given CanEqual[C, C] = CanEqual.derived
  given CanEqual[Either[StateMachine.Failed[Counting, Boom], Stopped], Either[StateMachine.Failed[Counting, Boom], Stopped]] =
    CanEqual.derived
