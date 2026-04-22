# AGENTS.md

> **Maintenance:** When you learn something new about this project's code style or preferences during a session, update this file before finishing. This keeps the guidance accurate for future sessions.

## Project Overview

JavaDoc Central — a Scala web app that serves javadocs from Maven Central artifacts. Deployed on Heroku (512MB dyno). Uses ZIO ecosystem throughout.

## Tech Stack

- Scala 3 (3.8.x) with `-language:strictEquality`
- ZIO 2 for effects, concurrency, and application wiring
- zio-http for HTTP server and client
- zio-direct (`defer`/`.run`) as the primary effect composition style
- zio-cache for in-memory caching
- zio-redis for persistent storage (symbol search index)
- zio-schema with protobuf codec for Redis serialization
- sbt build tool

## Coding Style

### Effect Composition

Prefer `zio-direct` (`defer`/`.run`) over for-comprehensions or flatMap chains:

```scala
defer:
  val blocker = ZIO.service[FetchBlocker].run.blocker
  val tmpDir = ZIO.service[TmpDir].run
  val javadocDir = File(tmpDir.dir, groupArtifactVersion.toString)
  // ...
```

Do NOT nest `defer` blocks. If a sub-effect is complex, extract it into its own method with its own `defer`:

```scala
// Good: separate methods, each with own defer
private def evictCrawlerCache(gav: GAV): ZIO[...] =
  defer:
    // ...

private def scheduleCrawlerEviction(gav: GAV): ZIO[...] =
  defer:
    val fiber = evictCrawlerCache(gav).delay(crawlerEvictDelay).forkDaemon.run
    // ...
```

### Scala 3 Syntax

- Use Scala 3 indentation-based syntax (no braces for control structures, class bodies, etc.)
- Use `given`/`using` for implicits
- Explicit `given CanEqual` instances are required due to `-language:strictEquality`
- Use `enum` for ADTs
- Use `.nn` for Java interop null assertions

### ZIO Patterns

- Services are provided via `ZLayer` and accessed with `ZIO.service` / `ZIO.serviceWith` / `ZIO.serviceWithZIO`
- Case classes wrapping ZIO types (e.g., `case class JavadocCache(cache: Cache[...])`) are used as service types
- `ConcurrentMap` with `Promise` for coordinating concurrent operations (use `putIfAbsent` for atomic coordination, `.ensuring` for cleanup)
- `HandlerAspect.interceptHandlerStateful` for middleware that passes state from incoming to outgoing handlers — avoid `FiberRef` for request-scoped state
- `ZIO.scoped` for resource management with `Client` and `Scope`
- `.orDie` for unrecoverable errors, `.ignoreLogged` for best-effort cleanup
- `forkDaemon` for background tasks that should outlive the current scope

### ZIO Idioms

- Prefer `.delay(duration)` over `ZIO.sleep(duration) *> effect`
- Prefer `ZIO.foreachDiscard(option)(...)` over `ZIO.whenCase(option) { case Some(x) => ... }` for running an effect on an `Option`
- Prefer `ZIO.whenCase` only when matching on non-Option types or multiple cases

### Error Handling

- Domain errors are modeled as case classes (not exceptions)
- Union types for error channels: `ZIO[R, ErrorA | ErrorB, A]`
- `.catchAll` with pattern matching on error types
- `.orDie` only for truly unexpected failures (e.g., network errors during jar download)

### Project Structure

- All main source files are in `src/main/scala/` (flat, no packages)
- Single `object` per file (e.g., `App`, `Extractor`, `SymbolSearch`, `BadActor`)
- `App.scala` contains routes, middleware, layer wiring, and `run`
- `Extractor.scala` contains Maven Central download/extraction logic
- Tests in `src/test/scala/`, using `ZIOSpecDefault`
- `AppTest.scala` is a runnable dev server (not a test suite) using embedded Redis

### Testing

- ZIO Test with `ZIOSpecDefault`
- Tests provide their own layers (no shared test fixtures)
- `EmbeddedRedis.layer` for tests needing Redis
- `MockInference.layer` as fallback when Heroku inference is unavailable
- `TestAspect.withLiveClock`, `TestAspect.withLiveRandom`, `TestAspect.withLiveSystem` as needed
- `TestAspect.sequential` for tests with shared state

### Heroku Constraints

- 512MB memory quota (includes JVM heap + page cache from disk files + swap)
- Ephemeral filesystem — extracted jars on disk contribute to page cache memory pressure
- Single dyno (`web.1`) — all traffic hits one instance
- 30-second request timeout (H12 error)
- `-XX:+ExitOnOutOfMemoryError` — OOM kills the process immediately
- When running the `heroku` command you must not specify an app name
