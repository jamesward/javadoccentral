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
- **Git source dependencies (fallback):** if publishing to Maven Central is
  blocked, `zio-mavencentral` and `zio-http-mcp` can be built from their GitHub
  repos at pinned commits: the commented-out `zioMavenCentralGit` /
  `zioHttpMcpGit` lines in `build.sbt` say how. sbt clones them on first load,
  so builds then need network access to GitHub and a bigger sbt heap
  (`.sbtopts` has `-J-Xmx2g`).
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
- For Scala 3 and ZIO code (writing or reviewing it), follow `zen-of-scala`
  (matches the Coding Style below).
- For design, domain modeling, effects and testing strategy, follow
  `zen-of-james` (illegal states unrepresentable, multiversal equality).

### Maintenance routine

`.factory/MAINTENANCE.md` is the zen-of-projects bootstrap. A scheduled Claude
Code routine runs it (the schedule is set outside the repo, in the routine);
heroku-ops also fires it for production incidents.

### Production logging

`AppLogging.json` (zio-logging `consoleJsonLogger` + the slf4j2 bridge) logs
one JSON object per line. Heroku `log-runtime-metrics` is on for `javadocs`
(heroku-ops reads its memory samples). Not yet aligned: entries don't carry
`request_id` from `X-Request-ID` (see the Skill's "Production Servers").

`cache stats` carries `upstream`: outgoing requests per host and outcome
(`2xx`, `304`, `404`, `4xx`, `5xx`, `err`) and `kb` of successful bodies since
the previous line (`UpstreamStats`, a `ZClientAspect` on `App.clientLayer`).
Use it to see how much traffic repo1 and the GCS mirror get.

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
- **Malformed coordinates were defects** (#270): Maven Central answers 400 (not
  404) for a NUL or invalid UTF-8 in a segment, or a segment over ~1,100
  characters, and zio-mavencentral turned that into an untyped error, so the
  API, MCP tools and browse routes answered 500 with an ERROR log. Since
  zio-mavencentral 0.18.0 a 400 / 414 is "not found", and `latestOrFail` types
  an unreachable upstream as `LatestUnavailable` (worded "retry" by
  `ErrorMessages.latest`; `/latest` and its JSON answer 503, the badge an
  uncached grey "unavailable"). `MalformedCoordinatesSpec` and
  `LatestUnavailableSpec` check that no surface answers 5xx or logs an ERROR.
  An MCP defect is logged once, by zio-http-mcp (`MCP.logMcp` only logs typed
  errors).
- **Overload looked like a Maven Central outage** (#271): when the jar-fetch
  bulkhead (8 in flight, 16 queued per cache) was full, a crawler burst got
  "Maven Central could not be reached", with nothing in the app log. That is
  now `Extractor.JarBusy`: a 503 + `Retry-After` saying javadocs.dev is busy
  (`Web.busy`), a busy `ErrorMessages.jar` text, one INFO `Jar fetch busy`
  line per answer (with `gav`, `reason` and the guard's `client_ip`), and
  `javadocFetchBusy` / `sourcesFetchBusy` counts in `cache stats`
  (`JarBusySpec`). `UpstreamUnavailableError` means a real network/5xx failure.

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
- Use `enum` for ADTs (except state machine states: see "State machines")
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

### State machines

A flow of two or more dependent effectful steps with branches or fallbacks is an
explicit state machine on `StateMachine`. **The primary design goal is to make
invalid states unrepresentable**: a state the flow can't be in, or a move it
can't make, must not compile. `StateMachine.scala`'s scaladoc is the full
reference (the goal and how each part serves it, the model, errors, resources,
constraints, prior art, future improvements). It is meant to become a library,
so keep it free of this app's types. In short:

- A machine is a value `StateMachine[R, E, In, Out]`: only its input and output
  appear in its type. Build it from `step(f)` (one transition, returning the
  union of the states it may lead to), `andThen` (output into input),
  `on[A, Rest](m)` (run `m` on the `A` branch, pass `Rest` through; `Rest` is
  spelled out, Scala can't subtract from a union), `loop[A, B]` (repeat while
  the output is an `A`), `contramap`/`map`, and `named`. Declare the machine's
  type: an unhandled state shows up in the output and fails to conform.
- Each machine lives in its own object. Public: the input type, the output
  types (with `private[Machine]` constructors) and `val machine`. Private:
  intermediate states, transitions, smart constructors and inner machines
  (`Resolution.tiers` is shared by `Resolution.machine` and `.symbols`). So
  callers can't start partway, run a step alone, or forge a result. Group
  states with a sealed trait, not an enum (enum cases can't have private
  constructors). Add new machines to `MachineStatesSpec` (compile-time checks).
- A state holds exactly its valid data: no `Option` or flag that only some paths
  fill in, no `Boolean` result (make it two states), non-empty collections
  (`::`) where empty is meaningless, and no "empty list means the next step"
  (make that step a state).
- Expected outcomes are states; recoverable failures are error edges (catch and
  return a state); anything else fails `run` with `StateMachine.Failed`, which
  keeps the failing state's type name and the named machines it ran in. Retry
  inside a transition with a `Schedule`.
- Each run owns a `Scope` shared by all its steps: don't wrap `run` in
  `ZIO.scoped`.
- No parallel combinators (decided Nov 2026; reasons in the scaladoc's
  "Parallelism"): parallelism stays in ZIO at the call site, e.g.
  `ZIO.foreachPar(items)(machine.run)`, which gives each item its own `Scope`.
  Background work (`forkDaemon`) stays outside machines. Don't `race`
  precedence-ordered fallbacks (it changes the answer, and AI search costs per
  call). AI search's per-suggestion validation and index loads are not a
  machine: one parallel filter, then a straight `latest → index` in a daemon.
- Draw the topology in the machine's scaladoc.
- Machines: `SymbolSearch.Indexing`, `.Resolution`, `.NameSearch`,
  `Extractor.Detection`, `Web.ArtifactPage`, `Web.VersionPage`. Single-step
  handlers (one fetch plus error mapping) stay plain ZIO.
- Test a machine through its public `machine`/`run` only. Produce hard-to-reach
  outcomes with real conditions (`IndexingSpec`: a held guard entry for `Busy`, a
  held row lock for `Superseded`), not by calling private steps.
- No error edge from index failures to AI search: each AI call costs money
  (revisit with a cheap model).
- Not an IndexedStateT-style monad (tried Nov 2026; linear, loses state on
  failure), not a `Start/Next/Done` dispatch table (Nov 2026; leaked
  intermediate state types into the machine's type), and not Mechanoid (for
  long-lived event-driven entities).
- `scala.compiletime.testing.typeChecks` for compile-time tests: zio-test's
  `typeCheck` trips `-Werror` ("Catching Throwable"). Use `???` for arguments
  (explicit nulls reject `null`, which makes a negative check pass vacuously).

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
- Tests that hit the live Maven Central get `@@ MavenCentralRetry.aspect` per
  test (up to 5 retries, 2 s exponential backoff, each retry logged at WARN);
  tests that don't touch the network don't, so they fail at once. Specs that
  call Maven Central provide `LoggingClient.layer` instead of `Client.default`:
  it logs every request at INFO with status, URL and the `server` / `cf-ray` /
  `cf-cache-status` / `age` headers. A failure is cached nowhere (`JarCache`,
  `LatestCache`), so a retry refetches. Every request for an uncached GAV costs
  `new-key` budget (30 per IP per 10 minutes), so `AppSpec`'s shared guard
  (`suiteGuard`) allows 10,000; otherwise retries during an outage would turn
  into 429s. The crawler-budget test builds its own default guard per attempt.
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
  `MaxDirectMemorySize=96m`) date from the old 512MB dyno, and only apply to
  a forked `sbt run`: the start script Heroku runs doesn't get `javaOptions`.
  Production (checked on a one-off dyno, Nov 23) runs with metaspace uncapped,
  `ReservedCodeCacheSize` 240MB, `MaxDirectMemorySize` = `-Xmx`, G1 with 8
  `ParallelGCThreads` (the dyno shows 8 CPUs) and no `G1PeriodicGCInterval`.
- RSS grows after every restart (Nov 13: 450MB at boot to ~900MB after 11 h,
  ~40MB/h) while the JVM's own heap + non-heap committed stays ~370MB, so the
  growth is native memory. Swap and R14 start once RSS passes ~870MB. Before the
  Nov 12 crawler fixes this took about 2 h and R14 ran most of the day.
- The buildpack sets `JAVA_OPTS=-XX:MaxRAM=1073741824 -Xmx671m` (the start
  script honors it) and `JAVA_TOOL_OPTIONS=-XX:+ExitOnOutOfMemoryError`.
  `MALLOC_ARENA_MAX=2` was set as a config var on Nov 13 (v249, issue #265);
  in the following week R14 happened once (Nov 19), so keep it.
  buildpack-scala defaults it too (jamesward/buildpack-scala#13).
- Heroku exposes config vars to the build, and sbt reads `JAVA_OPTS`, so the
  runtime `-Xmx400m` also capped the build's sbt heap: compiling zio-http-mcp and
  zio-mavencentral from source then ran out of memory. `.sbtopts` sets
  `-J-Xmx2g`, which takes precedence for sbt; the app's start script doesn't read it.
- Override JVM flags with the `JAVA_OPTS` config var (heroku/jvm appends it
  after its defaults; the last flag wins). Not `JAVA_TOOL_OPTIONS`: the JVM
  reads it before the command line, so `-Xmx671m` beats an `-Xmx` there.
- Since Nov 23 (v254, issue #265) `JAVA_OPTS=-Xmx400m -XX:G1PeriodicGCInterval=60000`:
  the heap used peaks at ~160MB, and a smaller heap also lowers the default
  direct-memory cap. Success is RSS under ~750MB with no swap over several days;
  if heap is too small, `-XX:+ExitOnOutOfMemoryError` restarts the dyno.
- Memory history (7 days, 10-minute or hourly buckets; `heroku logs` only covers
  minutes) comes from the Heroku Metrics API with `heroku auth:token`:
  `https://api.metrics.heroku.com/metrics/<app-id>/dyno/memory?start_time=…Z&end_time=…Z&step=10m&process_type=web`
  (also `dyno/errors` for R14/R15 counts, `dyno/load`, `router/status`).
- Ephemeral filesystem — extracted jars on disk contribute to page cache memory pressure
- Single dyno (`web.1`) — all traffic hits one instance
- 30-second request timeout (H12 error)
- H27 ("Client Request Interrupted") on a big file is a client hanging up
  mid-download; the request log still says `Http request served` 200.
- `Web.withFile` reads jar entries up to 1 MB whole and streams bigger ones in
  64 KB chunks (`Extractor.javadocEntry`, `Content-Length` from
  `JarHandle.entrySize`). Streaming every file made a first page load of
  `dev.zio:zio_3:2.1.27` (32 files) ~40% slower; a paused 20 MB download read
  whole held 20 MB of direct memory, streamed ~220 KB. `FileServingSpec`
  measures both, checks caching headers / 304 / HEAD for every file a real
  page loads, and checks that aborted downloads release the jar entry, file
  descriptors and direct memory. A zero-length `Body.fromStream` never
  completes, so don't stream empty entries.
- A jar cache miss waits at most `Extractor.missWait` (20 s) and then answers a
  503 + `Retry-After`. The fetch runs on a daemon fiber, so it keeps going when
  the request gives up and the retry is a cache hit. Before this, slow ~20 MB
  `vaadin-platform-javadoc` misses showed up as H12s (incident javadocs-29916434).
  Tests whose results depend on Maven Central's live "latest" compare against
  `Extractor.latest`, not a pinned version (jQuery 4.1.0 broke `AppSpec`).
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
- Indexing (`SymbolSearch.Indexing`), symbol search (`SymbolSearch.Resolution`)
  and artifact-name search (`SymbolSearch.NameSearch`) are `StateMachine`s (see
  "State machines" under Coding Style).
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


### Maven Central access

Balances latency, freshness, and load on repo1 (Nov 2026 design):

- **Mirrors** (zio-mavencentral `MavenCentralRepo`, 0.18.0): repo1 is
  authoritative; Google's GCS copy (`maven-central.storage-download…`) is the
  fallback. It lags new artifacts by up to a day (seen: 17 h) and has no HTML
  listings. Metadata asks repo1 first; jars and POMs ask GCS first (one GET,
  `JarCache.mavenCentralDownloader`), and repo1 when GCS doesn't have them.
  **Only repo1's 404 is "not found"**: a GCS 404 while repo1 can't be asked is
  the temporary `NotFoundOnFallback`. repo1 has a 20/s (burst 50) budget; over
  it, GCS answers what it can. Breakers trip on failure rate (50% over 30 s, ≥10
  calls), probe after 30 s, back off to 10 min; 5 s per-attempt timeout.
  `repo.maven.apache.org` is the same Cloudflare endpoint as repo1 (not used).
  A jar body that stalls for 30 s fails the download (upstream-unavailable),
  so a stuck transfer can't hold a fetch slot forever (#268's follow-up).
- **Postgres cache** (`MavenCache`, `V2__maven_cache.sql`): versions (1 h, or
  10 min when GCS served it; served stale and revalidated in the background with
  a 304), group listings (24 h), repo1's "not found" (1 h; 1 day for malformed
  coordinates), missing javadoc/sources jars (7 days for a published version).
  A version or artifact missing from a cached row refreshes it at once (at most
  every 5 / 10 min). Stale rows are served when Maven Central is down. Rows not
  read in 30 days are pruned daily. NUL can't be stored in `text`, so such keys
  skip the cache. `LatestCache` is a 1-minute memory tier over it.
- **What still needs repo1 alone**: group listings (GCS's bucket API is the slow
  fallback, 10 s cap; `dev.zio` takes ~25 s) and anything published in the last
  day.
- Tests: `MirrorSpec` (library, fake mirrors), `MavenCacheSpec` (fake mirrors +
  Postgres).

### Security probe filtering

All flood, crawler and probe handling goes through zio-http-guard's `Guard`,
configured in `JavadocsGuard` (detectors: `probe-shape`, `new-key`,
`client-hints`) and tuned with `GUARD_*` config vars. Don't add separate
limiter middleware (the old `BadActorMiddleware` and per-User-Agent
`CrawlerLimiter` were removed: the latter 429'd Googlebot on cached pages).
`/llms/{g}/{a}/{v}` (the markdown symbol listing) fetches a jar too, so
`JavadocsGuard.browsedGav` counts it like `/{g}/{a}/{v}`: before, Meta's crawler
used it for half of all jar downloads without touching its budget.
Each declared crawler gets one `new-key` budget (uncached GAVs per window)
across all its IPs (`ClientKey.crawlerOrIp`); a per-IP budget let Meta's
crawler, rotating through dozens of IPs, download a jar every ~10 seconds and
push the dyno into swap. A rate-limit verdict doesn't flag the client, so a
limited crawler still gets cached pages.
The guard runs before browse routes can interpret arbitrary paths as Maven
coordinates. Keep generic probe classification in `zio-http-guard`'s
`Probe.suspectPath` (used by `probe-shape`), not as a growing list of
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
