# AGENTS.md

> **Maintenance:** When you learn something new about this project's code style or preferences during a session, update this file before finishing. This keeps the guidance accurate for future sessions.

## Project Overview

JavaDoc Central — a Scala web app that serves javadocs from Maven Central artifacts. Deployed on Heroku (512MB dyno). Uses ZIO ecosystem throughout.

## Build Instructions

Run sbt with `./sbt`

Use Java 25 when running sbt/tests. `build.sbt` requires Java 25+ and
`.sbtopts` includes JDK 25-only JVM flags.

Start the test server with: `./sbt ~reStartTest` (for auto-reloading) or `./sbt runTest` for non-auto-reloading

## MCP tool descriptions

The descriptions on the `McpTool` definitions in `MCP.scala` are not just
labels — they are the primary signal a remote agent uses to choose between
this MCP server and alternatives like "shell out and unzip the local jar
cache." When editing them, keep the agent-steering posture:

- Lead with **what task the tool is for**, not just what it returns.
  ("Use this when you need to read the actual source of a JVM library.")
- Mention that the tool works against the **live Maven Central catalog**
  with **no local install / build / repository checkout** required —
  this is the property that should make an agent prefer it over local
  jar inspection.
- **Cross-reference** related tools (which to call first, which `link`
  values feed into which call, what the fallback is when one returns
  `NotFoundError`).
- **Disambiguate** javadoc-vs-source tools: when an agent wants rendered
  API docs, it should reach for `get_javadoc_*`; when it wants the raw
  Scala/Java/Kotlin source, it should reach for `*_source_*`.
- Keep them concise but explicit. Verbose-but-clear is better than
  terse-and-ambiguous when the cost of an agent picking the wrong path
  is "shells out for several tool calls and burns context."

## Tech Stack

- Scala 3 (3.8.x) with `-language:strictEquality`
- ZIO 2 for effects, concurrency, and application wiring
- zio-http for HTTP server and client
- zio-direct (`defer`/`.run`) as the primary effect composition style
- zio-cache for in-memory caching (prefer `ScopedCache` when cached values own external resources — see "Caching disk-backed values" below)
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

### `Scope` usage conventions

Getting `Scope` right matters for the `ScopedCache`-backed javadoc/sources
caches — their per-entry finalizers delete the extracted directory, so the
cache reference must live until the last reader is done.

- **Layer-level Scope.** Use `ZLayer.scoped` (not `ZLayer.fromZIO` plus
  `Scope.default`) for layers that construct scoped resources. The layer
  owns a private `Scope` that is closed when the layer is finalized; the
  `Scope` does not leak into unrelated code paths. Examples:
  `javadocCacheLayer`, `sourcesCacheLayer` in `App.scala`.
- **Request-handler Scope.** zio-http's `ServerInboundHandler.writeResponse`
  creates one `Scope` per request and closes it **after** the response
  body has been fully written to netty (`scope.use(handler *> writeBody)`).
  Any `Scope` requirement at the `Handler` boundary is satisfied by that
  per-request scope. `Server.serve` enforces `HasNoScope[R]` on the
  outermost `Routes` environment, so Handlers must not expose `Scope` in
  their declared `R`; use `Handler.scoped[R]` to absorb `Scope` into the
  request scope when the inner ZIO legitimately requires one.
- **File-streaming handlers must not use a local `ZIO.scoped`.**
  `Handler.fromFileZIO` wraps the returned `File` in `Body.fromFile`,
  which opens the `FileInputStream` lazily when netty pulls the body. A
  local `ZIO.scoped` around `getDir` would close before netty opens the
  file, prematurely releasing the `ScopedCache` owner-count reference; a
  concurrent eviction could then delete the extracted directory
  mid-stream. Instead, keep `Scope` on the inner ZIO and wrap the
  resulting `Handler` in `Handler.scoped[R]` (see `Web.withFile`).
- **In-memory response handlers may use `ZIO.scoped`.** When the response
  body is fully materialized before the handler returns
  (e.g. `Extractor.javadocContents` → `markdownResponse`), a local
  `ZIO.scoped` is fine — the bytes are already in memory by the time the
  scope closes.
