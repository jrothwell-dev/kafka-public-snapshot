package com.demo.notification

import com.demo.config.{AppConfig, ConfigLoader}
import com.demo.notification.models._
import com.demo.notification.kafka.{NotificationConsumer, NotificationProducer}
import com.demo.service.{EmailService, EmailTemplateLoader}
import java.time.Instant

class NotificationService(
  config: AppConfig,
  emailService: EmailService,
  producer: NotificationProducer,
  consumer: NotificationConsumer
) {
  
  private var running = true
  
  def start(): Unit = {
    println("=" * 60)
    println("Notification Service Starting")
    println("=" * 60)
    println(s"Consuming from: ${config.kafka.topics.notificationRequests}")
    println(s"Publishing to: ${config.kafka.topics.notificationEvents}")
    println(s"Poll interval: ${config.notificationService.pollInterval}ms")
    println("=" * 60)
    println()
    
    while (running) {
      try {
        val requests = consumer.poll(config.notificationService.pollInterval.toLong)
        
        requests.foreach(processRequest)
        
        if (requests.nonEmpty) {
          consumer.commitSync()
        }
        
      } catch {
        case e: Exception =>
          println(s"Error in notification service: ${e.getMessage}")
          e.printStackTrace()
      }
    }
    
    consumer.close()
    producer.close()
  }
  
  private def processRequest(request: NotificationRequest): Unit = {
    println(s"Processing notification request: ${request.id}")
    
    // Publish "sending" event
    producer.publishEvent(NotificationEvent(
      requestId = request.id,
      eventType = EventType.Sending,
      notificationType = request.notificationType,
      recipients = request.recipients,
      status = Status.Success,
      metadata = Map("requested_by" -> request.requestedBy)
    ))
    
    request.notificationType match {
      case NotificationType.Email => handleEmail(request)
      case NotificationType.SMS => handleUnsupported(request, "SMS")
      case NotificationType.Slack => handleUnsupported(request, "Slack")
    }
  }
  
  private def handleEmail(request: NotificationRequest): Unit = {
    val result = EmailTemplateLoader.loadAndReplace(request.template, request.data).flatMap { template =>
      val isHtml = request.template.endsWith(".html")
      emailService.sendEmail(template.to, template.subject, template.body, isHtml)
    }
    
    result match {
      case Right(_) =>
        println(s"✓ Email sent successfully for request: ${request.id}")
        producer.publishEvent(NotificationEvent(
          requestId = request.id,
          eventType = EventType.Sent,
          notificationType = NotificationType.Email,
          recipients = request.recipients,
          status = Status.Success,
          metadata = Map(
            "template" -> request.template,
            "requested_by" -> request.requestedBy
          )
        ))
        
      case Left(error) =>
        println(s"✗ Email failed for request: ${request.id} - $error")
        producer.publishEvent(NotificationEvent(
          requestId = request.id,
          eventType = EventType.Failed,
          notificationType = NotificationType.Email,
          recipients = request.recipients,
          status = Status.Failed,
          error = Some(error),
          metadata = Map(
            "template" -> request.template,
            "requested_by" -> request.requestedBy
          )
        ))
    }
  }
  
  private def handleUnsupported(request: NotificationRequest, notifType: String): Unit = {
    println(s"✗ $notifType notifications not yet implemented for request: ${request.id}")
    producer.publishEvent(NotificationEvent(
      requestId = request.id,
      eventType = EventType.Failed,
      notificationType = request.notificationType,
      recipients = request.recipients,
      status = Status.Failed,
      error = Some(s"$notifType notifications not yet implemented")
    ))
  }
  
  def stop(): Unit = {
    running = false
  }
}

object NotificationService {
  def main(args: Array[String]): Unit = {
    val config = ConfigLoader.load()
    val emailService = EmailService(config.smtp)
    val producer = NotificationProducer(config)
    val consumer = NotificationConsumer(config)
    
    val service = new NotificationService(config, emailService, producer, consumer)
    
    // Graceful shutdown
    sys.addShutdownHook {
      println("\nShutting down notification service...")
      service.stop()
    }
    
    service.start()
  }
}