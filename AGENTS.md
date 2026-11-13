# AGENTS.md

> **Maintenance:** When you learn something new about this project's code style or preferences during a session, update this file before finishing. This keeps the guidance accurate for future sessions.

## Project Overview

JavaDoc Central — a Scala web app that serves javadocs from Maven Central artifacts. Deployed on Heroku (one Standard-2X dyno, 1GB). Uses ZIO ecosystem throughout.

## Build Instructions

Follow the `zen-of-projects` Skill (extract with `./sbta extractSkillsJars`);
this file records only project-specific facts and exceptions.

Run sbt with the project launcher `./sbta` (`sbta.bat` on Windows).

This is a **server** project (Heroku). Build plugins: sbt-native-packager
(`JavaAppPackaging`, `stage`), sbt-reload (`runReload`), SkillsJars.

### Exceptions to zen-of-projects

- **Java 25, not 21** (CI uses Temurin 25 too; do not downgrade):
  `html-to-markdown` 3.x ships Java 25 bytecode, `build.sbt` requires Java 25+,
  and `.sbtopts` / `javaOptions` include JDK 25-only JVM flags
  (`--sun-misc-unsafe-memory-access=allow`).
- **Prerelease:** `zio-direct` has only `1.0.0-RC*` releases; keep the newest RC
  until a stable release exists.
- **Compatibility constraint:** keep the zio-schema / zio-json versions binary
  compatible with the pinned `zio-http-mcp` (see the comment in `build.sbt`).
- **Container image tracks production:** the Postgres image in
  `src/test/scala/PostgresContainer.scala` (`postgres:18.4`) mirrors the
  Heroku Postgres add-on on the `javadocs` app. Don't bump it in the maintenance
  routine; change it only when production's version changes (check with
  `heroku pg:info`).

- Dev server (Test scope, Postgres via Testcontainers, `MockInference` fallback;
  needs Docker): `./sbta ~Test/runReload` (auto-reload) or `./sbta Test/run`.
  `Test / mainClass` is `AppTest`; `Compile / mainClass` is `App`. Set `PORT`
  to override the default 8080 (after `./sbta shutdown`, since the daemon
  captures the environment). Add `-Dlocal` to use sibling checkouts of
  `zio-http-mcp`, `zio-mavencentral`, `zio-http-guard`.
- Prod-config server: `./sbta ~runReload` (needs `DATABASE_URL`, migrated with
  `./sbta "runMain FlywayMigrate"`, and
  `INFERENCE_URL` / `INFERENCE_KEY` / `INFERENCE_MODEL_ID`).
- Full validation (same as CI in `.github/workflows/test.yml`):

  ```bash
  ./sbta shutdown
  ./sbta extractSkillsJars
  ./sbta "Test / compile; testFull; stage"
  ./sbta shutdown
  ```

  Tests hit the live Maven Central and need Docker (Postgres Testcontainers).
  In a cloud session the Docker CLI is installed but the daemon isn't running:
  start it with `dockerd &` (as root); Testcontainers then works.
  CI and Heroku builds (`CI` / `STACK` env) additionally enable `-opt`.

### MCP

- MCP server `sbta` (this build's `./sbta --mcp`, a stdio server configured in
  `.mcp.json` and `.kiro/settings/mcp.json`, approved in
  `.claude/settings.json`). It starts sbt when it isn't running and talks to
  sbt's own server, so there is no port to check. Its tools are deferred in
  Claude Code: load them with ToolSearch (search `sbta`).
- Use it for ALL sbt work: `compile`, `test` (`full` for all), `run`, `check`
  after editing Scala sources (`"scope":"module"` after an API change),
  `glob-search` / `inspect` / `symbol-location` for Scala/classpath symbol
  questions instead of text search or jar inspection, and `sbt-task` for any
  other command (separate commands with `;`; `list-tasks` to discover
  tasks/settings). JavaDoc/ScalaDoc lookups are proxied through the same server.
- If it's unavailable, say so clearly and fall back to `./sbta <command>`.
  Logs: `project/target/sbta/mcp.log` and `project/target/sbta/boot.log`.
- Finish a session with `./sbta shutdown` so no sbt daemon is left running.

### Skills

Skills are extracted (git-ignored) into `.kiro/skills/` with
`./sbta extractSkillsJars`. Read the relevant `SKILL.md` files before working:

