name := "compliance-notification-router"
version := "1.0"
scalaVersion := "2.13.12"

// Enable the native packager plugin
enablePlugins(JavaAppPackaging)

libraryDependencies ++= Seq(
  "org.apache.kafka" % "kafka-clients" % "3.5.1",
  "com.typesafe" % "config" % "1.4.2",
  "ch.qos.logback" % "logback-classic" % "1.4.11",
  
  // JSON
  "io.circe" %% "circe-core" % "0.14.6",
  "io.circe" %% "circe-generic" % "0.14.6",
  "io.circe" %% "circe-parser" % "0.14.6"
)
