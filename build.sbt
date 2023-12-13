enablePlugins(LauncherJarPlugin)

// Hack Alert: This is the default when not in buildpacks (i.e. `default`)
// In buildpacks it is javadoccentral which puts it alphabetically after dev.zio.zio-constraintless_3-0.3.1.jar
// This causes the wrong Main-Class to get picked up.
// https://github.com/paketo-buildpacks/executable-jar/issues/206
organization := "default"

name := "javadoccentral"

// so we don't have to wait on Maven Central sync
//resolvers += "OSS Staging" at "https://oss.sonatype.org/content/groups/staging"

scalacOptions ++= Seq(
  "-Yexplicit-nulls",
  "-language:strictEquality",
  "-Xfatal-warnings",
)

scalaVersion := "3.3.1"

val zioVersion = "2.0.20"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio"                % zioVersion,
  "dev.zio" %% "zio-concurrent"     % zioVersion,
  "dev.zio" %% "zio-cache"          % "0.2.3",
  "dev.zio" %% "zio-logging"        % "2.1.16",
  "dev.zio" %% "zio-direct"         % "1.0.0-RC7",
  "dev.zio" %% "zio-direct-streams" % "1.0.0-RC7",
  "dev.zio" %% "zio-http"           % "3.0.0-RC4",
  "org.apache.commons" %  "commons-compress" % "1.25.0",

  "com.jamesward" %% "zio-mavencentral" % "0.0.8",

  "dev.zio" %% "zio-test"           % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt"       % zioVersion % Test,
  "dev.zio" %% "zio-test-magnolia"  % zioVersion % Test,
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

Compile / packageDoc / publishArtifact := false

Compile / doc / sources := Seq.empty

fork := true

javaOptions += "-Djava.net.preferIPv4Stack=true"


//run / javaOptions += s"-agentlib:native-image-agent=config-output-dir=src/graal"
//javaOptions += s"-agentlib:native-image-agent=trace-output=${(target in GraalVMNativeImage).value}/trace-output.json"
