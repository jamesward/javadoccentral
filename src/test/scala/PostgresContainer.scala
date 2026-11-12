import com.dimafeng.testcontainers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.direct.*

import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.Semaphore
import javax.sql.DataSource

// One Postgres container per test JVM (started on first use, reaped by Ryuk at
// exit), with a fresh database per spec cloned from a template that Flyway
// migrated once. Specs stay isolated and can run in parallel without paying a
// container start + migration each. Container starts and CREATE DATABASE go
// through one lock: under rootless Docker, programming two ephemeral host ports
// at once races ("address already in use").
object PostgresContainer:

  // Matches the Heroku Postgres plan's version (heroku pg:info).
  val image = "postgres:18.4"

  private val templateDb = "javadocs_template"
  private val lock = Semaphore(1)

  private lazy val container: PostgreSQLContainer =
    lock.acquire()
    try
      val c = PostgreSQLContainer(dockerImageNameOverride = DockerImageName.parse(image))
      c.start()
      c
    finally lock.release()

  private def jdbc(db: String): Db.Jdbc =
    val authority = container.jdbcUrl.stripPrefix("jdbc:postgresql://").takeWhile(_ != '/')
    Db.Jdbc(s"jdbc:postgresql://$authority/$db", container.username, container.password)

  private def admin(sql: String): Unit =
    val conn = DriverManager.getConnection(container.jdbcUrl, container.username, container.password).nn
    try conn.createStatement().nn.execute(sql): Unit
    finally conn.close()

  private lazy val templateReady: Unit =
    admin(s"""CREATE DATABASE "$templateDb"""")
    Unsafe.unsafe(implicit u =>
      Runtime.default.unsafe.run(
        ZIO.serviceWithZIO[DataSource](FlywayMigrate.migrate).provide(Db.dataSourceLayer(jdbc(templateDb), "template", 2))
      ).getOrThrow()
    ): Unit

  private def createFromTemplate(): String =
    val db = "javadocs_" + UUID.randomUUID().toString.replace("-", "")
    lock.acquire()
    try admin(s"""CREATE DATABASE "$db" TEMPLATE "$templateDb"""")
    finally lock.release()
    db

  /** A freshly migrated, empty database for one spec (or dev server), dropped when its scope closes. */
  val freshDatabase: ZLayer[Any, Throwable, DataSource] =
    ZLayer.scoped:
      defer:
        ZIO.attemptBlocking(templateReady).run
        val db = ZIO.acquireRelease(ZIO.attemptBlocking(createFromTemplate())): name =>
          ZIO.attemptBlocking(admin(s"""DROP DATABASE IF EXISTS "$name" WITH (FORCE)""")).ignoreLogged
        .run
        Db.dataSourceLayer(jdbc(db), s"test-$db", 3).build.run.get[DataSource]
