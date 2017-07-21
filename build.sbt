
name := "porthub"

version := "0.0"

scalaVersion in ThisBuild := "2.11.8"
val akkaVersion = "2.3.14"
val slickVersion = "3.1.1"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")
scalacOptions ++= Seq("-language:implicitConversions")
scalacOptions ++= Seq("-Ywarn-value-discard", "-Xfatal-warnings")

libraryDependencies ++= Seq(
  "org.scalatest"       %% "scalatest"        % "2.2.6",

  "org.joda"            % "joda-convert"      % "1.7",
  "joda-time"           % "joda-time"         % "2.9.3",

  "org.aspectj"         %  "aspectjweaver"    % "1.8.9",
  "io.kamon"            %% "kamon-core"       % "0.6.3",
  "io.kamon"            %% "kamon-autoweave"  % "0.6.3",
  "io.kamon"            %% "kamon-graphite"   % "1.0.0",
  "io.kamon"            %% "kamon-system-metrics" % "0.6.3",
  "io.kamon"            %% "kamon-akka"       % "0.6.3",
  "io.kamon"            %% "kamon-scala"      % "0.6.3",

  "com.typesafe.akka"   %% "akka-actor"       % akkaVersion,
  "com.typesafe.akka"   %% "akka-slf4j"       % akkaVersion,
  "ch.qos.logback"      %  "logback-classic"  % "1.1.3",

  "org.scodec" %% "scodec-core" % "1.10.3",
  "org.scodec" %% "scodec-akka" % "0.3.0",

  "com.github.rgafiyatullin" %% "owl-akka-goodies" % "0.1.8.1"
)


lazy val porthub =
  Project("porthub", file("."))
    .enablePlugins(SbtNativePackager)
    .enablePlugins(JavaServerAppPackaging)
//    .settings(com.github.retronym.SbtOneJar.oneJarSettings)


