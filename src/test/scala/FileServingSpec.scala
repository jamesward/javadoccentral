import com.jamesward.zio_mavencentral.JarCache
import com.jamesward.zio_mavencentral.MavenCentral
import com.jamesward.zio_mavencentral.MavenCentral.*
import nl.vroste.rezilience.Bulkhead
import zio.*
import zio.direct.*
import zio.http.{Client, Server}
import zio.test.*

import java.io.{ByteArrayOutputStream, File, FileOutputStream, InputStream}
import java.lang.management.{BufferPoolMXBean, ManagementFactory}
import java.net.{InetSocketAddress, Socket}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.zip.{ZipEntry, ZipOutputStream}
import scala.jdk.CollectionConverters.*

/**
 * Serving raw jar entries (`Web.withFile`) over a real server, from a jar
 * built on disk, plus a real javadoc site (`dev.zio:zio_3:2.1.27`, from Maven
 * Central).
 *
 * - Responses carry the entry's bytes, `Content-Length` and `Content-Type`,
 *   and a missing entry is a 404.
 * - Every file a real page loads gets the immutable caching headers, answers
 *   `If-None-Match` / `If-Modified-Since` with an empty 304, and answers HEAD
 *   with its length and no body.
 * - Measurements (logged, to compare serving strategies): time and heap
 *   allocated per request for a small page and a 20 MB file (the size of
 *   `aws-cdk-lib`'s `member-search-index.js`), one at a time and 8 at once; a
 *   first-visit page load (index.html, then its assets 6 at a time); and the
 *   live heap and direct memory each paused big download holds, which must not
 *   be the file's size.
 * - A client that hangs up mid-download (Heroku's H27 "Client Request
 *   Interrupted") must not leak: the jar entry's reference is released (so the
 *   sweeper can close its `ZipFile`), and file descriptors and direct memory
 *   go back to where they were.
 */
