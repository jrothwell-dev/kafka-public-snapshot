name := "kafka-demo"

version := "0.1"

scalaVersion := "2.13.13"

libraryDependencies ++= Seq(
  "org.apache.kafka" % "kafka-clients" % "3.5.1",
  "org.postgresql" % "postgresql" % "42.6.0",
  "com.typesafe" % "config" % "1.4.2",
  "ch.qos.logback" % "logback-classic" % "1.4.11",
  
  // Slick for database access
  "com.typesafe.slick" %% "slick" % "3.5.0",
  "com.typesafe.slick" %% "slick-hikaricp" % "3.5.0",
  
  // Flyway for migrations
  "org.flywaydb" % "flyway-core" % "9.22.3",
  "org.flywaydb" % "flyway-database-postgresql" % "10.8.1",
  
  // Redis client
  "net.debasishg" %% "redisclient" % "3.42",
  
  // PureConfig for type-safe configuration
  "com.github.pureconfig" %% "pureconfig" % "0.17.4"
)