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
  "com.github.pureconfig" %% "pureconfig" % "0.17.4",
  
  // Email
  "com.sun.mail" % "javax.mail" % "1.6.2",
  
  // JSON
  "io.circe" %% "circe-core" % "0.14.6",
  "io.circe" %% "circe-generic" % "0.14.6",
  "io.circe" %% "circe-parser" % "0.14.6",
  
  // Testing
  "org.scalatest" %% "scalatest" % "3.2.17" % Test,
  "com.dimafeng" %% "testcontainers-scala-scalatest" % "0.41.0" % Test,
  "com.dimafeng" %% "testcontainers-scala-postgresql" % "0.41.0" % Test,
  "com.dimafeng" %% "testcontainers-scala-kafka" % "0.41.0" % Test
)