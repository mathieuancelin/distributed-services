import sbtassembly.Plugin._
import sbtassembly.Plugin.AssemblyKeys._

assemblySettings

name := "distributed-services"

version := "1.0-SNAPSHOT"

scalaVersion := "2.11.6"

resolvers += "patriknw at bintray" at "http://dl.bintray.com/patriknw/maven"

libraryDependencies += "com.github.patriknw" %% "akka-data-replication" % "0.11"

libraryDependencies += "com.typesafe.akka" % "akka-http-scala-experimental_2.11" % "1.0-RC2"

libraryDependencies += "com.typesafe.play"         %% "play-json"                         % "2.3.0"

libraryDependencies += "com.google.guava"          % "guava"                              % "17.0"

libraryDependencies += "ch.qos.logback"            % "logback-classic"                    % "1.1.1"

libraryDependencies += "com.typesafe"              % "config"                             % "1.2.1"

libraryDependencies += "com.codahale.metrics"      % "metrics-core"                       % "3.0.2"

libraryDependencies += "com.ning"                  % "async-http-client"                  % "1.8.8"

libraryDependencies += "com.squareup.okhttp"       % "okhttp"                             % "2.0.0"

libraryDependencies += "org.specs2"                %% "specs2"                            % "2.3.12"            % "test"

jarName in assembly := "distributed-services.jar"

test in assembly := {}

fork in test := true

organization := "com.distributedstuff"

parallelExecution in Test := false

testForkedParallel in Test := false



