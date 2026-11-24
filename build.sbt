val _javaCheck: Unit = require(sys.props("java.specification.version").toInt >= 25, s"Java 25+ is required, but found ${sys.props("java.version")}")

val useLocalSubprojects = sys.props.get("local").isDefined
val zioHttpMcpDir = file("../zio-http-mcp")
val zioMavenCentralDir = file("../zio-mavencentral")
val zioHttpGuardDir = file("../zio-http-guard")

// zio-mavencentral and zio-http-mcp come from Maven Central. If publishing is
// ever blocked again, build them from their git repos at pinned commits instead:
// uncomment the two URIs and the `dependsOn` lines below, and remove their
// `libraryDependencies` entries.
//val zioMavenCentralGit = uri("https://github.com/jamesward/zio-mavencentral.git#317012680d5a47f3505069b3af6f7e5f6929280f")
//val zioHttpMcpGit      = uri("https://github.com/jamesward/zio-http-mcp.git#e606517a4f0af755ba432c6cc8410a698a74a1a7")

lazy val root = {
  val base = (project in file(".")).enablePlugins(JavaAppPackaging)
  val withMcp = if (useLocalSubprojects && zioHttpMcpDir.exists()) base.dependsOn(RootProject(zioHttpMcpDir)) else base
  //val withMcp = base.dependsOn(RootProject(if (useLocalSubprojects && zioHttpMcpDir.exists()) zioHttpMcpDir.toURI else zioHttpMcpGit))
  val withMaven = if (useLocalSubprojects && zioMavenCentralDir.exists()) withMcp.dependsOn(RootProject(zioMavenCentralDir)) else withMcp
  //val withMaven = withMcp.dependsOn(RootProject(if (useLocalSubprojects && zioMavenCentralDir.exists()) zioMavenCentralDir.toURI else zioMavenCentralGit))
  if (useLocalSubprojects && zioHttpGuardDir.exists()) withMaven.dependsOn(RootProject(zioHttpGuardDir)) else withMaven
}

val zioVersion = "2.1.27"

val flywayVersion = "13.13.0"

// Hack Alert: This is the default when not in buildpacks (i.e. `default`)
// In buildpacks it is javadoccentral which puts it alphabetically after dev.zio.zio-constraintless_3-0.3.1.jar
// This causes the wrong Main-Class to get picked up.
// https://github.com/paketo-buildpacks/executable-jar/issues/206
organization := "default"

name := "javadoccentral"

// so we don't have to wait on Maven Central sync
//resolvers += "OSS Staging" at "https://oss.sonatype.org/content/groups/staging"

// todo: production builds with opt
scalacOptions ++= Seq(
  //"-Yexplicit-nulls", // doesn't seem to work anymore
  "-language:strictEquality",
  "-deprecation",
  "-Werror",
  //  "-Wopt:all",
)

// only in CI & Heroku
scalacOptions ++= {
  if (sys.env.contains("CI") || sys.env.contains("STACK")) {
    streams.value.log.info("enabling optimizations in CI & Heroku builds")
    Seq("-opt", "-opt-inline:**")
  }
  else {
    Seq.empty
  }
}

// Test sources aren't optimized: `-opt-inline:**` inlines JDK code into them
// (e.g. `HttpRequest.newBuilder`, `String(bytes, UTF_8)`), and the inlined code
// references JDK-internal classes the module system doesn't export, so the
// specs fail with IllegalAccessError in CI. Main sources keep the optimizer.
Test / scalacOptions ~= (_.filterNot(_.startsWith("-opt")))

scalaVersion := "3.10.1"

// SkillsJars
skillsJarsOutputDir := Some(file(".kiro/skills"))
libraryDependencies += "com.jamesward" % "skills" % "0.0.27" % Skills

