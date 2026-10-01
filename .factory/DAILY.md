# Daily Routine

If there are other open PRs for this work, update that PR instead of creating a new one.

0. Load the project's MCP tools before anything else. `AGENTS.md` names the sbt-mcp server
   (`sbt-mcp-<project>`). In Claude Code its tools are deferred, so load them with ToolSearch
   (search for the server name). They include `sbt-task` for sbt commands and the javadocs.dev
   tools such as `get_latest_version`. Use them for the rest of the run, and fall back to `./sbt`
   and `curl` only when they are unavailable. Say which one you used.
1. Update the Skills dependency. It is pinned in `build.sbt` as
   `"com.jamesward" % "skills" % "<version>" % Skills`. List every pin (some projects also pin it
   in an `example/` build) with:

   ```bash
   grep -rn '"com.jamesward" % "skills"' --include='*.sbt' . | grep -v -e /target/ -e /src/sbt-test/
   ```

   Get the latest release with `get_latest_version` (group `com.jamesward`, artifact `skills`). Without
   MCP, ask Maven Central itself, not a mirror (mirrors lag new releases):

   ```bash
   curl -fsS --retry 5 --retry-delay 10 --retry-all-errors https://repo.maven.apache.org/maven2/com/jamesward/skills/maven-metadata.xml | sed -n 's:.*<release>\(.*\)</release>.*:\1:p'
   ```

   Maven Central can rate-limit cloud sessions (HTTP 429); the retries cover that. Set every pin
   to the version it prints.
2. Run `reload; extractSkillsJars` with the sbt-mcp `sbt-task` tool, or `./sbt extractSkillsJars`. `.kiro/skills/` is gitignored, so it does not exist until this
   runs. If sbt cannot download artifacts (for example HTTP 429 or a proxy 403), stop and report the
   error instead of changing resolvers.
3. Read `.kiro/skills/*zen-of-projects*/SKILL.md` and follow its "Daily Routine" section, using
   `AGENTS.md` for this project's commands and documented exceptions.
