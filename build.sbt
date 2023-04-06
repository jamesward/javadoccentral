enablePlugins(GraalVMNativeImagePlugin)

name := "javadoccentral"

scalacOptions ++= Seq(
  "-Yexplicit-nulls",
  "-language:strictEquality",
  "-Xfatal-warnings",
)

scalaVersion := "3.2.2"

val zioVersion = "2.0.10"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio"                % zioVersion,
  "dev.zio" %% "zio-concurrent"     % zioVersion,
  "dev.zio" %% "zio-logging"        % "2.1.11",
  "dev.zio" %% "zio-direct"         % "1.0.0-RC7",
  "dev.zio" %% "zio-direct-streams" % "1.0.0-RC7",
  "dev.zio" %% "zio-http"           % "0.0.5",

  "dev.zio" %% "zio-test"           % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt"       % zioVersion % Test,
  "dev.zio" %% "zio-test-magnolia"  % zioVersion % Test,

  "dev.zio" %% "zio-http-testkit"   % "0.0.5"    % Test,
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
/*

val Http4sVersion = "0.23.6"
val CirceVersion = "0.14.1"
val Specs2Version = "4.9.3"
val Slf4jVersion = "1.7.32"
val CommonsCompress = "1.21"

libraryDependencies ++= Seq(
  "org.http4s"         %% "http4s-blaze-server"  % Http4sVersion,
  "org.http4s"         %% "http4s-blaze-client"  % Http4sVersion,
  "org.http4s"         %% "http4s-circe"         % Http4sVersion,
  "org.http4s"         %% "http4s-dsl"           % Http4sVersion,
  "org.http4s"         %% "http4s-twirl"         % Http4sVersion,
  "io.circe"           %% "circe-generic"        % CirceVersion,
  "io.circe"           %% "circe-optics"         % CirceVersion,
  "org.slf4j"          %  "slf4j-simple"         % Slf4jVersion,
  "org.apache.commons" %  "commons-compress"     % CommonsCompress,

  "org.specs2"         %% "specs2-core"                % Specs2Version % Test,
  "org.typelevel"      %% "cats-effect-testing-specs2" % "1.2.0"       % Test,
)

addCompilerPlugin("org.typelevel" % "kind-projector" % "0.13.0" cross CrossVersion.full)
addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.1")

scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-explaintypes",
  "-feature",
  "-Xcheckinit",
  "-Xfatal-warnings",
  "-Xlint:adapted-args",
  "-Xlint:constant",
  "-Xlint:delayedinit-select",
  "-Xlint:doc-detached",
  "-Xlint:inaccessible",
  "-Xlint:infer-any",
  "-Xlint:nullary-unit",
  "-Xlint:option-implicit",
  "-Xlint:package-object-classes",
  "-Xlint:poly-implicit-overload",
  "-Xlint:private-shadow",
  "-Xlint:stars-align",
  "-Xlint:type-parameter-shadow",
  "-Ywarn-dead-code",
  "-Ywarn-extra-implicit",
  "-Ywarn-numeric-widen",
  "-Ywarn-unused:implicits",
  "-Ywarn-unused:locals",
  "-Ywarn-unused:params",
  "-Ywarn-unused:patvars",
  "-Ywarn-unused:privates",
)

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

// todo: https://github.com/sbt/sbt-native-packager/issues/1330
graalVMNativeImageOptions += s"-H:ReflectionConfigurationFiles=../../src/graal/reflect-config.json"
graalVMNativeImageOptions += s"-H:ResourceConfigurationFiles=../../src/graal/resource-config.json"

fork := true

//run / javaOptions += s"-agentlib:native-image-agent=config-output-dir=src/graal"
//javaOptions += s"-agentlib:native-image-agent=trace-output=${(target in GraalVMNativeImage).value}/trace-output.json"

// todo: before graalvm-native-image:packageBin run integration tests with the above config-output to generate the configs, bonus if in docker

 */
