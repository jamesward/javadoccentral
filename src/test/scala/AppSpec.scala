import App.given
import MavenCentral.*
import zio.direct.*
import zio.http.{Client, Header, Path, QueryParams, Request, Status, URL}
import zio.test.*
import zio.test.Assertion.*
import zio.{Chunk, Runtime, Scope, ZIO}

import java.net.URI
import java.nio.file.Files

object AppSpec extends ZIOSpecDefault:

  given CanEqual[Status, Status] = CanEqual.derived

  def spec = suite("App")(
    test("routing") {
      defer {
        val groupIdResp = App.appWithMiddleware.runZIO(Request.get(URL(Path.root, queryParams = QueryParams("groupId" -> "com.jamesward")))).run
        val artifactIdResp = App.appWithMiddleware.runZIO(Request.get(URL(Path.root / "com.jamesward", queryParams = QueryParams("artifactId" -> "travis-central-test")))).run
        val versionResp = App.appWithMiddleware.runZIO(Request.get(URL(Path.root / "com.jamesward" / "travis-central-test", queryParams = QueryParams("version" -> "0.0.15")))).run
        val latest = App.appWithMiddleware.runZIO(Request.get(URL(Path.root / "org.webjars" / "jquery" / "latest"))).run
        val groupIdRedir = App.appWithMiddleware.runZIO(Request.get(URL((Path.root / "com.jamesward").addTrailingSlash))).run
        val artifactIdRedir = App.appWithMiddleware.runZIO(Request.get(URL((Path.root / "com.jamesward" / "travis-central-test").addTrailingSlash))).run

        val indexPath = App.appWithMiddleware.runZIO(Request.get(URL(Path.root / "org.webjars" / "webjars-locator-core" / "0.52" / "index.html"))).run
        val filePath = App.appWithMiddleware.runZIO(Request.get(URL(Path.root / "org.webjars" / "webjars-locator-core" / "0.52" / "org" / "webjars" / "package-summary.html"))).run
        val notFoundFilePath = App.appWithMiddleware.runZIO(Request.get(URL(Path.root / "org.webjars" / "webjars-locator-core" / "0.52" / "asdf"))).exit.run

        assertTrue(
          App.appWithMiddleware.runZIO(Request.get(URL(Path.empty))).run.status.isSuccess,
          App.appWithMiddleware.runZIO(Request.get(URL(Path.root))).run.status.isSuccess,

          groupIdResp.status.isRedirection,
          groupIdResp.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward")),

          artifactIdResp.status.isRedirection,
          artifactIdResp.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward/travis-central-test")),

          versionResp.status.isRedirection,
          versionResp.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward/travis-central-test/0.0.15")),

          latest.status.isRedirection,
          latest.headers.get(Header.Location).exists(_.url.path == Path.decode("/org.webjars/jquery/3.6.4")),

          groupIdRedir.status.isRedirection,
          groupIdRedir.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward")),

          artifactIdRedir.status.isRedirection,
          artifactIdRedir.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward/travis-central-test")),

          indexPath.status.isSuccess,
          filePath.status.isSuccess,
          notFoundFilePath.isFailure,
        )
      }
    },
  ).provide(Client.default, Scope.default)
