name := "ujisso"

organization := "com.github.jvican"

description := "Module to authenticate against UJI SSO (Jaume I University, Spain)"

version := "1.0"

scalaVersion := "2.11.6"

licenses := Seq(
  "MIT License" -> url("http://www.opensource.org/licenses/mit-license.html")
)

resolvers ++= Seq(
  Resolver.sonatypeRepo("releases"),
  Resolver.sonatypeRepo("snapshots")
)

libraryDependencies ++= {
  val akkaVersion = "2.3.9"
  val sprayVersion = "1.3.3"
  val base64Version = "0.2.0"
  val xmlrpcVersion = "1.0.1"
  val scalazVersion = "7.1.3"
  val specs2Core = "2.4.15"

  Seq(
    "com.github.jvican"   %%  "xmlrpc"        % xmlrpcVersion,
    "me.lessis"           %%  "base64"        % base64Version,
    "io.spray"            %%  "spray-routing-shapeless2" % sprayVersion,
    "io.spray"            %%  "spray-testkit" % sprayVersion  % "test",
    "com.typesafe.akka"   %%  "akka-actor"    % akkaVersion,
    "org.scalaz"          %%  "scalaz-core"   % scalazVersion,
    "org.specs2"          %%  "specs2-core"   % specs2Core    % "test"
  )
}

// Settings to publish to Sonatype
licenses := Seq("MIT License" -> url("http://www.opensource.org/licenses/MIT"))

sonatypeProfileName := "com.github.jvican"

pomExtra in Global := <url>https://github.com/jvican/ujisso</url>
  <scm>
    <url>https://github.com/jvican/ujisso.git</url>
    <connection>scm:git:git@github.com:jvican/ujisso.git</connection>
  </scm>
  <developers>
    <developer>
      <id>jvican</id>
      <name>Jorge Vicente Cantero</name>
      <url>https://github.com/jvican</url>
    </developer>
  </developers>
