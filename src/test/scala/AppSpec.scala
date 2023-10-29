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

        /*
        assertTrue broken with defer:
        [error] 31 |        assertTrue(
        [error]    |java.lang.Exception: Expected an expression. This is a partially applied Term. Try eta-expanding the term first.
         */

        assert(App.appWithMiddleware(blocker, latestCache, javadocExistsCache).runZIO(Request.get(URL(Path.empty))).run.status.isSuccess)(isTrue) &&
        assert(App.appWithMiddleware(blocker, latestCache, javadocExistsCache).runZIO(Request.get(URL(Path.root))).run.status.isSuccess)(isTrue) &&
        assert(groupIdResp.status.isRedirection)(isTrue) &&
        assert(groupIdResp.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward")))(isTrue) &&
        assert(artifactIdResp.status.isRedirection)(isTrue) &&
        assert(artifactIdResp.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward/travis-central-test")))(isTrue) &&
        assert(versionResp.status.isRedirection)(isTrue) &&
        assert(versionResp.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward/travis-central-test/0.0.15")))(isTrue) &&
        assert(latest.status.isRedirection)(isTrue) &&
        assert(latest.headers.get(Header.Location).exists(_.url.path == Path.decode("/org.webjars/jquery/3.7.1")))(isTrue) &&
        assert(groupIdRedir.status.isRedirection)(isTrue)
        assert(groupIdRedir.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward")))(isTrue) &&
        assert(artifactIdRedir.status.isRedirection)(isTrue) &&
        assert(artifactIdRedir.headers.get(Header.Location).exists(_.url.path == Path.decode("/com.jamesward/travis-central-test")))(isTrue) &&
        assert(indexPath.status.isSuccess)(isTrue) &&
        assert(filePath.status.isSuccess)(isTrue) &&
        assert(notFoundFilePath.status == Status.NotFound)(isTrue)
  ).provide(Client.default, Scope.default)