- `zen-of-projects` — project conventions and the maintenance routine.
- `zen-of-scala` — Scala 3 / ZIO idioms (matches the Coding Style below).
- `zen-of-james` — general design principles (illegal states
  unrepresentable, multiversal equality).

`.factory/MAINTENANCE.md` is the zen-of-projects bootstrap.

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

## MCP tool output schemas

A tool's return type becomes its MCP `outputSchema` + `structuredContent`,
derived from the return type's `zio-schema` `Schema` by `zio-http-mcp`'s
`McpOutput`:

- An **object-typed** schema (a case class, a `Map`) is advertised verbatim and
  returned as-is in `structuredContent`.
- Any **non-object** value — a `Set`/`List` (array) or a scalar like `Version` —
  is nested under a single `result` property. The MCP spec requires
  `outputSchema` to be `{"type":"object"}` and `structuredContent` to be a JSON
  object, so `search_artifacts` / `symbol_to_artifact` (`Set[GroupArtifact]`),
  `list_source_files` (`Set[SourceFile]`: `link` + `sizeBytes`),
  `list_javadoc_symbols` (`Set[Content]`) and `get_latest_version` (`Version`)
  all return `{"result": …}`.
- `JsonSchemaGen` auto-generates a `description` for collection schemas from the
  collection kind + element type (e.g. `Set[Foo]` → "a set of Foo") and emits a
  `description` from any `zio-schema` `@description` annotation on records/fields.
- Returning a bare `String` (or `ToolContent`) is the deliberate opt-out for
  unstructured/prose output (`get_javadoc_index`, `get_javadoc_symbol`,
  `get_source_file`): no `outputSchema`, plain text content.

This logic lives in `zio-http-mcp` (`McpOutput`, `JsonSchemaGen`), so shipping it
to production requires **publishing a new `zio-http-mcp` and bumping the version
pin in `build.sbt`** — production builds resolve the published artifact, not the
local `../zio-http-mcp` subproject (which is only used under `-Dlocal`).

## Lessons from agent evals

Evals of agents using this server (Spring AI agents on gpt-oss-120b, see the
`exquisite_evals` project) showed where the tools steered agents wrong:

- **`search_artifacts` with a version in the query** (`"jackson-databind 3.0.0"`)
  matched nothing, and agents retried variants dozens of times. Version-like
  tokens are now dropped when the full query matches nothing.
- **Lower-cased class names** (`"jevjudge"`) missed the case-sensitive symbol
  index and fell through to AI search. Symbol matching is now case-insensitive
  throughout (see "Symbol index (Postgres)").
- **`list_javadoc_symbols` on a big library** (jackson-databind lists hundreds of
  classes) blew agents' context budgets. It takes an optional `filter`.
- **"Latest" was the last entry of `maven-metadata.xml`**, which is publish order,
  not version order, and included pre-releases (Netty resolved to 5.0.0.Alpha2).
  zio-mavencentral now sorts with Maven's `ComparableVersion` and `latest` skips
  pre-releases by default. The web UI (`/latest`, badges, `LatestCache`) always
  uses that default; `get_latest_version` / `/api/latest-version` accept
  `includePreReleases` so an agent can opt in.
- Descriptions now say what agents got wrong: pre-releases need opting in; the
  same artifactId can live under several groupIds (Jackson 2 vs 3); a class name
  can resolve to several artifacts (a Java and a Scala `JevJudge`).
- **Error results were case-class `toString`s** (`GroupIdOrArtifactIdNotFoundError(g,a)`),
  which say what failed but not what to do. `MCP.ErrorMessages` words every tool
  error (coordinates, likely cause, the tool to try next) and is shared with the
  REST API; tools map their errors to `String` with it. Keep raw errors in logs.
- **Converted javadoc pages were mostly chrome**: nav bars, "JavaScript is disabled",
  and a `docs.oracle.com` link with a title on every type. `Extractor` strips the
  chrome, unwraps external/self links, and with `LinkBase.Jar` (MCP/API) rewrites
  the rest to jar paths an agent can pass back as `link`; the browse markdown path
  (`Web.withFile`) uses `LinkBase.Page` so links stay valid at the page URL.
- **Big source files overflowed agents' tool output** (magnum's 30 KB
  `DbCodec.scala` went over a 30,000-character limit). `list_source_files` gives
  each file's `sizeBytes` (from the jar's central directory, via
  zio-mavencentral's `JarHandle.filterEntrySizes`), and `get_source_file` takes
  `grep` (case-insensitive regex, literal if invalid) and `startLine`/`endLine`,
  returning numbered lines (`Extractor.selectLines`).
