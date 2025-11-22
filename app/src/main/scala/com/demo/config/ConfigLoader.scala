package com.demo.config

import pureconfig._
import pureconfig.generic.auto._
import com.typesafe.config.ConfigFactory
import java.io.File
import scala.io.Source

object ConfigLoader {
  
  def load(): AppConfig = {
    // Load .env file first
    loadEnvFile()
    
    // Then load the config (which will use env vars)
    val config = ConfigFactory.load()
    ConfigSource.fromConfig(config).loadOrThrow[AppConfig]
  }
  
  private def loadEnvFile(): Unit = {
    // Try to find .env file in multiple locations
    val possibleLocations = Seq(
      new File(".env"),                                    // Current directory
      new File("app/.env"),                                // app subdirectory  
      new File("../.env"),                                 // Parent directory
      new File(System.getProperty("user.dir") + "/.env"),  // Working directory
      new File(System.getProperty("user.dir") + "/app/.env")
    )
    
    val envFile = possibleLocations.find(_.exists())
    
    envFile match {
      case Some(file) =>
        println(s"Loading .env from: ${file.getAbsolutePath}")
        
        Source.fromFile(file).getLines().foreach { line =>
          val trimmed = line.trim
          if (trimmed.nonEmpty && !trimmed.startsWith("#")) {
            trimmed.split("=", 2) match {
              case Array(key, value) =>
                val cleanKey = key.trim
                val cleanValue = value.trim
                System.setProperty(cleanKey, cleanValue)
                println(s"  Loaded: $cleanKey = ${cleanValue.take(20)}...")
              case _ => // Ignore malformed lines
            }
          }
        }
        
      case None =>
        println("WARNING: No .env file found in any expected location:")
        possibleLocations.foreach(f => println(s"  - ${f.getAbsolutePath}"))
    }
  }
}