object FileServingSpec extends ZIOSpecDefault:

  private val gav          = GroupArtifactVersion(GroupId("com.example"), ArtifactId("files"), Version("1.0.0"))
  private val bigSize      = 20 * 1024 * 1024
  private val smallContent = ("<html><body>" + "small page " * 400 + "</body></html>").getBytes(StandardCharsets.UTF_8).nn
  private val smallChunk   = Chunk.fromArray(smallContent)
  private val warmTtl      = 1.second

  private def writeJar(target: File): Unit =
    val out = ZipOutputStream(FileOutputStream(target))
    try
      out.putNextEntry(ZipEntry("index.html"))
      out.write("<html></html>".getBytes)
      out.closeEntry()
      out.putNextEntry(ZipEntry("small.html"))
      out.write(smallContent)
      out.closeEntry()
      out.putNextEntry(ZipEntry("empty.txt"))
      out.closeEntry()
      out.putNextEntry(ZipEntry("big.js"))
      val block = Array.tabulate[Byte](64 * 1024)(i => ('a' + i % 26).toByte)
      (1 to bigSize / block.length).foreach(_ => out.write(block))
      out.closeEntry()
    finally out.close()

  private val cacheLayer: ZLayer[Any, Nothing, Extractor.JavadocCache] =
    ZLayer.scoped:
      defer:
        val dir = ZIO.attemptBlockingIO(Files.createTempDirectory("file-serving-spec").nn.toFile).orDie.run
        val fromMavenCentral = JarCache.httpDownloader(g => MavenCentral.javadocUri(g.groupId, g.artifactId, g.version))
        val download: (GroupArtifactVersion, File) => ZIO[Client & MavenCentralRepo, JarCache.DownloadError, JarMeta] =
          (g, target) =>
            if g.toString == gav.toString then ZIO.attemptBlockingIO(writeJar(target)).orDie.as(JarMeta(None, None))
            else fromMavenCentral(g, target)
        val cache  = JarCache.make(dir, download, warmIdleTtl = warmTtl, sweepInterval = 200.millis).run
        val misses = Bulkhead.make(maxInFlightCalls = 8, maxQueueing = 16).run
        Extractor.JavadocCache(cache, misses)

  private def requestBytes(file: String): Array[Byte] =
    s"GET /${gav.groupId}/${gav.artifactId}/${gav.version}/$file HTTP/1.1\r\nHost: localhost\r\nUser-Agent: FileServingSpec\r\nX-Forwarded-For: 10.9.8.7\r\nConnection: close\r\n\r\n"
      .getBytes(StandardCharsets.US_ASCII).nn

  private def connect(port: Int, file: String): Socket =
    val socket = Socket()
    socket.connect(InetSocketAddress("localhost", port))
    socket.getOutputStream.nn.write(requestBytes(file))
    socket

  final case class Fetched(status: Int, headers: Map[String, String], body: Chunk[Byte])

  private def parse(in: InputStream): Fetched =
    val all = ByteArrayOutputStream()
    in.transferTo(all)
    val bytes = all.toByteArray.nn
    val split = String(bytes, StandardCharsets.ISO_8859_1).indexOf("\r\n\r\n")
    val head  = String(bytes, 0, split, StandardCharsets.ISO_8859_1).linesIterator.toList
    val status  = head.headOption.flatMap(_.split(' ').lift(1)).flatMap(_.toIntOption).getOrElse(0)
    val headers = head.drop(1).flatMap: line =>
      line.indexOf(':') match
        case -1 => None
        case i  => Some(line.substring(0, i).nn.toLowerCase.nn -> line.substring(i + 1).nn.trim.nn)
    Fetched(status, headers.toMap, Chunk.fromArray(bytes).drop(split + 4))

  /** One request, with its whole response in memory. */
  private def fetch(port: Int, file: String): Task[Fetched] =
    ZIO.attemptBlocking:
      val socket = connect(port, file)
      try parse(socket.getInputStream.nn) finally socket.close()

  /** One request, counting the response's bytes without keeping them. */
  private def download(port: Int, file: String): Task[Long] =
    ZIO.attemptBlocking:
      val socket = connect(port, file)
      try socket.getInputStream.nn.transferTo(java.io.OutputStream.nullOutputStream())
      finally socket.close()

  /** Reads `readBytes` of the response, then resets the connection. */
  private def abort(port: Int, file: String, readBytes: Int): Task[Unit] =
    ZIO.attemptBlocking:
      val socket = Socket()
      try
        socket.setReceiveBufferSize(16 * 1024)
        socket.connect(InetSocketAddress("localhost", port))
        socket.getOutputStream.nn.write(requestBytes(file))
        val in   = socket.getInputStream.nn
        val buf  = new Array[Byte](8 * 1024)
        var read = 0
        while read < readBytes && { val n = in.read(buf); if n > 0 then read += n; n > 0 } do ()
        socket.setSoLinger(true, 0) // RST, like a client that went away
      finally socket.close()

  /** Reads `readFirst` bytes, signals `ready`, waits for `release`, then reads the rest. Returns the response's size. */
  private def heldDownload(port: Int, file: String, readFirst: Int, ready: Promise[Nothing, Unit], release: Promise[Nothing, Unit]): Task[Long] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking:
        val socket = Socket()
        socket.setReceiveBufferSize(16 * 1024)
        socket.connect(InetSocketAddress("localhost", port))
        socket.getOutputStream.nn.write(requestBytes(file))
        socket
    )(socket => ZIO.succeed(socket.close())): socket =>
      val in = socket.getInputStream.nn
      ZIO.attemptBlocking(in.readNBytes(readFirst).nn.length.toLong)
        .zipLeft(ready.succeed(()))
        .zipLeft(release.await)
        .flatMap(first => ZIO.attemptBlocking(first + in.transferTo(java.io.OutputStream.nullOutputStream())))

  /** Heap in use right after a full GC: roughly the live set. */
  private def liveHeap: UIO[Long] =
    ZIO.succeed:
      java.lang.System.gc()
      ManagementFactory.getMemoryMXBean.nn.getHeapMemoryUsage.nn.getUsed

  final case class Held(heapKbPerClient: Long, directKbPerClient: Long, openConnections: Long)

  /** Live heap and direct memory per client while `clients` downloads of `file` are paused mid-response. */
  private def heldPerClient(port: Int, file: String, clients: Int): Task[Held] =
    defer:
      val heapBefore   = liveHeap.run
      val directBefore = directBytes.run
      val release      = Promise.make[Nothing, Unit].run
      val readies      = ZIO.foreach(1 to clients)(_ => Promise.make[Nothing, Unit]).run
      val fibers       = ZIO.foreach(readies)(ready => heldDownload(port, file, 64 * 1024, ready, release).fork).run
      ZIO.foreachDiscard(readies)(_.await).run
      val heapHeld     = liveHeap.delay(500.millis).run
      val directHeld   = directBytes.run
      val connections  = openConnections(port).run
      release.succeed(()).run
      val sizes        = ZIO.foreach(fibers)(_.join).run
      val held = Held((heapHeld - heapBefore) / clients / 1024, (directHeld - directBefore) / clients / 1024, connections)
      ZIO.logInfo(s"measurement $file: $clients paused downloads hold $held, response sizes ${sizes.distinct}").run
      held

  // A real javadoc site (Scala 3 scaladoc): its index.html pulls in ~60 scripts, styles, fonts and images.
  private val zioGav = GroupArtifactVersion(GroupId("dev.zio"), ArtifactId("zio_3"), Version("2.1.27"))
  private val zioBase = s"/${zioGav.groupId}/${zioGav.artifactId}/${zioGav.version}/"

  private lazy val http = java.net.http.HttpClient.newBuilder().nn.version(java.net.http.HttpClient.Version.HTTP_1_1).nn.build().nn

  final case class Got(status: Int, headers: Map[String, String], bytes: Long, text: String)

  /** One request over a pooled keep-alive connection, like a browser. Keeps the body as text when `keepText`. */
  private def get(port: Int, path: String, method: String = "GET", headers: List[(String, String)] = Nil, keepText: Boolean = false): Task[Got] =
    ZIO.attemptBlocking:
      val builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(s"http://localhost:$port$path")).nn
        .method(method, java.net.http.HttpRequest.BodyPublishers.noBody()).nn
        .header("User-Agent", "FileServingSpec").nn
        .header("X-Forwarded-For", "10.9.8.7").nn
      headers.foreach((k, v) => builder.header(k, v))
      val response = http.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream()).nn
      val in = response.body().nn
      val (bytes, text) =
        if keepText then
          val all = in.readAllBytes().nn
          (all.length.toLong, String(all, StandardCharsets.UTF_8))
        else (in.transferTo(java.io.OutputStream.nullOutputStream()), "")
      val hs = response.headers().nn.map().nn.asScala.toMap.map((k, v) => k.toLowerCase.nn -> v.nn.get(0).nn)
      Got(response.statusCode(), hs, bytes, text)

  private val refPattern = """(?:src|href)\s*=\s*"([^"#?]+)"|url\(\s*['"]?([^'")#?]+)""".r

  /** Same-site, non-page resources a document references, as paths resolved against `docPath`. */
  private def assetRefs(docPath: String, text: String): List[String] =
    refPattern.findAllMatchIn(text).toList
      .flatMap(m => Option(m.group(1)).orElse(Option(m.group(2))))
      .filterNot(r => r.startsWith("http:") || r.startsWith("https:") || r.startsWith("//") || r.startsWith("data:") || r.startsWith("mailto:") || r.startsWith("javascript:"))
      .filterNot(_.endsWith(".html"))
      .map(r => java.net.URI.create(s"http://x$docPath").nn.resolve(r.replace(" ", "%20")).nn.getPath.nn)
      .filter(_.startsWith(zioBase))
      .distinct

  /** The assets `index.html` references, and those plus what its stylesheets reference (fonts, icons; a
   *  browser fetches only the ones the page uses). Files the jar doesn't contain are left out. */
  final case class PageAssets(direct: List[String], all: List[String])

  private def pageAssets(port: Int): Task[PageAssets] =
    defer:
      val index   = get(port, zioBase + "index.html", keepText = true).run
      val direct  = assetRefs(zioBase + "index.html", index.text)
      val nested  = ZIO.foreach(direct.filter(_.endsWith(".css")))(css => get(port, css, keepText = true).map(g => assetRefs(css, g.text))).run.flatten
      val all     = (direct ++ nested).distinct
      val present = ZIO.filter(all)(path => get(port, path, method = "HEAD").map(_.status == 200)).run.toSet
      ZIO.logInfo(s"page assets missing from the jar: ${all.filterNot(present).mkString(", ")}").run
      PageAssets(direct.filter(present), all.filter(present))

  /** A browser's first visit: index.html, then its assets 6 at a time (the HTTP/1.1 per-host limit). Returns the wall time in ms. */
  private def pageLoad(port: Int, assets: List[String]): Task[Double] =
    (get(port, zioBase + "index.html") *> ZIO.foreachParDiscard(assets)(get(port, _)).withParallelism(6))
      .timed.map(_._1.toNanos.toDouble / 1e6)

  /** Median of 20 first-visit page loads, after 5 to warm up. */
  private def measurePageLoad(port: Int, label: String, assets: List[String]): Task[Double] =
    defer:
      ZIO.foreachDiscard(1 to 5)(_ => pageLoad(port, assets)).run
      val loads  = ZIO.foreach(1 to 20)(_ => pageLoad(port, assets)).run.sorted
      val bytes  = ZIO.foreach(assets)(get(port, _)).run.map(_.bytes).sum
      val median = loads(loads.size / 2)
      ZIO.logInfo(f"measurement page load ($label): ${assets.size + 1} files, ${bytes / 1024} KB, median $median%.1f ms, p90 ${loads((loads.size * 9) / 10)}%.1f ms, min ${loads.head}%.1f ms").run
      median

  /** Open file descriptors on this spec's cached jars (a leaked `ZipFile` or entry stream keeps one). */
  private def jarFds: UIO[Long] =
    ZIO.attemptBlocking:
      Files.list(Paths.get("/proc/self/fd")).nn.iterator().nn.asScala.count: fd =>
        scala.util.Try(Files.readSymbolicLink(fd).nn.toString.nn.contains("file-serving-spec")).getOrElse(false)
      .toLong
    .orDie

  /** Server-side TCP connections on `port` that are still open (ESTABLISHED or CLOSE_WAIT). */
  private def openConnections(port: Int): UIO[Long] =
    ZIO.attemptBlocking:
      val hexPort = f"$port%04X"
      List("/proc/net/tcp", "/proc/net/tcp6").map(Paths.get(_)).filter(Files.exists(_)).map: table =>
        Files.readAllLines(table).nn.asScala.drop(1).count: line =>
          val fields = "\\S+".r.findAllIn(line).toList
          fields.lift(1).exists(_.endsWith(s":$hexPort")) && fields.lift(3).exists(st => st == "01" || st == "08")
      .sum.toLong
    .orDie

  private def directBytes: UIO[Long] =
    ZIO.succeed:
      ManagementFactory.getPlatformMXBeans(classOf[BufferPoolMXBean]).nn.asScala
        .filter(_.getName == "direct").map(_.getMemoryUsed).sum
        + math.max(0L, io.netty.util.internal.PlatformDependent.usedDirectMemory())

  /** Heap allocated by all threads since the JVM started (an approximation). */
  private def allocatedBytes: UIO[Long] =
    ZIO.succeed(ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean].getTotalThreadAllocatedBytes)

  private def settle: UIO[Unit] =
    ZIO.succeed(java.lang.System.gc()).delay(500.millis) *> ZIO.sleep(2.seconds)

  final case class Measurement(label: String, requests: Int, parallelism: Int, msPerRequest: Double, allocatedKbPerRequest: Long):
    override def toString =
      f"$label%-6s x$requests%-5d parallelism=$parallelism  $msPerRequest%8.3f ms/request  $allocatedKbPerRequest%7d KB allocated/request"

  private def measure(label: String, requests: Int, parallelism: Int)(one: Task[Long]): Task[Measurement] =
    defer:
      val before          = allocatedBytes.run
      val (took, _)       = ZIO.foreachParDiscard(1 to requests)(_ => one).withParallelism(parallelism).timed.run
      val after           = allocatedBytes.run
      val m = Measurement(label, requests, parallelism, took.toNanos.toDouble / 1e6 / requests, (after - before) / 1024 / requests)
      ZIO.logInfo(s"measurement $m").run
      m

  private def round(port: Int): Task[List[Measurement]] =
    defer:
      val smallSeq = measure("small", 500, 1)(download(port, "small.html")).run
      val smallPar = measure("small", 1000, 8)(download(port, "small.html")).run
      val bigSeq   = measure("big", 10, 1)(download(port, "big.js")).run
      val bigPar   = measure("big", 16, 8)(download(port, "big.js")).run
      List(smallSeq, smallPar, bigSeq, bigPar)

  def spec = suite("FileServing")(
    test("serves entries with their bytes, Content-Length and Content-Type, and 404s a missing one") {
      defer:
        val port    = Server.install(Web.appWithMiddleware).run
        val small   = fetch(port, "small.html").run
        val empty   = fetch(port, "empty.txt").run
        val missing = fetch(port, "missing.html").run
        val bigSize = download(port, "big.js").run
        assertTrue(
          small.status == 200,
          small.body == smallChunk,
          small.headers.get("content-length").contains(smallChunk.length.toString),
          small.headers.get("content-type").exists(_.startsWith("text/html")),
          empty.status == 200,
          empty.body.isEmpty,
          missing.status == 404,
          bigSize > FileServingSpec.bigSize.toLong,
        )
    },
    test("measurements: a big file doesn't cost its size in heap") {
      defer:
        val port = Server.install(Web.appWithMiddleware).run
        // Warm up: fetch the jar, JIT the paths, fill netty's pools.
        ZIO.foreachDiscard(1 to 200)(_ => download(port, "small.html")).run
        ZIO.foreachDiscard(1 to 5)(_ => download(port, "big.js")).run
        val rounds   = ZIO.foreach(1 to 3)(_ => round(port)).run.flatten
        ZIO.logInfo(rounds.mkString("measurements\n", "\n", "")).run
        val held     = heldPerClient(port, "big.js", 8).run
        assertTrue(held.heapKbPerClient < 4L * 1024, held.directKbPerClient < 4L * 1024, held.openConnections >= 8L)
    },
    test("a real javadoc page: every file it loads is cacheable, 304s when revalidated, and HEAD has its length") {
      defer:
        val port   = Server.install(Web.appWithMiddleware).run
        val assets = pageAssets(port).run
        val files  = (zioBase + "index.html") :: assets.all
        val checks = ZIO.foreach(files): path =>
          defer:
            val got  = get(port, path).run
            val etag = got.headers.getOrElse("etag", "")
            val revalidated = get(port, path, headers = List("If-None-Match" -> etag)).run
            val since       = get(port, path, headers = List("If-Modified-Since" -> "Thu, 01 Jan 1970 00:00:00 GMT")).run
            val head        = get(port, path, method = "HEAD").run
            val problems = List(
              Option.when(got.status != 200)(s"status ${got.status}"),
              Option.when(etag != s"W/\"$path\"")(s"etag '$etag'"),
              Option.when(!got.headers.get("last-modified").contains("Thu, 01 Jan 1970 00:00:00 GMT"))("last-modified"),
              Option.when(!got.headers.get("cache-control").exists(_.contains("immutable")))("cache-control"),
              Option.when(!got.headers.get("content-length").contains(got.bytes.toString))(s"content-length ${got.headers.get("content-length")} vs ${got.bytes}"),
              Option.when(revalidated.status != 304 || revalidated.bytes != 0L)(s"If-None-Match gave ${revalidated.status}"),
              Option.when(since.status != 304 || since.bytes != 0L)(s"If-Modified-Since gave ${since.status}"),
              Option.when(head.status != 200 || head.bytes != 0L || head.headers.get("content-length") != got.headers.get("content-length"))(s"HEAD ${head.status} ${head.headers.get("content-length")}"),
            ).flatten
            Option.when(problems.nonEmpty)(s"$path: ${problems.mkString(", ")}")
        .run.flatten
        ZIO.logInfo(s"measurement page assets: ${files.size} files").run
        assertTrue(files.size >= 40, checks.isEmpty)
    } @@ MavenCentralRetry.aspect,
    test("measurements: loading a real javadoc page") {
      defer:
        val port   = Server.install(Web.appWithMiddleware).run
        val assets = pageAssets(port).run
        val direct = measurePageLoad(port, "index.html's own references", assets.direct).run
        val all    = measurePageLoad(port, "with stylesheet references", assets.all).run
        assertTrue(direct > 0.0, all > 0.0)
    } @@ MavenCentralRetry.aspect,
    test("aborted downloads release the jar entry, file descriptors and direct memory") {
      defer:
        val port  = Server.install(Web.appWithMiddleware).run
        val cache = ZIO.service[Extractor.JavadocCache].run.cache

        // Warm up: fetch the jar, load classes, fill netty's pools.
        val fullSize = download(port, "big.js").run
        ZIO.foreachDiscard(1 to 3)(_ => abort(port, "big.js", 256 * 1024)).run
        settle.run
        val fdsBefore    = jarFds.run
        val connsBefore  = openConnections(port).run
        val directBefore = directBytes.run

        // Abort at different points: right after the headers, and partway
        // through the body. Some run concurrently.
        ZIO.foreachParDiscard(1 to 40)(i => abort(port, "big.js", if i % 2 == 0 then 1 else 1024 * 1024)).withParallelism(4).run
        settle.run
        val fdsAfter    = jarFds.run
        val connsAfter  = openConnections(port).run
        val directAfter = directBytes.run
        val warmAfter   = cache.warmCount.run

        // The server still serves the file in full afterwards.
        val afterSize = download(port, "big.js").run
        ZIO.logInfo(s"jar fds $fdsBefore -> $fdsAfter, open connections $connsBefore -> $connsAfter, direct ${directBefore / 1024 / 1024}MB -> ${directAfter / 1024 / 1024}MB, warm=$warmAfter").run
        assertTrue(
          fullSize > bigSize.toLong,
          afterSize == fullSize,
          warmAfter == 0,
          fdsAfter <= fdsBefore,
          connsAfter <= connsBefore,
          directAfter <= directBefore + 32L * 1024 * 1024,
        )
    },
    test("the warm check detects a reference that is still held") {
      defer:
        val cache  = ZIO.service[Extractor.JavadocCache].run
        val handle = cache.get(gav).run
        val held   = Promise.make[Nothing, Unit].run
        val reader = handle.streamEntry("big.js").take(1).tap(_ => held.await).runDrain.fork.run
        ZIO.sleep(warmTtl * 3).run
        val warm = cache.cache.warmCount.run
        held.succeed(()).run
        reader.join.run
        assertTrue(warm == 1)
    },
  ).provide(
    Server.defaultWith(_.onAnyOpenPort),
    LoggingClient.layer,
    MavenCentral.MavenCentralRepo.live,
    App.latestCacheLayer,
    cacheLayer,
    App.sourcesCacheLayer,
    PostgresContainer.freshDatabase,
    SymbolIndex.layer,
    MavenCache.layer,
    MockInference.layer,
    JavadocsGuard.layer,
    App.symbolSearchGuardLayer,
  ) @@ TestAspect.withLiveClock @@ TestAspect.withLiveSystem @@ TestAspect.sequential @@ TestAspect.timeout(5.minutes)
