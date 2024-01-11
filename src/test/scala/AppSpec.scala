import com.jamesward.zio_mavencentral.MavenCentral
import com.jamesward.zio_mavencentral.MavenCentral.given
import zio.cache.{Cache, Lookup}
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.http.*
import zio.test.*
import zio.test.Assertion.*
import zio.*

object AppSpec extends ZIOSpecDefault:

  def spec = suite("App")(
    test("routing"):
      defer:
        val blocker = ConcurrentMap.empty[MavenCentral.GroupArtifactVersion, Promise[Nothing, Unit]].run
        val latestCache = Cache.make(50, 60.minutes, Lookup(App.latest)).run
        val javadocExistsCache = Cache.make(50, 60.minutes, Lookup(App.javadocExists)).run

        val groupIdResp = App.appWithMiddleware(blocker, latestCache, javadocExistsCache).runZIO(Request.get(URL(Path.root, queryParams = QueryParams("groupId" -> "com.jamesward")))).run
        val artifactIdResp = App.appWithMiddleware(blocker, latestCache, javadocExistsCache).runZIO(Request.get(URL(Path.root / "com.jamesward", queryParams = QueryParams("artifactId" -> "travis-central-test")))).run
        val versionResp = App.appWithMiddleware(blocker, latestCache, javadocExistsCache).runZIO(Request.get(URL(Path.root / "com.jamesward" / "travis-central-test", queryParams = QueryParams("version" -> "0.0.15")))).run
        val latest = App.appWithMiddleware(blocker, latestCache, javadocExistsCache).runZIO(Request.get(URL(Path.root / "org.webjars" / "jquery" / "latest"))).run

        val groupIdRedir = App.appWithMiddleware(blocker, latestCache, javadocExistsCache).runZIO(Request.get(URL((Path.root / "com.jamesward").addTrailingSlash))).run
        val artifactIdRedir = App.appWithMiddleware(blocker, latestCache, javadocExistsCache).runZIO(Request.get(URL((Path.root / "com.jamesward" / "travis-central-test").addTrailingSlash))).run

        val indexPath = App.appWithMiddleware(blocker, latestCache, javadocExistsCache).runZIO(Request.get(URL(Path.root / "org.webjars" / "webjars-locator-core" / "0.52" / "index.html"))).run
        val filePath = App.appWithMiddleware(blocker, latestCache, javadocExistsCache).runZIO(Request.get(URL(Path.root / "org.webjars" / "webjars-locator-core" / "0.52" / "org" / "webjars" / "package-summary.html"))).run
        val notFoundFilePath = App.appWithMiddleware(blocker, latestCache, javadocExistsCache).runZIO(Request.get(URL(Path.root / "org.webjars" / "webjars-locator-core" / "0.52" / "asdf"))).run

        assertTrue(
          App.appWithMiddleware(blocker, latestCache, javadocExistsCache).runZIO(Request.get(URL(Path.empty))).run.status.isSuccess,
          App.appWithMiddleware(blocker, latestCache, javadocExistsCache).runZIO(Request.get(URL(Path.root))).run.status.isRedirection,
          groupIdResp.status.isRedirection,
          groupIdResp.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward")),
          artifactIdResp.status.isRedirection,
          artifactIdResp.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward/travis-central-test")),
          versionResp.status.isRedirection,
          versionResp.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward/travis-central-test/0.0.15")),
          latest.status.isRedirection,
          latest.headers.get(Header.Location).exists(_.url.path == Path.decode("/org.webjars/jquery/3.7.1")),
          groupIdRedir.status.isRedirection,
          groupIdRedir.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward")),
          artifactIdRedir.status.isRedirection,
          artifactIdRedir.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward/travis-central-test")),
          indexPath.status.isSuccess,
          filePath.status.isSuccess,
          notFoundFilePath.status == Status.NotFound,
        )
  ).provide(Client.default)
