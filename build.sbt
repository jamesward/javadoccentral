enablePlugins(LauncherJarPlugin)

// Hack Alert: This is the default when not in buildpacks (i.e. `default`)
// In buildpacks it is javadoccentral which puts it alphabetically after dev.zio.zio-constraintless_3-0.3.1.jar
// This causes the wrong Main-Class to get picked up.
// https://github.com/paketo-buildpacks/executable-jar/issues/206
organization := "default"

name := "javadoccentral"

scalacOptions ++= Seq(
  "-Yexplicit-nulls",
  "-language:strictEquality",
  "-Xfatal-warnings",
)

scalaVersion := "3.3.0-RC2" // https://github.com/lampepfl/dotty/issues/13985

val zioVersion = "2.0.13"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio"                % zioVersion,
  "dev.zio" %% "zio-concurrent"     % zioVersion,
  "dev.zio" %% "zio-logging"        % "2.1.11",
  "dev.zio" %% "zio-direct"         % "1.0.0-RC7",
  "dev.zio" %% "zio-direct-streams" % "1.0.0-RC7",
  "dev.zio" %% "zio-http"           % "3.0.0-RC1",
  "org.apache.commons" %  "commons-compress" % "1.21",

  "dev.zio" %% "zio-test"           % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt"       % zioVersion % Test,
  "dev.zio" %% "zio-test-magnolia"  % zioVersion % Test,

  "dev.zio" %% "zio-http-testkit"   % "3.0.0-RC1"    % Test,
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

Compile / packageDoc / publishArtifact := false

Compile / doc / sources := Seq.empty

fork := true

//run / javaOptions += s"-agentlib:native-image-agent=config-output-dir=src/graal"
//javaOptions += s"-agentlib:native-image-agent=trace-output=${(target in GraalVMNativeImage).value}/trace-output.json"
