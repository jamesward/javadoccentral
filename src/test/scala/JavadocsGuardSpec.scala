import zio.http.*
import zio.test.*

object JavadocsGuardSpec extends ZIOSpecDefault:

  private def gav(path: String): Option[String] =
    JavadocsGuard.browsedGav(Request.get(URL.decode(path).toOption.get)).map(_.toString)

  def spec = suite("JavadocsGuard.browsedGav")(
    test("browse pages and /llms version listings name the GAV whose jar they fetch") {
      assertTrue(
        gav("/com.example/foo/1.0") == Some("com.example/foo/1.0"),
        gav("/com.example/foo/1.0/index.html") == Some("com.example/foo/1.0"),
        gav("/llms/com.example/foo/1.0") == Some("com.example/foo/1.0"),
      )
    },
    test("routes that fetch no jar name none") {
      assertTrue(
        gav("/llms/com.example/foo").isEmpty,
        gav("/llms/com.example").isEmpty,
        gav("/llms.txt").isEmpty,
        gav("/llms/com.example/foo/1.0/extra").isEmpty,
        gav("/api/latest-version").isEmpty,
        gav("/com.example/foo/latest").isEmpty,
        gav("/com.example/foo/badge.svg").isEmpty,
        gav("/sitemap/com.example/foo/1.0").isEmpty,
      )
    },
  )
