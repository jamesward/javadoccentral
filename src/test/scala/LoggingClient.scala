import zio.*
import zio.http.*

/**
 * The tests' HTTP client: `Client.default`, logging every outgoing request at INFO with its
 * status, URL, duration and the response headers that say who answered it. Production logs
 * these at DEBUG (`App.clientLayer`); the tests log them so a CI failure against the live
 * Maven Central shows what each mirror actually returned (on Nov 20 2026 every jar and
 * `maven-metadata.xml` request got a 404, and the logs couldn't say from where).
 */
object LoggingClient:
  private def custom(name: String): Header.HeaderType = Header.Custom(name, "").headerType

  // Who served the response: origin, CDN (Cloudflare fronts repo1.maven.org) and cache state.
  private val responseHeaders: Set[Header.HeaderType] = Set(
    Header.Server, Header.Via, Header.Age, Header.Date, Header.Location,
    Header.ContentType, Header.ContentLength, Header.LastModified,
    custom("cf-ray"), custom("cf-cache-status"), custom("x-cache"), custom("x-served-by"),
  )

  val layer: ZLayer[Any, Throwable, Client] =
    Client.default.update(_ @@ ZClientAspect.requestLogging(
      level = _ => LogLevel.Info,
      loggedRequestHeaders = Set(Header.UserAgent),
      loggedResponseHeaders = responseHeaders,
    ))
