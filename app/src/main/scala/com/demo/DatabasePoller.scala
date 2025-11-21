package com.demo

import java.sql.DriverManager
import java.util.Properties
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}

object DatabasePoller {
  
  def main(args: Array[String]): Unit = {
    println("Starting Database Poller...")
    
    // Kafka producer configuration
    val props = new Properties()
    props.put("bootstrap.servers", "localhost:9092")
    props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    
    val producer = new KafkaProducer[String, String](props)
    
    // Database connection
    val dbUrl = "jdbc:postgresql://localhost:5432/demodata"
    val dbUser = "demo"
    val dbPassword = "demo123"
    
    try {
      val connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword)
      val statement = connection.createStatement()
      val resultSet = statement.executeQuery("SELECT id, name, email FROM users")
      
      println("Reading users from database and sending to Kafka...")
      
      while (resultSet.next()) {
        val id = resultSet.getInt("id")
        val name = resultSet.getString("name")
        val email = resultSet.getString("email")
        
        val message = s"""{"id": $id, "name": "$name", "email": "$email"}"""
        val record = new ProducerRecord[String, String]("user-events", id.toString, message)
        
        producer.send(record)
        println(s"Sent: $message")
      }
      
      resultSet.close()
      statement.close()
      connection.close()
      
      println("Done! All users sent to Kafka.")
      
    } catch {
      case e: Exception => 
        println(s"Error: ${e.getMessage}")
        e.printStackTrace()
    } finally {
      producer.close()
    }
  }
}