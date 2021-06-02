enablePlugins(SbtTwirl, GraalVMNativeImagePlugin)

name := "javadoccentral"

// must use jdk 11 for static / muslc
javacOptions ++= Seq("-source", "11", "-target", "11")

scalacOptions += "-target:jvm-11"

scalaVersion := "2.13.6"

val Http4sVersion = "0.21.24"
val CirceVersion = "0.14.1"
val Specs2Version = "4.9.3"
val LogbackVersion = "1.2.3"
val Slf4jVersion = "1.7.30"
val CommonsCompress = "1.20"

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

  "org.scalameta"      %% "svm-subs"             % "20.2.0",

  "org.specs2"         %% "specs2-core"          % Specs2Version % Test,
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
  "--no-server",
  "--no-fallback",
  "--static",
  "--install-exit-handlers",
  "--enable-http",
  "--enable-https",
  "--enable-all-security-services",
  "--libc=musl",
  "-H:+RemoveSaturatedTypeFlows",
  "-H:+ReportExceptionStackTraces",
)

// todo: https://github.com/sbt/sbt-native-packager/issues/1330
graalVMNativeImageOptions += s"-H:ReflectionConfigurationFiles=../../src/graal/reflect-config.json"
graalVMNativeImageOptions += s"-H:ResourceConfigurationFiles=../../src/graal/resource-config.json"

fork := true

run / javaOptions += s"-agentlib:native-image-agent=config-output-dir=src/graal"
//javaOptions += s"-agentlib:native-image-agent=trace-output=${(target in GraalVMNativeImage).value}/trace-output.json"

// todo: before graalvm-native-image:packageBin run integration tests with the above config-output to generate the configs, bonus if in docker
