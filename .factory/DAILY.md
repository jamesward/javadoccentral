# Daily Routine

If there are other open PRs for this work, update that PR instead of creating a new one.

1. Update the Skills dependency. It is pinned in `build.sbt` as
   `"com.jamesward" % "skills" % "<version>" % Skills`. List every pin (some projects also pin it
   in an `example/` build) with:

   ```bash
   grep -rn '"com.jamesward" % "skills"' --include='*.sbt' . | grep -v -e /target/ -e /src/sbt-test/
   ```

   Get the latest release from Maven Central itself, not a mirror (mirrors lag new releases):

   ```bash
   curl -fsS https://repo1.maven.org/maven2/com/jamesward/skills/maven-metadata.xml \
     | sed -n 's:.*<release>\(.*\)</release>.*:\1:p'
   ```

   If that request fails, retry with the base URL `https://repo.maven.apache.org/maven2`. Set every
   pin to the version it prints.
2. Run `./sbt extractSkillsJars`. `.kiro/skills/` is gitignored, so it does not exist until this
   runs. If sbt cannot download artifacts (for example HTTP 429 or a proxy 403), stop and report the
   error instead of changing resolvers.
3. Read `.kiro/skills/*zen-of-projects*/SKILL.md` and follow its "Daily Routine" section, using
   `AGENTS.md` for this project's commands and documented exceptions.
