//requires partner, apex, metadata and tooling jars generated by wsc and placed in ./lib folder

name := "tooling-force.com"

version := "0.1-SNAPSHOT"

scalaVersion := "2.10.2"

scalacOptions ++= Seq(
  "-deprecation", 
  "-encoding", "UTF-8",
  "-feature", 
  "-unchecked"
)

resolvers ++= Seq(
  "Sonatype OSS Releases"  at "http://oss.sonatype.org/content/repositories/releases/",
  "Sonatype OSS Snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/"
)

libraryDependencies ++= Seq(
  "commons-logging" %  "commons-logging" % "1.1.3",
  "org.scalatest" %  "scalatest_2.10" % "2.0" % "test",
  "com.typesafe.akka" % "akka-actor_2.10" % "2.2-M1"
)

//exportJars := true

