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
  //"-Yexplicit-nulls", // doesn't seem to work anymore
  "-language:strictEquality",
  // "-Xfatal-warnings", // doesn't seem to work anymore
)

scalaVersion := "3.7.3"

val zioVersion = "2.1.22"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio"                 % zioVersion,
  "dev.zio" %% "zio-concurrent"      % zioVersion,
  "dev.zio" %% "zio-cache"           % "0.2.7",
  "dev.zio" %% "zio-logging"         % "2.5.1",
  "dev.zio" %% "zio-direct"          % "1.0.0-RC7",
  "dev.zio" %% "zio-direct-streams"  % "1.0.0-RC7",
  "dev.zio" %% "zio-http"            % "3.5.1",
  "org.apache.commons" %  "commons-compress" % "1.28.0",
  "org.slf4j" % "slf4j-simple" % "2.0.17",

  "com.jamesward" %% "zio-mavencentral" % "0.0.22",

  "com.softwaremill.chimp" %% "core" % "0.1.6",
  "com.softwaremill.sttp.tapir" %% "tapir-zio" % "1.12.3",
  "com.softwaremill.sttp.tapir" %% "tapir-zio-http-server" % "1.12.3",

  "org.jsoup" % "jsoup" % "1.21.2",

  "dev.zio" %% "zio-test"           % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt"       % zioVersion % Test,
  "dev.zio" %% "zio-test-magnolia"  % zioVersion % Test,
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

Compile / packageDoc / publishArtifact := false

Compile / doc / sources := Seq.empty

fork := true

javaOptions += "-Djava.net.preferIPv4Stack=true"

//run / javaOptions += s"-agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image"
//javaOptions += s"-agentlib:native-image-agent=trace-output=${(target in GraalVMNativeImage).value}/trace-output.json"
