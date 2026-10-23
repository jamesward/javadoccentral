import com.jamesward.zio_mavencentral.JarCache
import com.jamesward.zio_mavencentral.MavenCentral.gav
import zio.test.*

object ErrorMessagesSpec extends ZIOSpecDefault:

  def spec = suite("MCP.ErrorMessages")(
    test("an unreachable upstream says it is temporary and to retry, with the reason") {
      val e   = JarCache.UpstreamUnavailableError(gav("org.webjars", "webjars-locator-core", "0.59"),
        "NoRouteToHostException (repo1.maven.org/104.18.18.12:443)")
      val msg = MCP.ErrorMessages.jar(MCP.ErrorMessages.Jar.Javadoc)(e)
      assertTrue(
        msg.contains("org.webjars:webjars-locator-core:0.59"),
        msg.contains("NoRouteToHostException (repo1.maven.org/104.18.18.12:443)"),
        msg.contains("retry"),
        !msg.contains("null:"),
        MCP.ErrorMessages.entry(MCP.ErrorMessages.Jar.Sources)(e).contains("sources jar"),
      )
    },
  )
