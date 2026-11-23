import zio.*
import zio.http.*

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import scala.jdk.CollectionConverters.*

/**
 * Counts every outgoing HTTP request (Maven Central mirrors, inference, …) by
 * host and outcome, so the `cache stats` line shows how much traffic each
 * upstream gets: `2xx`, `304`, `404`, other `4xx`, `5xx`, transport errors
 * (`err`), and the `Content-Length` bytes of successful responses (`kb`).
 * The counts are since the previous `cache stats` line.
 */
object UpstreamStats:

  private val counts = ConcurrentHashMap[String, LongAdder]()

  private def add(key: String, n: Long): Unit =
    counts.computeIfAbsent(key, _ => LongAdder()).nn.add(n)

  private def outcome(status: Status): String =
    if status.isSuccess then "2xx"
    else if status.code == 304 || status.code == 404 then status.code.toString
    else if status.isClientError then "4xx"
    else if status.isServerError then "5xx"
    else status.code.toString

  private[UpstreamStats] def record(host: String, result: Exit[Any, Response]): Unit =
    result match
      case Exit.Success(response) =>
        add(s"$host ${outcome(response.status)}", 1)
        if response.status.isSuccess then
          response.header(Header.ContentLength).foreach(cl => add(s"$host bytes", cl.length))
      case Exit.Failure(_) =>
        add(s"$host err", 1)

  /** `host:2xx=12,304=3,kb=2048;host2:…`, sorted by host, and resets the counts. */
  def snapshotAndReset: UIO[String] =
    ZIO.succeed:
      val snapshot = counts.asScala.toList.map((k, v) => k -> v.sumThenReset()).filter(_._2 > 0)
      snapshot
        .groupBy(_._1.takeWhile(_ != ' '))
        .toList.sortBy(_._1)
        .map: (host, entries) =>
          val parts = entries.map((k, n) => k.drop(host.length + 1) -> n).sortBy(_._1).map:
            case ("bytes", n) => s"kb=${n / 1024}"
            case (kind, n)    => s"$kind=$n"
          s"$host:${parts.mkString(",")}"
        .mkString(";")

  val aspect: ZClientAspect[Nothing, Any, Nothing, Body, Nothing, Any, Nothing, Response] =
    new ZClientAspect[Nothing, Any, Nothing, Body, Nothing, Any, Nothing, Response]:
      override def apply[ReqEnv, Env >: Nothing <: Any, In >: Nothing <: Body, Err >: Nothing <: Any, Out >: Nothing <: Response](
        client: ZClient[Env, ReqEnv, In, Err, Out],
      ): ZClient[Env, ReqEnv, In, Err, Out] =
        val oldDriver = client.driver
        val newDriver = new ZClient.Driver[Env, ReqEnv, Err]:
          override def request(version: Version, method: Method, url: URL, headers: Headers, body: Body, sslConfig: Option[ClientSSLConfig], proxy: Option[Proxy])(using trace: Trace): ZIO[Env & ReqEnv, Err, Response] =
            val host = url.host.getOrElse("-")
            oldDriver.request(version, method, url, headers, body, sslConfig, proxy)
              .onExit(exit => ZIO.succeed(record(host, exit)))
          override def socket[Env1 <: Env](version: Version, url: URL, headers: Headers, app: WebSocketApp[Env1])(using trace: Trace, ev: ReqEnv =:= Scope): ZIO[Env1 & ReqEnv, Err, Response] =
            oldDriver.socket(version, url, headers, app)
        client.transform(client.bodyEncoder, client.bodyDecoder, newDriver)
