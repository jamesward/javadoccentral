import zio.*
import zio.logging.{ConsoleLoggerConfig, LogFilter, LogFormat, consoleJsonLogger, consoleLogger}
import zio.logging.slf4j.bridge.Slf4jBridge

// Logging bootstrap layers, wired into `App` (prod) and `AppTest` (dev) via
// `override val bootstrap`.
//
// Both variants install the SLF4J bridge, so logs from Java libraries (Netty,
// the Redis client, jsoup, etc.) flow through ZIO's logger and pick up the same
// format as our own `ZIO.log*` calls — the bridge is now the sole slf4j
// provider (slf4j-simple is gone). The two differ in console format and level
// floor:
//   - `json` (prod): one JSON object per event. Log aggregators auto-detect
//     JSON and map `level`/`message`/`timestamp`, and a multi-line stacktrace
//     stays a SINGLE log event because its newlines are escaped inside the JSON
//     string value (Heroku's logplex splits on real newlines, so unescaped
//     multi-line output would fragment into N events). Floor is INFO so we don't
//     ship high-frequency DEBUG (request logging, MCP tools/list, MCP
//     notifications) to the log drain — that traffic was the bulk of the
//     ~130MB/day volume.
//   - `debug` (dev): human-readable logfmt console output at a DEBUG floor for
//     OUR ZIO loggers, so the debug-level request/tools-list/notification logs
//     are visible locally. The slf4j bridge stays at INFO so Netty & friends
//     don't flood the dev console with library DEBUG/TRACE; raise a specific
//     Java logger to DEBUG via `debugWith` if you need it.
object AppLogging:

  val json: ZLayer[Any, Nothing, Unit] =
    build(jsonConsole = true, LogLevel.Info, LogLevel.Info, Map.empty)

  val debug: ZLayer[Any, Nothing, Unit] =
    build(jsonConsole = false, LogLevel.Debug, LogLevel.Info, Map.empty)

  // Dev variant that also raises specific Java (slf4j) loggers to DEBUG.
  def debugWith(debugLoggers: Map[String, LogLevel]): ZLayer[Any, Nothing, Unit] =
    build(jsonConsole = false, LogLevel.Debug, LogLevel.Info, debugLoggers)

  private def build(
    jsonConsole: Boolean,
    consoleLevel: LogLevel,
    slf4jLevel: LogLevel,
    debugLoggers: Map[String, LogLevel],
  ): ZLayer[Any, Nothing, Unit] =
    val format  = if jsonConsole then jsonLogFormat else textLogFormat
    val cfg     = ConsoleLoggerConfig(format, LogFilter.LogLevelByNameConfig(consoleLevel, Map.empty))
    val console = if jsonConsole then consoleJsonLogger(cfg) else consoleLogger(cfg)
    val slf4j   = Slf4jBridge.init(LogFilter.logLevelByName[Any](slf4jLevel, debugLoggers.toSeq*))
    Runtime.removeDefaultLoggers >>> console >>> slf4j

  // Dev/text: the aligned default columns, plus any `ZIO.logAnnotate` key/values
  // appended as ` key=value` (logfmt style). `lazy` so it is initialized when
  // `build` first reads it, not by object-init order (the `json`/`debug` vals
  // above call `build`, which would otherwise see this as a null forward ref).
  private lazy val textLogFormat: LogFormat =
    LogFormat.default + LogFormat.space + LogFormat.annotations

  // Prod/JSON: `LogFormat.default` without the `timestamp.fixed(32)` padding
  // (which would leak trailing spaces into the JSON string value), plus
  // `annotations` so `ZIO.logAnnotate` key/values become first-class JSON fields
  // instead of being crammed into `message`. Otherwise identical (level, thread,
  // quoted message, and cause when non-empty). `lazy` for the same init-order
  // reason as `textLogFormat`.
  private lazy val jsonLogFormat: LogFormat =
    import LogFormat.*
    label("timestamp", timestamp) |-|
      label("level", level) |-|
      label("thread", fiberId) |-|
      label("message", quoted(line)) +
      (space + label("cause", cause)).filter(LogFilter.causeNonEmpty) +
      annotations
