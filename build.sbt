val _ = require(sys.props("java.specification.version").toInt >= 25, s"Java 25+ is required, but found ${sys.props("java.version")}")

val useLocalSubprojects = sys.props.get("local").isDefined
val zioHttpMcpDir = file("../zio-http-mcp")
val zioMavenCentralDir = file("../zio-mavencentral")
val zioHttpGuardDir = file("../zio-http-guard")

lazy val root = {
  val base = (project in file(".")).enablePlugins(LauncherJarPlugin)
  val withMcp = if (useLocalSubprojects && zioHttpMcpDir.exists()) base.dependsOn(RootProject(zioHttpMcpDir)) else base
  val withMaven = if (useLocalSubprojects && zioMavenCentralDir.exists()) withMcp.dependsOn(RootProject(zioMavenCentralDir)) else withMcp
  if (useLocalSubprojects && zioHttpGuardDir.exists()) withMaven.dependsOn(RootProject(zioHttpGuardDir)) else withMaven
}

val zioVersion = "2.1.26"

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
  // "-Xfatal-warnings", // doesn't seem to work anymore
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

scalaVersion := "3.8.4"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio"                   % zioVersion,
  "dev.zio" %% "zio-concurrent"        % zioVersion,
  "dev.zio" %% "zio-cache"             % "0.2.8",
  "dev.zio" %% "zio-logging"           % "2.5.3",
  "dev.zio" %% "zio-direct"            % "1.0.0-RC7",
  "dev.zio" %% "zio-redis"             % "1.2.1",
  "dev.zio" %% "zio-schema-protobuf"   % "1.8.5",
  "dev.zio" %% "zio-schema-json"       % "1.8.5",
  "dev.zio" %% "zio-schema-derivation" % "1.8.5",

  "org.slf4j" % "slf4j-simple" % "2.0.18",

  "org.jsoup" % "jsoup" % "1.22.2",

  "dev.kreuzberg" % "html-to-markdown" % "3.5.7",

  "dev.zio" %% "zio-test"           % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt"       % zioVersion % Test,
  "dev.zio" %% "zio-test-magnolia"  % zioVersion % Test,
  "dev.zio" %% "zio-redis-embedded" % "1.2.1" % Test,

  "io.modelcontextprotocol.sdk" % "mcp-core"           % "1.1.2" % Test,
  "io.modelcontextprotocol.sdk" % "mcp-json-jackson2"  % "1.1.3" % Test,
)

libraryDependencies ++= {
  if (useLocalSubprojects && zioHttpMcpDir.exists()) Seq.empty
  else Seq("com.jamesward" %% "zio-http-mcp" % "0.0.9")
}

libraryDependencies ++= {
  if (useLocalSubprojects && zioMavenCentralDir.exists()) Seq.empty
  else Seq("com.jamesward" %% "zio-mavencentral" % "0.9.0")
}

libraryDependencies ++= {
  if (useLocalSubprojects && zioHttpGuardDir.exists()) Seq.empty
  else Seq("com.jamesward" %% "zio-http-guard" % "0.0.1")
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

lazy val runTest = taskKey[Unit]("run AppTest")

runTest := (Test / runMain).toTask(" AppTest").value

lazy val reStartTest =
  inputKey[spray.revolver.AppProcess]("re-start, but test")

reStartTest :=
  Def.inputTask {
    spray.revolver.Actions.restartApp(
      streams.value,
      reLogTag.value,
      thisProjectRef.value,
      reForkOptions.value,
      Some("AppTest"),
      (Test / fullClasspath).value,
      reStartArgs.value,
      spray.revolver.Actions.startArgsParser.parsed
    )
  }.dependsOn(Compile / products).evaluated
