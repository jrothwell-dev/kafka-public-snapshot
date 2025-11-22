name := "sc-poller"
version := "1.0"
scalaVersion := "2.13.12"

libraryDependencies ++= Seq(
  "org.apache.kafka" % "kafka-clients" % "3.5.1",
  "com.typesafe" % "config" % "1.4.2",
  "ch.qos.logback" % "logback-classic" % "1.4.11",
  
  // JSON
  "io.circe" %% "circe-core" % "0.14.6",
  "io.circe" %% "circe-generic" % "0.14.6",
  "io.circe" %% "circe-parser" % "0.14.6",

  // HTTP client
  "com.softwaremill.sttp.client3" %% "core" % "3.9.1",
  "com.softwaremill.sttp.client3" %% "circe" % "3.9.1",
  
  // PureConfig for type-safe configuration
  "com.github.pureconfig" %% "pureconfig" % "0.17.4"
)