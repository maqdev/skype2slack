import AssemblyKeys._

name := "skype-bot"

organization  := "com.github.maqdev"

version       := "0.1.1"

scalaVersion  := "2.11.3"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

resolvers += "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies ++= {
  val akkaV = "2.3.6"
  val sprayV = "1.3.2"
  Seq(
    "io.spray"            %%  "spray-can"     % sprayV,
    "io.spray"            %%  "spray-routing" % sprayV,
    "io.spray"            %%  "spray-client"  % sprayV,
    "com.typesafe.play"   %% "play-json" % "2.3.4",
    "io.spray"            %%  "spray-testkit" % sprayV  % "test",
    "com.typesafe.akka"   %%  "akka-actor"    % akkaV,
    "com.typesafe.akka"   %%  "akka-testkit"  % akkaV   % "test",
    "org.specs2"          %%  "specs2-core"   % "2.3.11" % "test",
    "org.xerial" % "sqlite-jdbc" % "3.8.7",
    "com.google.guava" % "guava" % "16.0.1"
  )
}

Revolver.settings ++ assemblySettings
