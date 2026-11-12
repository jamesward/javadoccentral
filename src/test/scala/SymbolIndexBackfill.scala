import com.jamesward.zio_mavencentral.MavenCentral.{ArtifactId, GroupArtifactVersion, GroupId, Version}
import zio.*
import zio.direct.*

import java.nio.file.{Files, Path}
import javax.sql.DataSource
import scala.jdk.CollectionConverters.*

/**
 * One-time migration: loads the Redis symbol index export (see MIGRATION.md) into
 * Postgres through the app's own write path (`SymbolIndex.replace`), so names are
 * filtered the same way and a newer version the live app already indexed is kept.
 * Applies the Flyway migrations first. Safe to re-run.
 *
 *   ./sbta "Test/runMain SymbolIndexBackfill target/redis-export heroku"
 *
 * `heroku` reads the target database's URL with `heroku config:get DATABASE_URL`;
 * otherwise the `DATABASE_URL` environment variable is used.
 */
object SymbolIndexBackfill extends ZIOAppDefault:

  private def rows(file: Path): Task[Vector[(String, String)]] =
    ZIO.attemptBlocking:
      Files.readAllLines(file).nn.asScala.toVector.flatMap: line =>
        line.split('\t') match
          case Array(a, b) => Vector(a -> b)
          case _           => Vector.empty

  private def toGav(ga: String, version: String): Option[GroupArtifactVersion] =
    ga.split(':') match
      case Array(g, a) => Some(GroupArtifactVersion(GroupId(g), ArtifactId(a), Version(version)))
      case _           => None

  private def herokuDatabaseUrl: Task[Db.Jdbc] =
    ZIO.attemptBlocking:
      val process = ProcessBuilder("heroku", "config:get", "DATABASE_URL").redirectError(ProcessBuilder.Redirect.INHERIT).start().nn
      String(process.getInputStream.nn.readAllBytes().nn).trim
    .flatMap(url => ZIO.fromEither(Db.parse(url)).mapError(e => RuntimeException(s"DATABASE_URL: $e")))

  private def backfill(exportDir: Path): ZIO[SymbolIndex & DataSource, Throwable, Unit] =
    defer:
      val migrations = ZIO.serviceWithZIO[DataSource](FlywayMigrate.migrate).run
      val symbols = rows(exportDir.resolve("symbols.tsv")).run
      val versions = rows(exportDir.resolve("gavcache.tsv")).run.toMap
      val byGa = symbols.groupMap(_._2)(_._1)
      val gavs = byGa.toList.flatMap((ga, fqns) => versions.get(ga).flatMap(toGav(ga, _)).map(_ -> fqns.toSet))
      Console.printLine(s"migrations applied: $migrations; ${symbols.size} rows, ${byGa.size} artifacts, ${gavs.size} with a version").run
      val (duration, written) = ZIO.foreachPar(gavs)((gav, fqns) => SymbolIndex.replace(gav, fqns)).withParallelism(4).timed.run
      Console.printLine(s"written ${written.count(identity)}, kept newer ${written.count(!_)}, in ${duration.toSeconds}s").run

  def run =
    defer:
      val args = getArgs.run
      val exportDir = Path.of(args.headOption.getOrElse("target/redis-export"))
      val jdbc = (if args.contains("heroku") then herokuDatabaseUrl else Db.jdbcFromEnv).run
      backfill(exportDir).provide(Db.dataSourceLayer(jdbc, "backfill", 5), SymbolIndex.layer).run
