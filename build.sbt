name := "ujiauth"

organization := "com.github.jvican"

description := "Module to authenticate against UJI SSO (Jaume I University, Spain)"

version := "1.0"

scalaVersion := "2.11.6"

licenses := Seq(
  "MIT License" -> url("http://www.opensource.org/licenses/mit-license.html")
)

resolvers += Resolver.sonatypeRepo("releases")

libraryDependencies ++= {
  val akkaVersion = "2.3.9"
  val sprayVersion = "1.3.3"
  val base64Version = "0.2.0"
  val xmlrpcVersion = "1.0.1"
  val scalazVersion = "7.1.1"
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
