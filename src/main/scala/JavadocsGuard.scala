import com.jamesward.zio_http_guard.BadActorMiddleware
import com.jamesward.zio_http_guard.guard.*
import com.jamesward.zio_mavencentral.{GavCacheMiddleware, MavenCentral}
import zio.*
import zio.http.*

/**
 * javadocs.dev's configuration of zio-http-guard.
 *
 * Crawlers on cloud IPs walk every version of an artifact
 * (`/com.vaadin/flow-html-components/<each version>/...`), and each GAV not
 * yet cached is a javadoc jar download. `new-key` limits each client to
 * `GUARD_NEW_KEYS_PER_CLIENT` (default 30) uncached GAVs per
 * `GUARD_NEW_KEYS_WINDOW` (default 10m) and answers the rest with `429`,
 * without blocking the client's other requests. A declared crawler is one
 * client across all its IPs (Meta's crawler rotated through 56 addresses in
 * 3.5 minutes, so a per-IP limit never fired); anyone else is one per IP.
 * Cached GAVs never count, so browsing is free. `probe-shape` answers
 * scanner paths (`.env`, `.php`, `wp-admin`, …; zio-http-guard's
 * `BadActorMiddleware.defaultSuspect`) with a cheap `404` before any route
 * can read them as Maven coordinates; the client is then flagged, so its
 * later requests get the block response for `GUARD_FLAG_FOR`.
 * `client-hints` blocks requests whose Chrome client hints contradict their
 * User-Agent.
 * Thresholds and actions are `GUARD_*` config vars (see zio-http-guard's
 * README).
 */
object JavadocsGuard:

  // First path segments that are routes of their own, not a groupId.
  private val nonGavRoots = Set("api", "llms", "sitemap", ".well-known", "mcp")

  private given CanEqual[MavenCentral.Version, MavenCentral.Version] = CanEqual.derived

  /** The GAV a browse request would fetch a jar for, if any. */
  def browsedGav(request: Request): Option[MavenCentral.GroupArtifactVersion] =
    if request.path.segments.headOption.exists(nonGavRoots.contains) then None
    else
      GavCacheMiddleware.gavFromPath(request.path)
        .filterNot(gav => gav.version == MavenCentral.Version.latest || gav.version.toString == "badge.svg")

  // Conditional requests get a 304 from `GavCacheMiddleware` before any jar is fetched.
  private def isConditional(request: Request): Boolean =
    request.headers.contains(Header.IfNoneMatch.name) || request.headers.contains(Header.IfModifiedSince.name)

  private def costlyGav(request: Request): Option[MavenCentral.GroupArtifactVersion] =
    if isConditional(request) then None else browsedGav(request)

  // MCP clients' OAuth / OIDC discovery also tries `.well-known` under the
  // endpoint (`/mcp/.well-known/oauth-protected-resource`), but
  // `defaultSuspect` allows it only as the first segment. A probe flags the
  // whole client, so that locked Codex out of `/mcp`: judge the path without
  // its `.well-known` segments.
  private def isProbe(request: Request): Boolean =
    val path = request.path.segments.filterNot(_ == ".well-known").foldLeft(Path.root)(_ / _)
    BadActorMiddleware.defaultSuspect(request.copy(url = request.url.path(path)))

  private val probeShape: Detector =
    val reason = Reason("probe-shape")
    Detector.pure(DetectorName("probe-shape")): (request, _) =>
      Option.when(isProbe(request))(Verdict(Tier.Confirmed, reason, Some(Response.notFound)))

  val layer: ZLayer[Extractor.JavadocCache, Config.Error, Guard] =
    ZLayer.scoped:
      for
        cache    <- ZIO.service[Extractor.JavadocCache]
        config   <- ZIO.config(GuardConfig.config())
        settings <- ZIO.config(NewKeyPerClient.settings())
        newKeys  <- NewKeyPerClient.make(settings, costlyGav, gav => cache.cache.contains(gav).map(!_), ClientKey.crawlerOrIp())
        guard    <- Guard.make(
                      config,
                      Chunk(probeShape, newKeys, ClientHints.detector),
                      statsKey = request => browsedGav(request).map(_.toString).orElse(Some(request.path.encode)),
                    )
      yield guard
