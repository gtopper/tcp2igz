name := "http-amp"

organization := "gtopper"

version := "0.1"

scalaVersion := "2.12.2"

scalacOptions += "-target:jvm-1.8"

libraryDependencies += "org.apache.avro" % "avro" % "1.8.1"

libraryDependencies += "com.typesafe.akka" %% "akka-http" % "10.0.7"