- **`index.html` is often a shell**: a frameset (Java 8 and earlier) or a `Refresh`
  redirect (single-module modern javadoc). `Extractor.indexTarget` follows it.
- **Network failures were defects**: `JarCache.httpDownloader` died on any
  upstream error, so agents saw netty's raw `null: repo1.maven.org/…:443`. It
  now fails with `JarCache.UpstreamUnavailableError` (worded by
  `ErrorMessages` as "temporary, retry"; browse routes answer 503 +
  `Retry-After`), and zio-http-mcp answers real defects with a generic message
  while logging the cause.

## MCP client IP logging

zio-http-guard's `Guard` (applied outermost in `Web.appWithMiddleware`)
annotates every log line of a request with `client_ip`, including the lines
zio-http-mcp emits (`MCP initialize`, `server/discover`, `tools/call`, …), so
per-client protocol usage reports group by `client_ip`. (Before Nov 2026 the
MCP lines also carried a `clientIp` field from a now-removed wrapper.) The IP
is the `GUARD_TRUSTED_HOPS`-th value from the end of `X-Forwarded-For`
(default 1: the value the Heroku router appended; earlier values are
client-supplied and spoofable). With `GUARD_MODE=off` the guard, and so
`client_ip`, is skipped.

## MCP spec conformance

Check protocol conformance with MCPJam's CLI, against production or a local dev
server (`./sbta Test/bgRun`, then `bgStop`; it runs locally, so no tunnel is
needed):

```bash
npx -y @mcpjam/cli@latest --no-telemetry protocol conformance \
  --url http://localhost:8080/mcp --protocol-version 2026-07-28 \
  --fixtures-file fixtures.json   # {"toolCalls":[{"toolName":"get_latest_version","arguments":{...}}]}
```

Run it for `2026-07-28` and `2025-11-25`. Fixtures name safe tool calls, so the
output-schema check has results to validate. Known, accepted leftovers:

- "could not run" for completion, `Mcp-Name`, input-required and log probes
  (there are no prompts/resources, and no tool requests input or logs);
- the `logging` capability advisory: zio-http-mcp does emit per-request
  `notifications/message` when a request opts in, and the spec requires
  servers that emit them to declare `logging`;
- the missing `MCP-Protocol-Version` advisory (informational): tolerating it is
  allowed because the server still serves 2025-03-26 clients;
- `conformance run --suite apps` fails `ui-tools-present`: there are no MCP Apps.

Every tool sets an annotation `title` (MCPJam flags tools without one).
Required routing headers (`Mcp-Method`, `Mcp-Name`) are enforced by
zio-http-mcp, not here.

## REST API and OpenAPI

`Api.scala` exposes read-only `GET` + query-parameter endpoints under `/api`
that mirror the eight MCP tools. Define these with zio-http `Endpoint`, not
hand-written `Route` JSON responses: the Endpoint input/output codecs reuse the
same zio-schema `Schema`s as MCP, and `OpenAPIGen.fromEndpoints` generates
`/openapi.json`; SwaggerUI is served at `/api/doc`.

Keep each operation description in `MCP.Descriptions` and reuse it from both the
`McpTool.description` and Endpoint `Doc`, so the MCP and OpenAPI surfaces cannot
drift. The RFC 9727 API catalog at `/.well-known/api-catalog` is an RFC 9264
linkset whose `service-desc` points to `/openapi.json`.

Browse-form redirects (`?groupId`, `?artifactId`, `?version`, `?q`) and
trailing-slash normalization belong in the relevant browse handlers, **not in a
global middleware**. Global query redirect middleware can hijack the REST API,
which legitimately uses the same parameter names.

## `GET /mcp` browser landing page

`zio-http-mcp`'s `statelessRoutes` registers a `GET /mcp` that unconditionally
returns 405 (the stateless transport has no server→client SSE stream). Humans
who paste `/mcp` into a browser would just see that 405. Instead, in
`Web.scala`'s `app`, we **filter out that exact `GET /mcp`** (matched by
`route.routePattern.render == (Method.GET / "mcp").render`, which leaves the
`GET /mcp/{trailing}` catch-all and POST/DELETE/PRM routes untouched) and
register our own `GET /mcp`:

- **Browser** (`Accept` contains `text/html`) → an HTML setup page
  (`UI.mcpSetup`) with per-agent MCP config snippets.
