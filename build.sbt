enablePlugins(GraalVMNativeImagePlugin)

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

graalVMNativeImageOptions ++= Seq(
  "--no-fallback",
  "--install-exit-handlers",

  "--initialize-at-run-time=io.netty.channel.DefaultFileRegion",
  "--initialize-at-run-time=io.netty.channel.epoll.Native",
  "--initialize-at-run-time=io.netty.channel.epoll.Epoll",
  "--initialize-at-run-time=io.netty.channel.epoll.EpollEventLoop",
  "--initialize-at-run-time=io.netty.channel.epoll.EpollEventArray",
  "--initialize-at-run-time=io.netty.channel.kqueue.KQueue",
  "--initialize-at-run-time=io.netty.channel.kqueue.KQueueEventLoop",
  "--initialize-at-run-time=io.netty.channel.kqueue.KQueueEventArray",
  "--initialize-at-run-time=io.netty.channel.kqueue.Native",
  "--initialize-at-run-time=io.netty.channel.unix.Limits",
  "--initialize-at-run-time=io.netty.channel.unix.Errors",
  "--initialize-at-run-time=io.netty.channel.unix.IovArray",
  "--initialize-at-run-time=io.netty.handler.codec.compression.ZstdOptions",
  "--initialize-at-run-time=io.netty.handler.ssl.BouncyCastleAlpnSslUtils",
  "--initialize-at-run-time=io.netty.incubator.channel.uring.IOUringEventLoopGroup",

  "-H:+ReportExceptionStackTraces",
)

if (sys.env.get("STATIC").contains("true")) {
  graalVMNativeImageOptions ++= Seq(
    "--static",
    "--libc=musl",
  )
} else {
  graalVMNativeImageOptions ++= Seq(
    "-H:+StaticExecutableWithDynamicLibC",
//    "--initialize-at-run-time=io.netty.incubator.channel.uring.IOUringEventLoopGroup",
//    "--initialize-at-run-time=io.netty.incubator.channel.uring.Native",
  )
}

//run / javaOptions += s"-agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image"
//javaOptions += s"-agentlib:native-image-agent=trace-output=${(target in GraalVMNativeImage).value}/trace-output.json"
