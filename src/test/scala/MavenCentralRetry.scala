import zio.*
import zio.test.*

/**
 * For tests that hit the live Maven Central: retries a failed test up to 5 times, waiting
 * 2, 4, 8, 16 and 32 seconds (jittered) between attempts. Maven Central sometimes fails
 * every request for a few minutes; on Nov 20 2026 CI got a 404 for every jar and
 * `maven-metadata.xml` for over 5 minutes, which failed 49 tests.
 *
 * `TestAspect.retry` runs the schedule on the live clock, so the backoff really waits,
 * even in a suite on the `TestClock`. Apply it per test, not to a whole suite, so a test
 * that never touches the network fails at once instead of after a minute of retries. A
 * suite-level `timeout` still caps each test, retries included.
 *
 * Each retry logs `"Retrying a test that hits Maven Central"` at WARN with the attempt that
 * failed, why, and the wait before the next one; ZIO Test prints only the last failure.
 * Pair it with [[LoggingClient]] to see what Maven Central returned on each attempt.
 */
object MavenCentralRetry:
  private def describe(failure: TestFailure[Any]): String =
    failure match
      case TestFailure.Assertion(_, _) => "an assertion failed"
      case TestFailure.Runtime(cause, _) =>
        cause.failureOption.orElse(cause.dieOption).fold(cause.prettyPrint)(_.toString).take(500)

  private val schedule =
    (Schedule.exponential(2.seconds).jittered && Schedule.recurs(5) && Schedule.identity[TestFailure[Any]])
      .tapOutput: (delay, retry, failure) =>
        ZIO.logAnnotate(
          LogAnnotation("attempt", (retry + 1).toString),
          LogAnnotation("next_in_ms", delay.toMillis.toString),
          LogAnnotation("failure", describe(failure)),
        )(ZIO.logWarning("Retrying a test that hits Maven Central"))

  val aspect: TestAspect[Nothing, Any, Nothing, Any] = TestAspect.retry(schedule)
