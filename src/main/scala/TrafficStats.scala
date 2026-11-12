import com.jamesward.zio_http_guard.BadActorMiddleware
import zio.*
import zio.concurrent.ConcurrentMap
import zio.http.*

/**
 * Per-minute traffic summary for spotting floods: total requests, distinct
 * client IPs, and the top IPs and `/16` networks by request count, plus how
 * many requests opened a GAV that wasn't cached (a jar download).
 *
 * A single heavy hitter shows up in `topIps`; a swarm spread over many IPs
 * shows up as a high `distinctIps` with `topIps` near 1 each, and often in
 * `topNetworks` when it comes from one provider.
 */
final case class TrafficStats(
  byIp: ConcurrentMap[String, Long],
  newGavsByIp: ConcurrentMap[String, Long],
):
  import TrafficStats.*

  private def bump(map: ConcurrentMap[String, Long], ip: String): UIO[Unit] =
    map.compute(ip, (_, n) => Some(n.getOrElse(0L) + 1)).unit

  def recordRequest(ip: String): UIO[Unit] = bump(byIp, ip)
  def recordNewGav(ip: String): UIO[Unit]  = bump(newGavsByIp, ip)

  private def drain(map: ConcurrentMap[String, Long]): UIO[Chunk[(String, Long)]] =
    map.toChunk.flatMap(entries => ZIO.foreachDiscard(entries)((ip, _) => map.remove(ip)).as(entries))

  private def top(counts: Iterable[(String, Long)]): String =
    counts.toSeq.sortBy(-_._2).take(topN).map((key, n) => s"$key=$n").mkString(",")

  val report: ZIO[NewGavLimiter & Extractor.JavadocCache & Extractor.SourcesCache, Nothing, Unit] =
    for
      requests     <- drain(byIp)
      newGavs      <- drain(newGavsByIp)
      limiterStats <- ZIO.serviceWithZIO[NewGavLimiter](_.stats)
      javadoc      <- ZIO.serviceWithZIO[Extractor.JavadocCache](_.misses.metrics)
      sources      <- ZIO.serviceWithZIO[Extractor.SourcesCache](_.misses.metrics)
      warm         <- ZIO.serviceWithZIO[Extractor.JavadocCache](_.cache.warmCount)
      networks      = requests.groupMapReduce((ip, _) => network(ip))(_._2)(_ + _)
      _            <- ZIO.logAnnotate(
                        LogAnnotation("requests", requests.map(_._2).sum.toString),
                        LogAnnotation("distinctIps", requests.size.toString),
                        LogAnnotation("topIps", top(requests)),
                        LogAnnotation("topNetworks", top(networks)),
                        LogAnnotation("newGavs", newGavs.map(_._2).sum.toString),
                        LogAnnotation("topNewGavIps", top(newGavs)),
                        LogAnnotation("newGavLimitedIps", limiterStats._1.toString),
                        LogAnnotation("newGavDenied", limiterStats._2.toString),
                        LogAnnotation("javadocFetchInFlight", javadoc.inFlight.toString),
                        LogAnnotation("javadocFetchQueued", javadoc.inQueue.toString),
                        LogAnnotation("sourcesFetchInFlight", sources.inFlight.toString),
                        LogAnnotation("sourcesFetchQueued", sources.inQueue.toString),
                        LogAnnotation("javadocWarm", warm.toString),
                      )(ZIO.logInfo("traffic stats"))
    yield ()

object TrafficStats:

  val interval: Duration = 1.minute
  private val topN = 10

  /** The `/16` of an IPv4 address, or the address itself otherwise. */
  def network(ip: String): String =
    ip.split('.') match
      case Array(a, b, _, _) => s"$a.$b.0.0/16"
      case _                 => ip

  /** Counts every request by client IP. Apply outermost. */
  val middleware: HandlerAspect[TrafficStats, Unit] =
    HandlerAspect.interceptIncomingHandler:
      Handler.fromFunctionZIO[Request]: request =>
        ZIO.foreachDiscard(BadActorMiddleware.forwardedFor(request))(ip => ZIO.serviceWithZIO[TrafficStats](_.recordRequest(ip)))
          .as(request -> ())

  val layer: ZLayer[NewGavLimiter & Extractor.JavadocCache & Extractor.SourcesCache, Nothing, TrafficStats] =
    ZLayer.scoped:
      for
        byIp    <- ConcurrentMap.empty[String, Long]
        newGavs <- ConcurrentMap.empty[String, Long]
        stats    = TrafficStats(byIp, newGavs)
        env     <- ZIO.environment[NewGavLimiter & Extractor.JavadocCache & Extractor.SourcesCache]
        _       <- stats.report.provideEnvironment(env).repeat(Schedule.spaced(interval)).delay(interval).forkScoped
      yield stats
