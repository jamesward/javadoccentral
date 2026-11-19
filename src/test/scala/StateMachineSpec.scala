import zio.*
import zio.test.*

import scala.compiletime.testing.typeChecks

import StateMachine.step

/** The [[StateMachine]] combinators: composition, branches, loops, errors, resources and step logs. */
object StateMachineSpec extends ZIOSpecDefault:

  case class Counting(n: Int)
  case class Stopped(at: Int)
  case class Boom(reason: String)

  private def countDown(state: Counting): UIO[Counting | Stopped] =
    ZIO.succeed(if state.n == 0 then Stopped(0) else Counting(state.n - 1))

  private val countdown: StateMachine[Any, Nothing, Counting, Stopped] =
    step(countDown).loop[Counting, Stopped].named("countdown")

  // An acyclic machine: A → B → C, with a branch at B.
  case class A(n: Int)
  case class Even(n: Int)
  case class Odd(n: Int)
  case class C(n: Int)
  private def classify(a: A): UIO[Even | Odd] = ZIO.succeed(if a.n % 2 == 0 then Even(a.n) else Odd(a.n))
  private def halve(even: Even): UIO[C] = ZIO.succeed(C(even.n / 2))
  private val halveEvens: StateMachine[Any, Nothing, A, C | Odd] = step(classify).on[Even, Odd](step(halve))

  /** Counting(2) acquires a resource (counted in `held`) that later steps rely on. */
  private def acquiring(held: Ref[Int], last: Counting => ZIO[Any, Boom, Stopped])(state: Counting): ZIO[Scope, Boom, Counting | Stopped] =
    state.n match
      case 2 => ZIO.acquireRelease(held.update(_ + 1))(_ => held.update(_ - 1)).as(Counting(1))
      case _ => last(state)

  private def acquiringMachine(held: Ref[Int], last: Counting => ZIO[Any, Boom, Stopped]) =
    step(acquiring(held, last)).loop[Counting, Stopped].named("acquiring")

  override def spec = suite("StateMachine")(
    test("a loop follows edges back to its input state until an output state"):
      for result <- countdown.run(Counting(3))
      yield assertTrue(result == Stopped(0))
    ,
    test("andThen sequences machines, and on runs a branch and passes the rest through"):
      for
        sequenced <- step(classify).andThen(step((_: Even | Odd) => ZIO.succeed(C(0)))).run(A(1))
        even      <- halveEvens.run(A(4))
        odd       <- halveEvens.run(A(3))
      yield assertTrue(sequenced == C(0), even == C(2), odd == Odd(3))
    ,
    test("the compiler rejects an unhandled state, a wrong branch split, and a run that starts partway"):
      // Checked by the compiler, here at test compile time.
      val unhandled = typeChecks("val m: StateMachine[Any, Nothing, A, C] = step(classify).on[Even, Odd](step(halve))")
      val missingRest = typeChecks("step(classify).on[Even, Nothing](step(halve))")
      val startPartway = typeChecks("halveEvens.run(Even(2))")
      val valid = typeChecks("val m: StateMachine[Any, Nothing, A, C | Odd] = step(classify).on[Even, Odd](step(halve))")
      assertTrue(!unhandled, !missingRest, !startPartway, valid)
    ,
    test("an unhandled error fails the machine with the state it failed in and the machines it ran in"):
      val failing = step((state: Counting) => if state.n == 1 then ZIO.fail(Boom("boom")) else countDown(state))
        .loop[Counting, Stopped].named("inner")
      val outer = step((s: Stopped) => ZIO.succeed(Counting(s.at + 3))).andThen(failing).named("outer")
      for result <- outer.run(Stopped(0)).either
      yield assertTrue(
        result == Left(StateMachine.Failed("Counting", Boom("boom"), List("outer", "inner"))),
        result.left.toOption.map(_.toString).contains("outer > inner failed in Counting: Boom(boom)"),
      )
    ,
    test("an error edge recovers to a state of the topology"):
      val recovering = step((state: Counting) => ZIO.fail(Boom("boom")).catchAll(_ => ZIO.succeed(Stopped(state.n))))
      for result <- recovering.run(Counting(1))
      yield assertTrue(result == Stopped(1))
    ,
    test("a resource acquired in one step is held by later steps and released when the machine stops"):
      for
        held      <- Ref.make(0)
        heldLater <- Ref.make(-1)
        result    <- acquiringMachine(held, _ => held.get.flatMap(heldLater.set).as(Stopped(1))).run(Counting(2))
        after     <- held.get
        during    <- heldLater.get
      yield assertTrue(result == Stopped(1), during == 1, after == 0)
    ,
    test("the resource is released when a later step fails or the machine is interrupted"):
      for
        held        <- Ref.make(0)
        failed      <- acquiringMachine(held, _ => ZIO.fail(Boom("boom"))).run(Counting(2)).either
        afterFail   <- held.get
        started     <- Promise.make[Nothing, Unit]
        fiber       <- acquiringMachine(held, _ => started.succeed(()) *> ZIO.never).run(Counting(2)).fork
        _           <- started.await
        _           <- fiber.interrupt
        afterInterr <- held.get
      yield assertTrue(failed.isLeft, afterFail == 0, afterInterr == 0)
    ,
    test("concurrent runs have separate scopes: interrupting one releases only its own resource"):
      for
        heldA    <- Ref.make(0)
        heldB    <- Ref.make(0)
        startedA <- Promise.make[Nothing, Unit]
        startedB <- Promise.make[Nothing, Unit]
        finishB  <- Promise.make[Nothing, Unit]
        fiberA   <- acquiringMachine(heldA, _ => startedA.succeed(()) *> ZIO.never).run(Counting(2)).fork
        fiberB   <- acquiringMachine(heldB, _ => startedB.succeed(()) *> finishB.await.as(Stopped(1))).run(Counting(2)).fork
        _        <- startedA.await *> startedB.await
        _        <- fiberA.interrupt
        afterA   <- heldA.get
        stillB   <- heldB.get
        _        <- finishB.succeed(())
        resultB  <- fiberB.join
        afterB   <- heldB.get
      yield assertTrue(afterA == 0, stillB == 1, resultB == Stopped(1), afterB == 0)
    ,
    test("logs each step with the machine and state type names, never state data"):
      for
        _    <- countdown.run(Counting(1))
        logs <- ZTestLogger.logOutput
        steps = logs.filter(_.message() == "State machine step").map(_.annotations)
      yield assertTrue(
        steps.map(a => (a("machine"), a("from"), a("to"))) ==
          Chunk(("countdown", "Counting", "Counting"), ("countdown", "Counting", "Stopped")),
      )
  ) @@ TestAspect.withLiveClock

  given CanEqual[Stopped, Stopped] = CanEqual.derived
  given CanEqual[C, C] = CanEqual.derived
  given CanEqual[C | Odd, C | Odd] = CanEqual.derived
  given CanEqual[Either[StateMachine.Failed[Boom], Stopped], Either[StateMachine.Failed[Boom], Stopped]] =
    CanEqual.derived
