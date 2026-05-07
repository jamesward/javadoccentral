import com.jamesward.zio_mavencentral.MavenCentral
import zio.*
import zio.concurrent.ConcurrentMap
import zio.direct.*
import zio.durationInt
import zio.http.*
import zio.http.Header.Accept
import zio.http.codec.PathCodec
import zio.redis.Redis
import zio.stream.ZStream

import scala.annotation.unused

import SymbolSearch.HerokuInference

object Web:
  given CanEqual[Path, Path] = CanEqual.derived
  given CanEqual[Method, Method] = CanEqual.derived
  given CanEqual[MediaType, MediaType] = CanEqual.derived

  import Extractor.retryOnServerError

  private def acceptsMarkdown(request: Request): Boolean =
    request.header(Accept).exists: accept =>
      val types = accept.mimeTypes.map(_.mediaType)
      types.exists(_ == MediaType.text.markdown) && !types.exists(mt => mt == MediaType.text.html || mt == MediaType.any)

  private def markdownResponse(content: String): Response =
    Response.text(content).contentType(MediaType.text.markdown)

  def withGroupId(groupId: MavenCentral.GroupId, request: Request): Handler[Client, Nothing, (MavenCentral.GroupId, Request), Response] =
    Handler.fromZIO:
      ZIO.scoped:
        MavenCentral.searchArtifacts(groupId).retryOnServerError
    .flatMap: artifacts =>
      if acceptsMarkdown(request) then
        val md = s"# $groupId\n\n## Artifacts\n\n" +
          artifacts.value.map(a => s"- [$a](https://www.javadocs.dev/$groupId/$a)").mkString("\n")
        markdownResponse(md).toHandler
      else
        Response.html(UI.page("javadocs.dev", UI.needArtifactId(groupId, artifacts.value))).toHandler
    .catchAll: _ =>
      if acceptsMarkdown(request) then
        markdownResponse(s"# $groupId\n\nGroupId not found.").toHandler
      else
        Response.html(UI.page("javadocs.dev", UI.invalidGroupId(groupId)), Status.NotFound).toHandler

  def withArtifactId(groupId: MavenCentral.GroupId, artifactId: MavenCentral.ArtifactId, request: Request): Handler[Client, Nothing, (MavenCentral.GroupId, MavenCentral.ArtifactId, Request), Response] =
    Handler.fromZIO:
      ZIO.scoped:
        defer:
          val isArtifact = MavenCentral.isArtifact(groupId, artifactId).retryOnServerError.run
          ZIO.when(isArtifact):
            MavenCentral.searchVersions(groupId, artifactId).retryOnServerError
          .run
    .flatMap: maybeVersions =>
      maybeVersions.fold:
        // if not an artifact, try appending to the groupId if the combined groupId exists
        val combinedGroupId = MavenCentral.GroupId(groupId.toString + "." + artifactId.toString)
        Handler.fromZIO:
          ZIO.scoped:
            MavenCentral.searchArtifacts(combinedGroupId).retryOnServerError
        .flatMap: _ =>
          Response.redirect(URL(Path.root / combinedGroupId.toString)).toHandler
        .orElse:
          Handler.fromZIO:
            ZIO.scoped:
              MavenCentral.searchArtifacts(groupId).retryOnServerError
          .flatMap: artifactIds =>
            Response.html(UI.page("javadocs.dev", UI.needArtifactId(groupId, artifactIds.value, Some(artifactId))), Status.NotFound).toHandler
          .orElse:
            Response.html(UI.page("javadocs.dev", UI.invalidGroupArtifact(groupId, artifactId, groupIdValid = false)), Status.NotFound).toHandler
      .apply: versions =>
        if acceptsMarkdown(request) then
          val md = s"# $groupId:$artifactId\n\n## Versions\n\n" +
            versions.value.map(v => s"- [$v](https://www.javadocs.dev/$groupId/$artifactId/$v)").mkString("\n")
          markdownResponse(md).toHandler
        else
          Response.html(UI.page("javadocs.dev", UI.needVersion(groupId, artifactId, versions.value))).toHandler
    .catchAll: e =>
      // invalid groupId or artifactId
      Handler.fromZIO:
        ZIO.scoped:
          MavenCentral.searchArtifacts(groupId).retryOnServerError
      .flatMap: artifactIds =>
        Response.html(UI.page("javadocs.dev", UI.needArtifactId(groupId, artifactIds.value)), Status.NotFound).toHandler // todo: message
      .orElse:
        Response.html(UI.page("javadocs.dev", UI.invalidGroupArtifact(groupId, artifactId, groupIdValid = false)), Status.NotFound).toHandler

  given zio.json.JsonEncoder[Extractor.Content] = zio.json.DeriveJsonEncoder.gen[Extractor.Content]

  def withVersion(groupId: MavenCentral.GroupId, artifactId: MavenCentral.ArtifactId, version: MavenCentral.Version, request: Request):
      Handler[Extractor.LatestCache & Client & Extractor.JavadocCache & Redis & Extractor.TmpDir, Nothing, (MavenCentral.GroupId, MavenCentral.ArtifactId, MavenCentral.Version, Path, Request), Response] =
    val groupArtifactVersion = MavenCentral.GroupArtifactVersion(groupId, artifactId, version)

    if acceptsMarkdown(request) then
      Handler.fromResponseZIO:
        ZIO.scoped:
          defer:
            val contents = Extractor.javadocContents(groupArtifactVersion).run
            val md = s"# $groupId:$artifactId $version\n\n## Symbols\n\n" +
              contents.toSeq.sortBy(_.fqn).map: c =>
                s"- [${c.fqn}](https://www.javadocs.dev${groupArtifactVersion.toPath}/${c.link}) (${c.kind})"
              .mkString("\n")
            markdownResponse(md)
        .catchAll:
          case _: MavenCentral.NotFoundError =>
            ZIO.succeed:
              Response.notFound(groupArtifactVersion.toPath.toString)
    else if request.header(Accept).exists(_.mimeTypes.exists(_.mediaType.matches(MediaType.application.json))) then
      Handler.fromResponseZIO:
        ZIO.scoped:
          defer:
            val contents = Extractor.javadocContents(groupArtifactVersion).run
            import zio.json.*
            Response.json(contents.toJson)
        .catchAll:
          case _: MavenCentral.NotFoundError =>
            ZIO.succeed:
              Response.notFound(groupArtifactVersion.toPath.toString)
    else
      Handler.fromZIO:
        ZIO.scoped:
          if groupArtifactVersion.version == MavenCentral.Version.latest then
            ZIO.serviceWithZIO[Extractor.LatestCache](_.cache.get(groupArtifactVersion.noVersion)).map: latest =>
              groupArtifactVersion.noVersion / latest
            .orElseSucceed(groupArtifactVersion.noVersion.toPath).map: path =>
              Response.redirect(URL(path))
          else
            defer:
              val javadocDir = ZIO.serviceWithZIO[Extractor.JavadocCache](_.getDir(groupArtifactVersion)).run
              Extractor.javadocFile(groupArtifactVersion, javadocDir, "index.html").run
              Response.redirect(URL(groupArtifactVersion.toPath / "index.html"))
            .catchSome:
              case _: Extractor.JavadocFileNotFound =>
                defer:
                  val javadocDir = ZIO.serviceWithZIO[Extractor.JavadocCache](_.getDir(groupArtifactVersion)).run
                  val files = Extractor.fileList(javadocDir.toPath).map(_.toSeq.filter(_.endsWith(".html")).sorted).run
                  val versions = ZIO.scoped(MavenCentral.searchVersions(groupId, artifactId).retryOnServerError).map(_.value).orElseSucceed(Seq.empty).run
                  Response.html(UI.page("javadocs.dev", UI.javadocFileList(groupId, artifactId, version, versions, files)))
      .catchAll:
        case _: MavenCentral.NotFoundError | _: Extractor.JavadocFileNotFound =>
          Handler.fromZIO:
            ZIO.scoped:
              MavenCentral.searchVersions(groupId, artifactId).retryOnServerError
          .flatMap: versions =>
            Response.html(UI.page("javadocs.dev", UI.noJavadoc(groupId, artifactId, versions.value, version)), Status.NotFound).toHandler // todo: message
          .orElse:
            Response.redirect(URL(groupId / artifactId)).toHandler // todo: message

  // have to convert the failures to an exception for fromFileZIO
  case class JavadocException(error: MavenCentral.NotFoundError | Extractor.JavadocFileNotFound) extends Exception

  def withFile(groupId: MavenCentral.GroupId, artifactId: MavenCentral.ArtifactId, version: MavenCentral.Version, file: Path, request: Request):
      Handler[Client & Extractor.TmpDir & Redis & Extractor.JavadocCache & SymbolSearch.SymbolSearchGuard, Nothing, (MavenCentral.GroupId, MavenCentral.ArtifactId, MavenCentral.Version, Path, Request), Response] =
    val groupArtifactVersion = MavenCentral.GroupArtifactVersion(groupId, artifactId, version)

    // todo: reduce duplication on error handling
    if acceptsMarkdown(request) && file.toString.endsWith(".html") then
      Handler.fromResponseZIO:
        ZIO.scoped:
          defer:
            val content = Extractor.javadocSymbolContents(groupArtifactVersion, file.toString).run
            markdownResponse(content)
          .catchAll:
            case _: MavenCentral.NotFoundError =>
              ZIO.succeed:
                Response.redirect(URL(groupArtifactVersion.toPath))
            case _: Extractor.JavadocFileNotFound =>
              ZIO.succeed:
                Response.notFound((groupArtifactVersion.toPath ++ file).toString)
            case _: Extractor.JavadocContentError =>
              ZIO.succeed:
                Response.notFound((groupArtifactVersion.toPath ++ file).toString)
    else
      Handler.fromFileZIO:
        ZIO.scoped:
          defer:
            val javadocDir = ZIO.serviceWithZIO[Extractor.JavadocCache](_.getDir(groupArtifactVersion)).run
            ZIO.when(file.toString == "index.html")(SymbolSearch.indexJavadocContents(groupArtifactVersion)).run // update the cache
            Extractor.javadocFile(groupArtifactVersion, javadocDir, file.toString).run
          .catchAll(e => ZIO.fail(JavadocException(e)))
      .contramap[(MavenCentral.GroupId, MavenCentral.ArtifactId, MavenCentral.Version, Path, Request)](_._5) // not sure why fromFileZIO doesn't have an IN param anymore
      .catchAll:
        case JavadocException(e: MavenCentral.NotFoundError) =>
          Response.redirect(URL(groupArtifactVersion.toPath)).toHandler
        case JavadocException(e: Extractor.JavadocFileNotFound) =>
          Response.notFound((groupArtifactVersion.toPath ++ file).toString).toHandler

  private def groupIdExtractor(groupId: String): Either[String, MavenCentral.GroupId] =
    if groupId == "mcp" then
      Left("mcp")
    else
      MavenCentral.GroupId.unapply(groupId).toRight(groupId)

  private val groupId: PathCodec[MavenCentral.GroupId] =
    string("groupId").transformOrFailLeft(groupIdExtractor)(_.toString)

  private def artifactIdExtractor(artifactId: String) = MavenCentral.ArtifactId.unapply(artifactId).toRight(artifactId)
  private val artifactId: PathCodec[MavenCentral.ArtifactId] =
    string("artifactId").transformOrFailLeft(artifactIdExtractor)(_.toString)

  private def versionExtractor(version: String) = MavenCentral.Version.unapply(version).toRight(version)
  private val version: PathCodec[MavenCentral.Version] =
    string("version").transformOrFailLeft(versionExtractor)(_.toString)

  def withVersionAndFile(groupId: MavenCentral.GroupId, artifactId: MavenCentral.ArtifactId, version: MavenCentral.Version, path: Path, request: Request):
      Handler[BadActor.Store & Extractor.LatestCache & Extractor.JavadocCache & Extractor.SourcesCache & Extractor.TmpDir & Client & Redis & HerokuInference & SymbolSearch.SymbolSearchGuard, Nothing, (MavenCentral.GroupId, MavenCentral.ArtifactId, MavenCentral.Version, Path, Request), Response] = {
    if (path.isEmpty)
      withVersion(groupId, artifactId, version, request)
    else
      withFile(groupId, artifactId, version, path, request)
  }


  def index(request: Request): Handler[Redis & HerokuInference & Client & Extractor.JavadocCache & Extractor.SourcesCache & Extractor.TmpDir & SymbolSearch.SymbolSearchGuard, Nothing, Request, Response] =
    request.queryParameters.map.keys.filterNot(_ == "groupId").headOption.fold(Response.html(UI.page("javadocs.dev", UI.index)).toHandler):
      query =>
        // todo: rate limit
        Handler.fromZIO:
          ZIO.scoped:
            SymbolSearch.search(query).tapError:
              e =>
                ZIO.logErrorCause(s"SymbolSearch.search failed for query: $query", Cause.fail(e))
        .flatMap:
          results =>
            if acceptsMarkdown(request) then
              val md = s"# Search results for: $query\n\n" +
                (if results.isEmpty then "No results found - but maybe the library just hasn't been indexed yet?"
                else results.toSeq.map(ga => s"- [${ga.groupId}:${ga.artifactId}](https://www.javadocs.dev${ga.toPath})").mkString("\n"))
              markdownResponse(md).toHandler
            else
              Response.html(UI.page("javadocs.dev", UI.symbolSearchResults(query, results))).toHandler
        .catchAll:
          _ =>
            // todo: convey error
            if acceptsMarkdown(request) then
              markdownResponse(s"# Search results for: $query\n\nNo results found.").toHandler
            else
              Response.html(UI.page("javadocs.dev", UI.symbolSearchResults(query, Set.empty))).toHandler

  val robots = Response.text:
    """User-agent: *
      |Allow: /
      |Sitemap: https://www.javadocs.dev/sitemap.xml
      |""".stripMargin

  private val llmsHeader =
    """# javadocs.dev
      |
      |> Javadoc browser for Maven Central artifacts. All pages return markdown when requested with `Accept: text/markdown`.
      |
      |## Usage
      |
      |All javadoc pages support markdown responses via the `Accept: text/markdown` header.
      |
      |### URL patterns
      |
      |- `/{groupId}` — list artifacts for a group
      |- `/{groupId}/{artifactId}` — list versions for an artifact
      |- `/{groupId}/{artifactId}/{version}` — list symbols/classes in a versioned artifact
      |- `/{groupId}/{artifactId}/{version}/{path}` — view javadoc for a specific symbol
      |
      |Use `latest` as the version to resolve to the latest available version.
      |
      |### Example
      |
      |```
      |curl -H "Accept: text/markdown" https://www.javadocs.dev/dev.zio/zio-schema_3/1.8.3/zio/schema/Schema.html
      |```
      |
      |## Symbol search
      |
      |Search for symbols (classes, methods, etc.) across indexed artifacts. The query parameter key is the search term:
      |
      |```
      |curl -H "Accept: text/markdown" "https://www.javadocs.dev/?Schema"
      |```
      |
      |## MCP
      |
      |An MCP server (Streamable HTTP) is available at: `https://www.javadocs.dev/mcp`
      |""".stripMargin

  val llmsTxt: Handler[Redis, Nothing, Request, Response] =
    Handler.fromZIO:
      SymbolSearch.allGroupArtifacts.fold(
        error =>
          Response(status = Status.InternalServerError, body = Body.fromString(error.getMessage)),
        groupArtifacts =>
          val groups = groupArtifacts.map(_.groupId).toSeq.sortBy(_.toString)
          val md = llmsHeader + "\n## Groups\n\n" +
            groups.map(g => s"- [$g](https://www.javadocs.dev/llms/$g)").mkString("\n") + "\n"
          Response.text(md)
      )

  def llmsGroup(gId: MavenCentral.GroupId, @unused request: Request): Handler[Redis, Nothing, (MavenCentral.GroupId, Request), Response] =
    Handler.fromZIO:
      SymbolSearch.allGroupArtifacts.fold(
        error =>
          Response(status = Status.InternalServerError, body = Body.fromString(error.getMessage)),
        groupArtifacts =>
          val filtered = groupArtifacts.filter(_.groupId == gId)
          if filtered.isEmpty then
            Response(status = Status.NotFound, body = Body.fromString(s"Group $gId not found"))
          else
            val md = s"# $gId\n\n## Artifacts\n\n" +
              filtered.toSeq.sortBy(_.artifactId.toString).map: ga =>
                s"- [${ga.artifactId}](https://www.javadocs.dev/llms/$gId/${ga.artifactId})"
              .mkString("\n") + "\n"
            Response.text(md)
      )

  def llmsArtifact(gId: MavenCentral.GroupId, aId: MavenCentral.ArtifactId, @unused request: Request): Handler[Client, Nothing, (MavenCentral.GroupId, MavenCentral.ArtifactId, Request), Response] =
    Handler.fromZIO:
      ZIO.scoped:
        MavenCentral.searchVersions(gId, aId).retryOnServerError
    .flatMap: versions =>
      val md = s"# $gId:$aId\n\n## Versions\n\n" +
        versions.value.map(v => s"- [$v](https://www.javadocs.dev/llms/$gId/$aId/$v)").mkString("\n") + "\n"
      Response.text(md).toHandler
    .catchAll: _ =>
      Response(status = Status.NotFound, body = Body.fromString(s"Artifact $gId:$aId not found")).toHandler

  def llmsVersion(gId: MavenCentral.GroupId, aId: MavenCentral.ArtifactId, ver: MavenCentral.Version, @unused request: Request): Handler[Client & Extractor.JavadocCache & Extractor.TmpDir & Redis, Nothing, (MavenCentral.GroupId, MavenCentral.ArtifactId, MavenCentral.Version, Request), Response] =
    val groupArtifactVersion = MavenCentral.GroupArtifactVersion(gId, aId, ver)
    Handler.fromResponseZIO:
      ZIO.scoped:
        defer:
          val contents = Extractor.javadocContents(groupArtifactVersion).run
          val md = s"# $gId:$aId $ver\n\n## Symbols\n\n" +
            contents.toSeq.sortBy(_.fqn).map: c =>
              s"- [${c.fqn}](https://www.javadocs.dev${groupArtifactVersion.toPath}/${c.link}) (${c.kind})"
            .mkString("\n") + "\n"
          Response.text(md)
      .catchAll:
        case _: MavenCentral.NotFoundError =>
          ZIO.succeed:
            Response.notFound(groupArtifactVersion.toPath.toString)

  val sitemapIndex: Handler[Redis, Nothing, Request, Response] =
    Handler.fromZIO:
      SymbolSearch.allGroupArtifacts.fold(
        error =>
          Response(status = Status.InternalServerError, body = Body.fromString(error.getMessage)),
        groupArtifacts =>
          val groups = groupArtifacts.map(_.groupId).toSeq.sortBy(_.toString)
          val xml =
            <sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
              {
                groups.map: g =>
                  <sitemap>
                    <loc>{"https://www.javadocs.dev/sitemap/" + g}</loc>
                  </sitemap>
              }
            </sitemapindex>
          Response(
            status = Status.Ok,
            body = Body.fromString(xml.toString),
            headers = Headers(Header.ContentType(MediaType.text.xml))
          )
      )

  def sitemapGroup(gId: MavenCentral.GroupId, @unused request: Request): Handler[Redis, Nothing, (MavenCentral.GroupId, Request), Response] =
    Handler.fromZIO:
      SymbolSearch.allGroupArtifacts.fold(
        error =>
          Response(status = Status.InternalServerError, body = Body.fromString(error.getMessage)),
        groupArtifacts =>
          val filtered = groupArtifacts.filter(_.groupId == gId)
          if filtered.isEmpty then
            Response(status = Status.NotFound, body = Body.fromString("Group not found"))
          else
            val xml =
              <urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
                <url>
                  <loc>{"https://www.javadocs.dev/" + gId}</loc>
                </url>
                {
                  filtered.toSeq.sortBy(_.artifactId.toString).flatMap: ga =>
                    Seq(
                      <url>
                        <loc>{"https://www.javadocs.dev" + ga.toPath}</loc>
                      </url>,
                      <url>
                        <loc>{"https://www.javadocs.dev" + ga / MavenCentral.Version.latest}</loc>
                      </url>
                    )
                }
              </urlset>
            Response(
              status = Status.Ok,
              body = Body.fromString(xml.toString),
              headers = Headers(Header.ContentType(MediaType.text.xml))
            )
      )


  val app: Routes[BadActor.Store & Extractor.LatestCache & Extractor.JavadocCache & Extractor.SourcesCache & Extractor.TmpDir & Client & Redis & HerokuInference & SymbolSearch.SymbolSearchGuard, Response] =
    // `statelessRoutes` from zio-http-mcp only registers POST/GET/DELETE on /mcp.
    // A HEAD request would otherwise throw inside the route tree (turning into a
    // 500 via `.sandbox`). Register HEAD /mcp explicitly so it mirrors GET's 405.
    val mcpRoutes = MCP.mcpServer.statelessRoutes ++ Routes(
      Method.HEAD / "mcp" -> Handler.fromResponse(Response.status(Status.MethodNotAllowed))
    )

    // All read endpoints respond to both GET and HEAD. Per RFC 9110, HEAD must
    // behave exactly like GET but with no response body. Using `GET #| HEAD`
    // registers the handler under both methods in the route tree; the
    // `headStripBody` middleware empties the body (while preserving the
    // Content-Length header) for HEAD responses.
    val getOrHead: Method = Method.GET #| Method.HEAD

    val appRoutes = Routes[BadActor.Store & Extractor.LatestCache & Extractor.JavadocCache & Extractor.SourcesCache & Extractor.TmpDir & Client & Redis & HerokuInference & SymbolSearch.SymbolSearchGuard, Nothing](
      getOrHead / Root -> Handler.fromFunctionHandler[Request](index),
      getOrHead / "favicon.ico" -> Handler.fromResource("favicon.ico").orDie,
      getOrHead / "favicon.png" -> Handler.fromResource("favicon.png").orDie,
      getOrHead / "robots.txt" -> robots.toHandler,
      getOrHead / "llms.txt" -> llmsTxt,
      getOrHead / "llms" / groupId -> Handler.fromFunctionHandler[(MavenCentral.GroupId, Request)](llmsGroup),
      getOrHead / "llms" / groupId / artifactId -> Handler.fromFunctionHandler[(MavenCentral.GroupId, MavenCentral.ArtifactId, Request)](llmsArtifact),
      getOrHead / "llms" / groupId / artifactId / version -> Handler.fromFunctionHandler[(MavenCentral.GroupId, MavenCentral.ArtifactId, MavenCentral.Version, Request)](llmsVersion),
      getOrHead / "sitemap.xml" -> sitemapIndex,
      getOrHead / "sitemap" / groupId -> Handler.fromFunctionHandler[(MavenCentral.GroupId, Request)](sitemapGroup),
      getOrHead / ".well-known" / trailing -> Handler.notFound,
      getOrHead / groupId -> Handler.fromFunctionHandler[(MavenCentral.GroupId, Request)](withGroupId),
      getOrHead / groupId / artifactId -> Handler.fromFunctionHandler[(MavenCentral.GroupId, MavenCentral.ArtifactId, Request)](withArtifactId),
      getOrHead / groupId / artifactId / version / trailing -> Handler.fromFunctionHandler[(MavenCentral.GroupId, MavenCentral.ArtifactId, MavenCentral.Version, Path, Request)](withVersionAndFile),
    )

    mcpRoutes ++ appRoutes

  private val redirectQueryParams = HandlerAspect.intercept: (request, response) =>
    request.url.queryParam("q").map: q =>
      // manual redirect because the URL type really wants to encode the query param as ?query= but we just want ?query
      val param = URL.root.setQueryParams(q).toString.stripSuffix("=")
      Response(Status.MovedPermanently, Headers(Header.Location.name -> param))
    .orElse:
      request.url.queryParam("groupId").map: groupId =>
        Response.redirect(request.url.path(Path.root / groupId).setQueryParams(), true)
    .orElse:
      request.url.queryParam("artifactId").map: artifactId =>
        Response.redirect(request.url.path(request.path / artifactId).setQueryParams(), true)
    .orElse:
      request.url.queryParam("version").map: version =>
        Response.redirect(request.url.path(request.path / version).setQueryParams(), true)
    .getOrElse(
      if request.url.path.hasTrailingSlash && request.url.path != Path.root then
        Response.redirect(request.url.dropTrailingSlash)
      else
        response
    )

  private def getForwardedFor(request: Request): Option[BadActor.IP] =
    request.headers.get("X-Forwarded-For").flatMap(_.split(",").lastOption).orElse(request.remoteAddress.map(_.getHostAddress))

  val gibberishStream: ZStream[Any, Nothing, Byte] =
    ZStream
      .repeatZIOWithSchedule(Random.nextBytes(1024), Schedule.fixed(100.millis))
      .flattenChunks
      .interruptAfter(30.seconds)

  private def suspect(req: Request): Boolean =
    val s = req.path.toString
    s.endsWith(".php") || s.contains("wp-includes") || s.contains("wp-admin") || s.contains("wp-content")

  private val badActorMiddleware: HandlerAspect[BadActor.Store, Unit] =
    HandlerAspect.interceptIncomingHandler:
      Handler.fromFunctionZIO:
        request =>
          getForwardedFor(request).fold(ZIO.fail(Response.badRequest("Failed to get forwarded IP"))):
            ip =>
              defer:
                val isSuspect = suspect(request)
                val clock = ZIO.clock.run
                val now = clock.instant.run
                BadActor.checkReq(ip, now, isSuspect).run match
                  case BadActor.Status.Allowed =>
                    ZIO.succeed(request -> ()).run
                  case BadActor.Status.Banned =>
                    ZIO.logWarning(s"Bad actor detected: $ip").run
                    val gibberishResponse = Response(
                      status = Status.Ok, // so that the client will read the body
                      body = Body.fromStreamChunked(gibberishStream),
                      headers = Headers(Header.ContentType(MediaType.application.json))
                    )
                    ZIO.fail(gibberishResponse).run

  private val knownCrawlers = Set(
    "meta-externalagent",
    "semrushbot",
    "amazonbot",
    "petalbot",
    "dotbot",
    "bytespider",
    "gptbot",
    "claudebot",
    "bingbot",
    "googlebot",
    "yandexbot",
    "baiduspider",
  )

  private def matchedCrawler(request: Request): Option[String] =
    request.header(Header.UserAgent).flatMap: ua =>
      val lower = ua.renderedValue.toLowerCase
      knownCrawlers.find(lower.contains)

  private def isCrawler(request: Request): Boolean =
    matchedCrawler(request).isDefined

  // Per-crawler GAV limiter: each crawler UA may have at most one active GAV
  // at a time. Requests for the active GAV (or non-GAV paths) pass; other
  // GAVs get 429. This prevents crawlers from triggering many concurrent
  // javadoc extractions. The limiter is independent of the disk cache's
  // eviction lifecycle: a crawler's slot is released after
  // `crawlerGavHoldDuration` of inactivity for that crawler+GAV, at which
  // point the crawler is free to move on to a different GAV.
  private val crawlerGavHoldDuration: Duration = 10.minutes

  case class CrawlerGavSlot(gav: MavenCentral.GroupArtifactVersion, lastAccess: java.time.Instant)

  case class CrawlerGavLimiter(active: ConcurrentMap[String, CrawlerGavSlot]):
    // Attempts to claim the crawler's active-GAV slot for this request.
    // Returns true if the request is allowed, false if it should be 429'd.
    // Handles three cases:
    //   * no existing slot  -> claim it, allow
    //   * same GAV          -> refresh timestamp, allow
    //   * different GAV but the existing slot's last access is older than
    //     `holdDuration` -> steal the slot, allow
    //   * different GAV and still active -> deny
    def tryClaim(
      crawler: String,
      gav: MavenCentral.GroupArtifactVersion,
      holdDuration: Duration,
    ): ZIO[Any, Nothing, Boolean] =
      defer:
        val now = Clock.instant.run
        val fresh = CrawlerGavSlot(gav, now)
        active.putIfAbsent(crawler, fresh).run match
          case None => true
          case Some(existing) if existing.gav == gav =>
            // Refresh the timestamp. Last-writer-wins is fine for our
            // purposes (coarse "recent activity" signal).
            active.put(crawler, fresh).run
            true
          case Some(existing) =>
            val idle = java.time.Duration.between(existing.lastAccess, now)
            if idle.compareTo(holdDuration.asJava) >= 0 then
              active.put(crawler, fresh).run
              true
            else false

  val crawlerGavLimiterLayer: ZLayer[Any, Nothing, CrawlerGavLimiter] =
    ZLayer.fromZIO:
      ConcurrentMap.empty[String, CrawlerGavSlot].map(CrawlerGavLimiter(_))

  private val crawlerRateLimitMiddleware: HandlerAspect[CrawlerGavLimiter, Unit] =
    HandlerAspect.interceptIncomingHandler:
      Handler.fromFunctionZIO[Request]: request =>
        matchedCrawler(request) match
          case None => ZIO.succeed(request -> ())
          case Some(crawler) =>
            gavFromPath(request.path) match
              case None => ZIO.succeed(request -> ())
              case Some(gav) =>
                ZIO.serviceWithZIO[CrawlerGavLimiter]: limiter =>
                  limiter.tryClaim(crawler, gav, crawlerGavHoldDuration).flatMap:
                    case true => ZIO.succeed(request -> ())
                    case false =>
                      ZIO.fail(
                        Response
                          .status(Status.TooManyRequests)
                          .addHeader(Header.RetryAfter.ByDuration(1.minute))
                      )

  private def gavFromPath(path: Path): Option[MavenCentral.GroupArtifactVersion] =
    val segments = path.segments.toList
    if segments.length >= 3 then
      for
        g <- MavenCentral.GroupId.unapply(segments(0))
        a <- MavenCentral.ArtifactId.unapply(segments(1))
        v <- MavenCentral.Version.unapply(segments(2))
      yield MavenCentral.GroupArtifactVersion(g, a, v)
    else None

  // Javadoc content at /{groupId}/{artifactId}/{version}/... with a concrete version
  // is immutable — Maven Central artifacts at a released version don't change.
  // Mark these responses with a long max-age + immutable to eliminate repeat fetches.
  // Exclude "latest" since it redirects to a changing concrete version.
  private val immutableCacheControl = Header.CacheControl.Multiple(NonEmptyChunk(
    Header.CacheControl.Public,
    Header.CacheControl.MaxAge(365.days.toSeconds.toInt),
    Header.CacheControl.Immutable,
  ))

  // Pin Last-Modified to epoch so that values stay stable across dyno restarts
  // (extracted files on disk get new mtimes on each re-extract, which would
  // otherwise invalidate client caches unnecessarily).
  private val epochLastModified = Header.LastModified(
    java.time.ZonedDateTime.ofInstant(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC)
  )

  private def isImmutableAssetPath(request: Request): Boolean =
    val segs = request.path.segments.toList
    (request.method == Method.GET || request.method == Method.HEAD)
      && segs.length >= 4
      && segs(2) != "latest"
      && MavenCentral.Version.unapply(segs(2)).isDefined
      && MavenCentral.GroupId.unapply(segs(0)).isDefined
      && MavenCentral.ArtifactId.unapply(segs(1)).isDefined

  private def stableEtag(request: Request): Header.ETag =
    Header.ETag.Weak(request.path.toString)

  // Outgoing: on successful responses for immutable GAV assets, strip any
  // disk-derived Last-Modified/ETag (unstable across restarts), add stable
  // path-based ETag + epoch Last-Modified, and emit immutable cache-control.
  private val immutableAssetCacheHeaders: HandlerAspect[Any, Unit] =
    HandlerAspect.intercept: (request, response) =>
      if isImmutableAssetPath(request) && response.status.isSuccess then
        response
          .removeHeader(Header.ETag)
          .removeHeader(Header.LastModified)
          .addHeader(stableEtag(request))
          .addHeader(epochLastModified)
          .addHeader(immutableCacheControl)
      else
        response

  // Incoming: if the client sent If-None-Match or If-Modified-Since for an
  // immutable GAV path, short-circuit with 304 Not Modified BEFORE routing.
  // This skips the javadoc jar download entirely for cached-client requests.
  private val immutableAssetNotModified: HandlerAspect[Any, Unit] =
    HandlerAspect.interceptIncomingHandler:
      Handler.fromFunctionZIO[Request]: request =>
        val hasValidator =
          request.header(Header.IfNoneMatch).isDefined
            || request.header(Header.IfModifiedSince).isDefined
        if isImmutableAssetPath(request) && hasValidator then
          ZIO.fail(
            Response
              .status(Status.NotModified)
              .addHeader(stableEtag(request))
              .addHeader(epochLastModified)
              .addHeader(immutableCacheControl)
          )
        else
          ZIO.succeed((request, ()))

  // HEAD support: per RFC 9110, HEAD must behave exactly like GET but with no
  // response body. We enable `Server.Config.generateHeadRoutes = true` (see
  // App.scala) so that zio-http's routing layer dispatches HEAD requests to
  // matching GET handlers. The GET handler still produces a response with a
  // full body, so this middleware strips the body for HEAD responses while
  // preserving the Content-Length the client would have seen for GET. The
  // netty encoder preserves an explicit Content-Length header when the request
  // method is HEAD (see NettyResponseEncoder / zio-http issue #3080).
  private val headStripBody: HandlerAspect[Any, Unit] =
    HandlerAspect.interceptHandlerStateful(
      Handler.fromFunction[Request]: request =>
        (request.method == Method.HEAD, (request, ()))
    )(
      Handler.fromFunction[(Boolean, Response)]:
        case (true, response) =>
          val withContentLength =
            if response.headers.contains(Header.ContentLength.name) then response
            else
              response.body.knownContentLength match
                case Some(len) => response.addHeader(Header.ContentLength(len))
                case None => response
          withContentLength.copy(body = Body.empty)
        case (false, response) => response
    )

  val appWithMiddleware: Routes[CrawlerGavLimiter & BadActor.Store & Extractor.JavadocCache & Extractor.SourcesCache & Extractor.LatestCache & Extractor.TmpDir & Client & Redis & HerokuInference & SymbolSearch.SymbolSearchGuard, Response] =
    app @@ badActorMiddleware @@ crawlerRateLimitMiddleware @@ redirectQueryParams @@ immutableAssetNotModified @@ immutableAssetCacheHeaders @@ Middleware.requestLogging(loggedRequestHeaders = Set(Header.UserAgent)) @@ headStripBody
