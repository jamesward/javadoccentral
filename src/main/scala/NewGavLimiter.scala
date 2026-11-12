import com.jamesward.zio_http_guard.BadActorMiddleware
import com.jamesward.zio_mavencentral.{GavCacheMiddleware, MavenCentral}
import zio.*
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.http.*

import java.time.Instant

/**
 * Per-IP limit on how many *new* (not yet cached) GAVs a client can open.
 *
 * Crawlers on cloud IPs walk every version of an artifact
 * (`/com.vaadin/flow-html-components/<each version>/...`) with User-Agents
 * the UA-keyed `CrawlerLimiter` doesn't know. Each new GAV is a javadoc jar
 * download, so a single client could start hundreds a minute. A person
 * browsing opens a few. Requests for GAVs already in the cache are free and
 * never counted.
 *
 * Over the limit, the request gets `429` with `Retry-After` and a
 * `New GAV limit` log line naming the IP.
 */
final case class NewGavLimiter(recent: ConcurrentMap[String, Ref[Chunk[Instant]]], denied: Ref[Long]):
  import NewGavLimiter.*

  /** Records a new-GAV request for `ip` if it's under the limit. */
  def tryNew(ip: String): UIO[Boolean] =
    defer:
      val now   = Clock.instant.run
      val fresh = Ref.make(Chunk.empty[Instant]).run
      val ref   = recent.putIfAbsent(ip, fresh).run.getOrElse(fresh)
      ref.modify: times =>
        val live = times.filter(isRecent(_, now))
        if live.size < maxNewGavs then (true, live :+ now) else (false, live)
      .tap(allowed => denied.update(_ + 1).unless(allowed))
      .run

  /** Tracked IPs and denials since the previous call. */
  val stats: UIO[(Int, Long)] =
    recent.toChunk.map(_.size).zip(denied.getAndSet(0L))

  private val sweep: UIO[Unit] =
    defer:
      val now     = Clock.instant.run
      val entries = recent.toChunk.run
      ZIO.foreachDiscard(entries): (ip, ref) =>
        ref.get.flatMap: times =>
          recent.remove(ip, ref).unit.when(times.forall(!isRecent(_, now)))
      .run

object NewGavLimiter:

  val window: Duration = 10.minutes
  val maxNewGavs: Int  = 30

  private def isRecent(at: Instant, now: Instant): Boolean =
    java.time.Duration.between(at, now).compareTo(window.asJava) < 0

  // First path segments that are routes of their own, not a groupId.
  private val nonGavRoots = Set("api", "llms", "sitemap", ".well-known", "mcp")

  private given CanEqual[MavenCentral.Version, MavenCentral.Version] = CanEqual.derived

  /** The GAV a browse request would fetch a jar for, if any. */
  def browsedGav(request: Request): Option[MavenCentral.GroupArtifactVersion] =
    if request.path.segments.headOption.exists(nonGavRoots.contains) then None
    else
      GavCacheMiddleware.gavFromPath(request.path)
        .filterNot(gav => gav.version == MavenCentral.Version.latest || gav.version.toString == "badge.svg")

  private val limited: Response =
    Response(
      Status.TooManyRequests,
      Headers(Header.RetryAfter.ByDuration(5.minutes)),
      Body.fromString("Too many new artifacts opened; try again later."),
    )

  val middleware: HandlerAspect[NewGavLimiter & TrafficStats & Extractor.JavadocCache, Unit] =
    HandlerAspect.interceptIncomingHandler:
      Handler.fromFunctionZIO[Request]: request =>
        (browsedGav(request), BadActorMiddleware.forwardedFor(request)) match
          case (Some(gav), Some(ip)) =>
            defer:
              val cached = ZIO.serviceWithZIO[Extractor.JavadocCache](_.cache.contains(gav)).run
              val allowed =
                if cached then true
                else
                  ZIO.serviceWithZIO[TrafficStats](_.recordNewGav(ip)).run
                  ZIO.serviceWithZIO[NewGavLimiter](_.tryNew(ip)).run
              if allowed then request -> ()
              else
                ZIO.logAnnotate(LogAnnotation("clientIp", ip), LogAnnotation("gav", gav.toString))(ZIO.logWarning("New GAV limit")).run
                ZIO.fail(limited).run
          case _ =>
            ZIO.succeed(request -> ())

  val layer: ZLayer[Any, Nothing, NewGavLimiter] =
    ZLayer.scoped:
      defer:
        val limiter = NewGavLimiter(ConcurrentMap.empty[String, Ref[Chunk[Instant]]].run, Ref.make(0L).run)
        limiter.sweep.repeat(Schedule.spaced(1.minute)).forkScoped.run
        limiter
