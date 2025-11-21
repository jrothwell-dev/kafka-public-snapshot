package com.demo

import org.flywaydb.core.Flyway
import com.demo.config.ConfigLoader

object MigrationRunner {
  
  def migrate(dbUrl: String, user: String, password: String): Unit = {
    println("Running database migrations...")
    
    val flyway = Flyway.configure()
      .dataSource(dbUrl, user, password)
      .locations("classpath:db/migration")
      .baselineOnMigrate(true)
      .load()
    
    val result = flyway.migrate()
    
    println(s"Successfully applied ${result.migrationsExecuted} migration(s)")
  }
  
  def main(args: Array[String]): Unit = {
    val config = ConfigLoader.load()
    
    println(s"Loading config for app: ${config.app.name}")
    println(s"Database: ${config.database.url}")
    
    migrate(
      config.database.url,
      config.database.user,
      config.database.password
    )
    
    println("\nConfiguration loaded successfully:")
    println(s"  Kafka Bootstrap: ${config.kafka.bootstrapServers}")
    println(s"  Redis: ${config.redis.host}:${config.redis.port}")
    println(s"  SMTP: ${config.smtp.host}:${config.smtp.port}")
  }
}