libraryDependencies ++= Seq(
  "dev.zio" %% "zio"                   % zioVersion,
  "dev.zio" %% "zio-concurrent"        % zioVersion,
  "dev.zio" %% "zio-cache"             % "0.4.1",
  "dev.zio" %% "zio-logging"                % "2.5.5",
  // The slf4j2 bridge is the sole slf4j provider (replaces slf4j-simple): Java
  // library logs (Netty, the Postgres driver, HikariCP, Flyway, jsoup, ...) route through ZIO's logger
  // and pick up the JSON (prod) / text (dev) format from `AppLogging`.
  "dev.zio" %% "zio-logging-slf4j2-bridge" % "2.5.5",
  "dev.zio" %% "zio-direct"            % "1.0.0-RC7",
  // Keep zio-schema / zio-json binary compatible with zio-http-mcp and
  // zio-mavencentral (both built against zio-schema 1.9.2).
  "dev.zio" %% "zio-schema-json"       % "1.9.2",
  "dev.zio" %% "zio-schema-derivation" % "1.9.2",

  // Symbol index: Postgres (pg_trgm) via magnum, schema managed by Flyway
  // (`bin/flyway-migrate` runs in Heroku's release phase, see Procfile).
  "dev.cheleb"      %% "zio-magnum"                 % "4.2.0",
  "com.augustnagro" %% "magnumpg"                   % "2.0.0",
  "com.zaxxer"       % "HikariCP"                   % "7.1.0",
  "org.flywaydb"     % "flyway-core"                % flywayVersion,
  "org.flywaydb"     % "flyway-database-postgresql" % flywayVersion,
  "org.postgresql"   % "postgresql"                 % "42.7.15",

  "org.jsoup" % "jsoup" % "1.24.1",

  "dev.kreuzberg" % "html-to-markdown" % "3.7.2",

  "dev.zio" %% "zio-test"           % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt"       % zioVersion % Test,
  "com.dimafeng" %% "testcontainers-scala-postgresql" % "0.44.1" % Test,

  "io.modelcontextprotocol.sdk" % "mcp-core"           % "2.2.0" % Test,
  "io.modelcontextprotocol.sdk" % "mcp-json-jackson2"  % "2.2.0" % Test,
)

libraryDependencies ++= {
  if (useLocalSubprojects && zioHttpMcpDir.exists()) Seq.empty
  else Seq("com.jamesward" %% "zio-http-mcp" % "0.16.0")
}

libraryDependencies ++= {
  if (useLocalSubprojects && zioMavenCentralDir.exists()) Seq.empty
  else Seq("com.jamesward" %% "zio-mavencentral" % "0.18.0")
}

// Used directly for bulkheads. Keep on the version zio-mavencentral is built
// against (0.10.6): rezilience minor releases aren't binary compatible.
libraryDependencies += "nl.vroste" %% "rezilience" % "0.10.6"

libraryDependencies ++= {
  if (useLocalSubprojects && zioHttpGuardDir.exists()) Seq.empty
  else Seq("com.jamesward" %% "zio-http-guard" % "0.3.0")
}

Compile / packageDoc / publishArtifact := false

Compile / doc / sources := Seq.empty

fork := true

javaOptions ++= Seq(
  "-Djava.net.preferIPv4Stack=true",
  "-XX:MaxDirectMemorySize=96m",
  "-XX:MaxMetaspaceSize=96m",
  "-XX:ReservedCodeCacheSize=48m",
  "-XX:CICompilerCount=2",
  // JDK 25: silence "terminally deprecated sun.misc.Unsafe" warnings
  // emitted by scala-library (LazyVals$) and "restricted method
  // java.lang.System::loadLibrary" warnings emitted by netty-common.
  // Both are upstream and unavoidable until those libraries migrate;
  // these flags suppress the noise.
  "--enable-native-access=ALL-UNNAMED",
  "--sun-misc-unsafe-memory-access=allow",
)

// The Metaspace/direct-memory caps above emulate the 512MB Heroku dyno for the
// app itself. Forked test JVMs additionally load Testcontainers + docker-java
// (which back the Postgres-based suites and AppTest), far exceeding the 96m
// Metaspace cap and OOMing. Raise the cap for the Test scope only; production
// run flags are unaffected. A later -XX:MaxMetaspaceSize on the command line
// wins, so this overrides the inherited 96m.
Test / javaOptions += "-XX:MaxMetaspaceSize=512m"

Compile / mainClass := Some("App")
Test / mainClass := Some("AppTest")

// sbt-native-packager writes a launcher per discovered main class: `bin/javadoccentral`
// (App, the web process) and `bin/flyway-migrate` (FlywayMigrate, the release phase).