- **MCP client** (`Accept: text/event-stream`) or **curl** (`*/*`, or no
  `Accept`) → still 405, preserving protocol compatibility.

`Web.prefersHtml` is the discriminator — `text/html` in `Accept` is more
reliable than User-Agent sniffing. Filtering the library route (rather than
letting `Routes.++` pick the last of two conflicting `GET /mcp` routes) avoids
the startup "Duplicate routes detected" warning. `HEAD /mcp` stays an explicit
405. When adding a new agent to `UI.mcpSetup`, remember MCP client config
formats drift; keep the endpoint + Streamable HTTP transport as the stable fact
and note that agent docs are authoritative.

## Tech Stack

- Scala 3 (3.10.x) with `-language:strictEquality`, `-deprecation`, `-Werror`
- ZIO 2 for effects, concurrency, and application wiring
- zio-http for HTTP server and client
- zio-direct (`defer`/`.run`) as the primary effect composition style
- zio-cache for in-memory caching (prefer `ScopedCache` when cached values own external resources — see "Caching disk-backed values" below)
- Postgres (Heroku Postgres, `pg_trgm`) for the symbol search index, via
  magnum / zio-magnum on a HikariCP pool; schema managed by Flyway
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
- zio-direct rejects mutable collections inside `defer`, including the `Array`
  from `String.split`. Do that parsing in a plain (non-`defer`) method.

### `AllValuesAreNullable` gotcha (Extractor.scala)

`Extractor.scala` imports `zio.prelude.data.Optional.AllValuesAreNullable`, an
implicit conversion from any value to `Optional`. With it in scope, collection
calls on an `Array` can silently resolve through `Optional` instead of
`ArrayOps`: `text.split("\\s+").toList` produced `List(theArray)` — it compiled
only because of an `.nn`, and `filterContents` then matched nothing. In that
file, avoid `split(...).toList`/`List.from(array)`; use an `Iterator` (e.g.
`"\\S+".r.findAllIn(text).toList`) and cover such helpers with a pure test
(`SearchQuerySpec`). The same
conversion makes `String.nonEmpty` always true (a non-null `Optional`); use
`!s.isEmpty`.

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
- `AppTest.scala` is a runnable dev server (not a test suite) on a fresh
  Postgres database from `PostgresContainer.freshDatabase`

### Testing

- ZIO Test with `ZIOSpecDefault`
- Tests provide their own layers (no shared test fixtures)
- `PostgresContainer.freshDatabase` for tests needing the symbol index. One
  Testcontainers Postgres per test JVM; Flyway migrates a template database
  once and each spec (each test, with `provide`) gets its own clone, dropped
  afterwards. Container starts and `CREATE DATABASE` share a lock, since
  rootless Docker races when two ephemeral ports are published at once.
  Requires Docker (available on CI and locally). Forked test JVMs raise
  `-XX:MaxMetaspaceSize` to 512m (`Test / javaOptions` in `build.sbt`) because
  Testcontainers + docker-java load far more classes than the production 96m
  cap allows.
- `JarCache.get` wraps the shared cache entry in a new `JarHandle` on every
  call, so compare handles by `meta` (one instance per entry), not with `eq`.
- `MockInference.layer` as fallback when Heroku inference is unavailable
- `TestAspect.withLiveClock`, `TestAspect.withLiveRandom`, `TestAspect.withLiveSystem` as needed
- `TestAspect.sequential` for tests with shared state
- When tests are run, store the results in a file so you can reference the results without re-running the tests
- If something is broken and doesn't make sense, your first task is reproduce the issue in a test

### Heroku Constraints

- 1GB memory quota on the Standard-2X `web` dyno (includes JVM heap +
  off-heap RSS + page cache from disk files + swap). In Nov 2026 it sat at
  ~1,000MB: ~800MB RSS (only ~310MB of it heap + non-heap) and ~200MB page
  cache. The JVM caps in `build.sbt` (`MaxMetaspaceSize=96m`,
  `MaxDirectMemorySize=96m`) date from the old 512MB dyno.
- Ephemeral filesystem — extracted jars on disk contribute to page cache memory pressure
- Single dyno (`web.1`) — all traffic hits one instance
- 30-second request timeout (H12 error)
- `-XX:+ExitOnOutOfMemoryError` — OOM kills the process immediately
- When running the `heroku` command you must not specify an app name
- Release phase: the Procfile's `release: bin/flyway-migrate` applies Flyway
  migrations before a new release serves traffic; a failure aborts the release.
  The old release keeps running while it migrates, so a migration must work
  with the code already deployed (add, then use; never drop what live code reads).

