enablePlugins(GraalVMNativeImagePlugin)

name := "javadoccentral"

scalacOptions ++= Seq(
  "-Yexplicit-nulls",
  "-language:strictEquality",
  "-Xfatal-warnings",
)

scalaVersion := "3.2.2"

val zioVersion = "2.0.13"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio"                % zioVersion,
  "dev.zio" %% "zio-concurrent"     % zioVersion,
  "dev.zio" %% "zio-logging"        % "2.1.11",
  "dev.zio" %% "zio-direct"         % "1.0.0-RC7",
  "dev.zio" %% "zio-direct-streams" % "1.0.0-RC7",
  "dev.zio" %% "zio-http"           % "3.0.0-RC1",
  "org.apache.commons" %  "commons-compress" % "1.21",
  //"com.lihaoyi" %% "scalatags"      % "0.12.0",

  "dev.zio" %% "zio-test"           % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt"       % zioVersion % Test,
  "dev.zio" %% "zio-test-magnolia"  % zioVersion % Test,

  "dev.zio" %% "zio-http-testkit"   % "3.0.0-RC1"    % Test,
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

Compile / packageDoc / publishArtifact := false

Compile / doc / sources := Seq.empty

graalVMNativeImageOptions ++= Seq(
  "--verbose",
  "--no-fallback",
  "--static",
  "--install-exit-handlers",
  "--enable-http",
  "--enable-https",
  "--libc=musl",
  "-H:+ReportExceptionStackTraces",
)

/*
// todo: https://github.com/sbt/sbt-native-packager/issues/1330
graalVMNativeImageOptions += s"-H:ReflectionConfigurationFiles=../../src/graal/reflect-config.json"
graalVMNativeImageOptions += s"-H:ResourceConfigurationFiles=../../src/graal/resource-config.json"
*/

fork := true

reStart / javaOptions += "-Djava.net.preferIPv4Stack=true"
run / javaOptions += "-Djava.net.preferIPv4Stack=true"
Test / javaOptions += "-Djava.net.preferIPv4Stack=true"

//run / javaOptions += s"-agentlib:native-image-agent=config-output-dir=src/graal"
//javaOptions += s"-agentlib:native-image-agent=trace-output=${(target in GraalVMNativeImage).value}/trace-output.json"

// todo: before graalvm-native-image:packageBin run integration tests with the above config-output to generate the configs, bonus if in docker