- **`forkDaemon` must not inherit a caller's `Scope`.** `forkDaemon` only
  changes the fiber supervision; the ZIO environment (including any
  `Scope`) is inherited as-is. If the parent's `Scope` closes while the
  daemon is still running, the daemon's subsequent `acquireRelease` /
  `addFinalizerExit` calls run the release immediately (the `Exited`
  branch in `zio.Scope.ReleaseMap.addDiscard`), leaving the daemon
  holding a reference that has already been released. Forked daemons
  that need a `Scope` must own it:

  ```scala
  // Correct: the daemon owns its Scope.
  def indexJavadocContents(gav): ZIO[…, Nothing, Unit] =
    val work = ZIO.scoped:
      defer:
        val (_, contents) = Extractor.javadocContents(gav).timed.run
        …
    work.forkDaemon.unit
  ```
- **Don't declare phantom `Scope`.** If a method's body doesn't actually
  use `Scope` (e.g. `Extractor.latest` which only calls
  `MavenCentral.latest`), don't put it in the return type. The
  requirement propagates through every caller and forces them to either
  provide `Scope.default` or wrap in `ZIO.scoped` for no reason.
- **Don't wrap non-scoped effects in `ZIO.scoped`.** Every `MavenCentral`
  public method returns `ZIO[Client, …]` (they `ZIO.scoped` internally
  when needed). Wrapping them in an outer `ZIO.scoped` is dead code.

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
- When tests are run, store the results in a file so you can reference the results without re-running the tests
- If something is broken and doesn't make sense, your first task is reproduce the issue in a test

### Heroku Constraints

- 512MB memory quota (includes JVM heap + page cache from disk files + swap)
- Ephemeral filesystem — extracted jars on disk contribute to page cache memory pressure
- Single dyno (`web.1`) — all traffic hits one instance
- 30-second request timeout (H12 error)
- `-XX:+ExitOnOutOfMemoryError` — OOM kills the process immediately
- When running the `heroku` command you must not specify an app name

### Caching disk-backed values

`JavadocCache` and `SourcesCache` wrap `zio.cache.ScopedCache`, not the plain
`Cache`. This matters because:

- `zio.cache.Cache` has **no eviction callback**. When an entry is removed
  (capacity overflow, TTL expiry, explicit invalidate), the in-memory map
  just drops the reference. If the cached value owns external state (like
  an extracted directory on disk), that state leaks.
- `zio.cache.ScopedCache` gives each entry its own `Scope`. Eviction closes
  the scope, which runs any finalizer registered inside the lookup. Per-
  entry reference counting inside `ScopedCache` ensures a concurrent reader
  is not cut off mid-read when eviction fires.

The pattern used here:

```scala
// Scoped lookup — returns ZIO[Scope, E, File] and registers a cleanup finalizer.
def javadoc(gav: GAV): ZIO[Client & TmpDir & Scope, NotFoundError, File] =
  defer:
    val dir = extractTo(tmpDir, gav).run
    ZIO.addFinalizer(deleteDirBlocking(dir).ignoreLogged).run
    dir

val cache = ScopedCache.makeWith(capacity, ScopedLookup(Extractor.javadoc)):
  case Exit.Success(_) => javadocCacheTtl
  case Exit.Failure(_) => Duration.Zero
```

Callers consume `getDir` inside `ZIO.scoped` so their owner reference is
released after use. `ScopedCache` also runs its own background TTL sweeper
(once per second) — no custom janitor is needed.

#### `Pending` dedup is not guaranteed — use `FetchBlocker`

`ScopedCache` tries to dedup concurrent lookups via its internal `Pending`
state, but that is **not pinned in the map**: `trackAccess` treats every
map entry the same, and if a `Pending` entry becomes the LRU under
capacity pressure it gets dropped (its `cleanMapValue` case is a no-op).
A subsequent `get` for the same key will then start a second concurrent
lookup — breaking the dedup contract.

For the javadoc/sources caches this matters because two concurrent
lookups that both call `MavenCentral.downloadAndExtractZip` into the
same `javadocDir` race inside `Files.copy(..., targetPath)` (no
`REPLACE_EXISTING`), surfacing as
`FileAlreadyExistsException` on files like `META-INF/MANIFEST.MF`.

The fix is `Extractor.FetchBlocker`: a pair of
`ConcurrentMap[GAV, Promise[NotFoundError, Unit]]` that sits in front of
the extraction. First fiber wins `putIfAbsent` and owns the extraction;
others await its `Promise`. Owner uses `.onExit` to complete the promise
and remove the map entry on success, failure, or interrupt. This pattern
is generally useful any time a cached value's construction has side
effects that can't safely run concurrently for the same key.
