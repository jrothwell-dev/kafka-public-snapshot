name := "notification-service"
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
  
  // JavaMail for email sending
  "com.sun.mail" % "javax.mail" % "1.6.2",
  
  // Mustache templating
  "com.github.spullara.mustache.java" % "compiler" % "0.9.10",
  
  // Redis for deduplication
  "redis.clients" % "jedis" % "5.0.0",
  
  // Testing
  "org.scalatest" %% "scalatest" % "3.2.17" % "test,it",
  "com.dimafeng" %% "testcontainers-scala-scalatest" % "0.41.0" % "test,it",
  "com.dimafeng" %% "testcontainers-scala-kafka" % "0.41.0" % "test,it"
)

