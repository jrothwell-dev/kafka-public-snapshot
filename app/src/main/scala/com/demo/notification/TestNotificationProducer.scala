package com.demo.notification

import com.demo.config.ConfigLoader
import com.demo.notification.kafka.NotificationProducer
import com.demo.notification.models._

object TestNotificationProducer {
  
  def main(args: Array[String]): Unit = {
    val config = ConfigLoader.load()
    val producer = NotificationProducer(config)
    
    println("Sending test notification request to Kafka...")
    
    val request = NotificationRequest(
      notificationType = NotificationType.Email,
      template = "boss-demo.html",
      recipients = Seq("jordanr@murrumbidgee.nsw.gov.au"),
      data = Map("timestamp" -> java.time.LocalDateTime.now().toString),
      priority = Priority.High,
      requestedBy = "test-producer"
    )
    
    producer.publishRequest(request) match {
      case Right(_) =>
        println(s"✓ Notification request published: ${request.id}")
        println(s"  Type: ${NotificationType.toString(request.notificationType)}")
        println(s"  Template: ${request.template}")
        println(s"  Recipients: ${request.recipients.mkString(", ")}")
        
      case Left(error) =>
        println(s"✗ Failed to publish: $error")
    }
    
    producer.close()
  }
}