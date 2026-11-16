import zio.*
import zio.test.*

/** The generic [[StateMachine]] driver: cycles, final start states, failures and step logs. */
object StateMachineSpec extends ZIOSpecDefault:

  case class Counting(n: Int)
  case class Stopped(at: Int)
  case class Failed(reason: String)

  private val countdown = StateMachine[Counting, Stopped]("countdown")
  private def step(state: Counting): UIO[Counting | Stopped] =
    ZIO.succeed(if state.n == 0 then Stopped(0) else Counting(state.n - 1))

  override def spec = suite("StateMachine")(
    test("follows edges back to an active state until a final state"):
      for result <- countdown.run(Counting(3))(step)
      yield assertTrue(result == Stopped(0))
    ,
    test("a final start state runs no transition"):
      for result <- countdown.run(Stopped(7))(_ => ZIO.dieMessage("no step expected"))
      yield assertTrue(result == Stopped(7))
    ,
    test("a failing transition fails the machine"):
      for result <- StateMachine[Counting, Stopped]("failing").run(Counting(1))(_ => ZIO.fail(Failed("boom"))).either
      yield assertTrue(result == Left(Failed("boom")))
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
  given CanEqual[Either[Failed, Stopped], Either[Failed, Stopped]] = CanEqual.derived
