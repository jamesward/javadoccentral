import com.typesafe.sbt.packager.docker.DockerPermissionStrategy

enablePlugins(PlayScala, LauncherJarPlugin, DockerPlugin)

name := "javadoccentral"

scalaVersion := "2.13.1"

libraryDependencies ++= Seq(
  guice,
  ws,
  "org.apache.commons" % "commons-compress" % "1.11"
)

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
  "-Xlint:nullary-override",
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

publishArtifact in (Compile, packageDoc) := false

publishArtifact in packageDoc := false

sources in (Compile, doc) := Seq.empty

dockerUpdateLatest := true
dockerBaseImage := "adoptopenjdk:14-jre-hotspot"
daemonUserUid in Docker := None
daemonUser in Docker := "root"
dockerPermissionStrategy := DockerPermissionStrategy.None
dockerEntrypoint := Seq("java", "-jar", "-Dhttp.port=\\$PORT", s"/opt/docker/lib/${(artifactPath in packageJavaLauncherJar).value.getName}")
dockerCmd :=  Seq.empty

val maybeDockerSettings = sys.props.get("dockerImageUrl").flatMap { imageUrl =>
  val parts = imageUrl.split("/")
  if (parts.size == 3) {
    val nameParts = parts(2).split(':')
    if (nameParts.length == 2)
      Some((parts(0), parts(1), nameParts(0), Some(nameParts(1))))
    else
      Some((parts(0), parts(1), parts(2), None))
  }
  else {
    None
  }
}

dockerRepository := maybeDockerSettings.map(_._1)
dockerUsername := maybeDockerSettings.map(_._2)
packageName in Docker := maybeDockerSettings.map(_._3).getOrElse(name.value)
version in Docker := maybeDockerSettings.flatMap(_._4).getOrElse(version.value)
