import com.dimafeng.testcontainers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import zio.*
import zio.redis.RedisConfig

// Valkey (a Redis-compatible server) running in a Testcontainers-managed Docker
// container. This replaces zio-redis-embedded, which extracts and fork/exec's a
// bundled native redis-server binary and intermittently fails with ETXTBSY
// ("Text file busy") when multiple test suites start it in parallel.
//
// The layer produces a `RedisConfig` pointing at the container's mapped port,
// so `Redis.singleNode` builds the client on top exactly as it did before.
object ValkeyContainer:

  val image = "valkey/valkey:8.1.6"
  private val redisPort = 6379

  // Each suite starts a fresh container per test (ZIO Test `provide`) for state
  // isolation, so a full run creates many containers in quick succession. Under
  // rootless Docker the dynamic host-port publisher occasionally races and fails
  // with "address already in use" (RootlessKit PortManager.AddPort). The failure
  // is transient: a retry creates a new container that is published on a fresh
  // random port. Bound the retries so a genuinely broken Docker still fails fast.
  private val startRetry: Schedule[Any, Any, Any] =
    (Schedule.spaced(250.millis) && Schedule.recurs(8)).jittered

  val layer: ZLayer[Any, Throwable, RedisConfig] =
    ZLayer.scoped:
      ZIO
        .acquireRelease(
          ZIO
            .attemptBlocking:
              val container = GenericContainer(
                image,
                exposedPorts = Seq(redisPort),
                waitStrategy = Wait.forListeningPort(),
              )
              container.start()
              container
            .retry(startRetry)
        )(container => ZIO.attemptBlocking(container.stop()).ignoreLogged)
        .map(container => RedisConfig(container.host, container.mappedPort(redisPort)))
