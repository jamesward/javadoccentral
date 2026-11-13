import com.augustnagro.magnum.{SqlExceptionEvent, SqlLogger, SqlSuccessEvent}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import zio.*
import zio.direct.*

import java.net.{URI, URLDecoder}
import java.nio.charset.StandardCharsets.UTF_8
import javax.sql.DataSource
import scala.util.Try

/** Postgres connection setup, shared by the app (`App`) and the release-phase migrator (`FlywayMigrate`). */
object Db:

  case class Jdbc(url: String, user: String, password: String) derives CanEqual

  // magnum needs a `SqlLogger` for every query. This one never touches the
  // events' untyped `params` (bind values), only the SQL text and the cause.
  // Query timing is logged by the callers (`SymbolSearch`), through ZIO.
  given SqlLogger = new SqlLogger:
    def log(event: SqlSuccessEvent): Unit = ()
    def exceptionMsg(event: SqlExceptionEvent): String =
      s"error executing query:\n${event.sql}\n${event.cause}"

  /**
   * Heroku's `DATABASE_URL` is libpq-style (`postgres://user:pass@host:port/db`).
   * Heroku Postgres requires TLS, so `sslmode=require` is added unless the URL
   * sets an `sslmode` (local plaintext dev uses `?sslmode=disable`).
   */
  def parse(raw: String): Either[String, Jdbc] =
    for
      uri    <- Try(URI(raw)).toEither.left.map(t => s"not a URI: ${t.getMessage}")
      _      <- Option(uri.getScheme).filter(s => s == "postgres" || s == "postgresql").toRight("scheme must be postgres://")
      userPw <- Option(uri.getRawUserInfo).toRight("no user:password")
      creds  <- userPw.split(":", 2) match
                  case Array(u, p) => Right((URLDecoder.decode(u, UTF_8), URLDecoder.decode(p, UTF_8)))
                  case _           => Left("no user:password")
      host   <- Option(uri.getHost).filter(_.nonEmpty).toRight("no host")
      db     <- Option(uri.getPath).map(_.stripPrefix("/")).filter(_.nonEmpty).toRight("no database name")
    yield
      val port = if uri.getPort > 0 then uri.getPort else 5432
      val query = Option(uri.getRawQuery).filter(_.nonEmpty) match
        case Some(q) if q.contains("sslmode=") => q
        case Some(q)                           => s"$q&sslmode=require"
        case None                              => "sslmode=require"
      Jdbc(s"jdbc:postgresql://$host:$port/$db?$query", creds._1, creds._2)

  val jdbcFromEnv: ZIO[Any, Throwable, Jdbc] =
    defer:
      val url = ZIO.systemWith(_.env("DATABASE_URL")).someOrFail(RuntimeException("DATABASE_URL env var not set")).run
      ZIO.fromEither(parse(url)).mapError(e => RuntimeException(s"DATABASE_URL: $e")).run

  /**
   * A small HikariCP pool. `initializationFailTimeout = -1` lets the app start
   * while Postgres is unreachable: the symbol index is an optional feature, so
   * browsing javadocs must not depend on it. Queries fail (and are logged)
   * until the database is back.
   *
   * `connectionTimeout` (how long a query waits for a free connection) is far
   * below Heroku's 30 s router timeout: Hikari's default is also 30 s, so a busy
   * or unreachable database turned sitemap and search requests into H12s
   * instead of the handlers' own fast error responses.
   */
  val connectionTimeout: Duration = 5.seconds

  def dataSourceLayer(jdbc: Jdbc, poolName: String, maxPoolSize: Int): ZLayer[Any, Throwable, DataSource] =
    ZLayer.scoped:
      ZIO.fromAutoCloseable:
        ZIO.attemptBlocking:
          val config = HikariConfig()
          config.setJdbcUrl(jdbc.url)
          config.setUsername(jdbc.user)
          config.setPassword(jdbc.password)
          config.setPoolName(poolName)
          config.setMaximumPoolSize(maxPoolSize)
          config.setInitializationFailTimeout(-1)
          config.setConnectionTimeout(connectionTimeout.toMillis)
          HikariDataSource(config)
    .map(env => ZEnvironment[DataSource](env.get))

  val fromEnv: ZLayer[Any, Throwable, DataSource] =
    ZLayer.fromZIO(jdbcFromEnv).flatMap(env => dataSourceLayer(env.get, "javadocs", 5))
