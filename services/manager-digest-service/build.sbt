name := "manager-digest-service"
version := "1.0"
scalaVersion := "2.13.12"

// Enable the native packager plugin
enablePlugins(JavaAppPackaging)

lazy val root = (project in file("."))
  .configs(IntegrationTest)
  .settings(
    Defaults.itSettings,
    IntegrationTest / fork := true
  )

libraryDependencies ++= Seq(
  "org.apache.kafka" % "kafka-clients" % "3.5.1",
  "com.typesafe" % "config" % "1.4.2",
  "ch.qos.logback" % "logback-classic" % "1.4.11",
  
  // JSON
  "io.circe" %% "circe-core" % "0.14.6",
  "io.circe" %% "circe-generic" % "0.14.6",
  "io.circe" %% "circe-parser" % "0.14.6",
  
  // YAML
  "org.yaml" % "snakeyaml" % "2.2",
  
  // Testing
  "org.scalatest" %% "scalatest" % "3.2.17" % "test,it",
  "com.dimafeng" %% "testcontainers-scala-scalatest" % "0.41.0" % "test,it",
  "com.dimafeng" %% "testcontainers-scala-kafka" % "0.41.0" % "test,it"
)