### Symbol index (Postgres)

`SymbolIndex.scala` holds the queries, `SymbolSearch.scala` the orchestration,
`Db.scala` the pool, and `src/main/resources/db/migration` the schema.

- Schema: `artifact` (one row per groupId:artifactId, with `indexed_version`)
  and `symbol` (`artifact_id`, `fqn`, generated lower-case simple `name`).
  Only the newest indexed version's symbols are kept; an older version never
  overwrites a newer one (`SymbolIndex.supersedes`, Maven version order). A
  re-index replaces an artifact's symbols in one transaction.
- Add schema changes as a new `V<n>__name.sql`. Never edit an applied one;
  Flyway checksums them and the release would fail.
- `symbol_to_artifact` tries, in order: artifact-name match
  (`search_artifacts`), full name containing the query (trigram `ILIKE`),
  similar simple name (`pg_trgm` `%`, only artifacts within 0.1 of the best
  score, top 20; skipped for dotted queries, which go to AI search instead),
  then AI search. Don't stop at
  exact-name matches: an exact match is always a containing match, so an
  exact-first tier hides other artifacts (production regression: `jev`
  returned 2 artifacts instead of 9). Queries under 3 characters (the trigram
  minimum) only match exactly. Each tier logs `"Symbol search"` with `match`
  and `durationMs`.
- `SymbolIndex.indexable` drops doc pages that aren't symbols (`index-all`,
  `.static-page`). LIKE wildcards in queries are escaped (`escapeLike`).
- GIN trigram indexes bloat ~4× under per-artifact rewrites; compact with
  `reindex index concurrently symbol_fqn_trgm_idx` (and `symbol_name_trgm_idx`)
  via `heroku pg:psql`.
- The pool starts even if Postgres is down (`initializationFailTimeout = -1`):
  browsing javadocs doesn't depend on the index, and search fails (logged)
  until the database is back.
- `Db.connectionTimeout` (5 s) is how long a query waits for one of the 5 pool
  connections. Keep it well under Heroku's 30 s router timeout: at Hikari's
  default (30 s) a busy or unreachable database turned `/sitemap/...` requests
  into H12s. DB-backed browse routes answer 503 + `Retry-After` when the index
  is unavailable, and the sitemaps query by group (`allGroupIds`,
  `groupArtifactsIn`), never the whole `artifact` table per request.


### Security probe filtering

All flood, crawler and probe handling goes through zio-http-guard's `Guard`,
configured in `JavadocsGuard` (detectors: `probe-shape`, `new-key`,
`client-hints`) and tuned with `GUARD_*` config vars. Don't add separate
limiter middleware (the old `BadActorMiddleware` and per-User-Agent
`CrawlerLimiter` were removed: the latter 429'd Googlebot on cached pages).
Each declared crawler gets one `new-key` budget (uncached GAVs per window)
across all its IPs (`ClientKey.crawlerOrIp`); a per-IP budget let Meta's
crawler, rotating through dozens of IPs, download a jar every ~10 seconds and
push the dyno into swap. A rate-limit verdict doesn't flag the client, so a
limited crawler still gets cached pages.
The guard runs before browse routes can interpret arbitrary paths as Maven
coordinates. Keep generic probe classification in `zio-http-guard`'s
`defaultSuspect` (used by `probe-shape`), not as a growing list of
javadoccentral routes:

- Match stable path shapes/segments (hidden files such as `.env*` anywhere in
  the path, dynamic scripts, CMS/debug probes), rather than only exact paths.
- A probe gets a cheap 404 before the protected handler runs, and flags the
  client: its later requests get the guard's block response (a tiny `200`)
  for `GUARD_FLAG_FOR` (10m).
- Include negative matcher tests for valid Maven coordinates and nested javadoc
  resources. In particular, preserve root `/.well-known` endpoints and avoid
  treating group IDs such as `com.php` or classes such as `Environment.html`,
  `Dockerfile.html`, and `Actuator.html` as probes.
- Validate guard changes here with `./sbta -Dlocal ...`. Production still uses
  the published `zio-http-guard` version pinned in `build.sbt`, so deployment
  requires publishing the library and bumping that pin.

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
