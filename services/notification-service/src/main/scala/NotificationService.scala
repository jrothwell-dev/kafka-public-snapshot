package com.council.notification

import org.apache.kafka.clients.consumer.{KafkaConsumer, ConsumerRecord}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import java.util.Properties
import java.time.{Duration, Instant}
import scala.jdk.CollectionConverters._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import redis.clients.jedis.{Jedis, JedisPool}
import javax.mail._
import javax.mail.internet._

// Input model from commands.notifications
case class NotificationData(
  wwccNumber: Option[String],
  expiryDate: Option[String],
  daysUntilExpiry: Option[Long]
)

case class NotificationCommand(
  notificationId: String,
  userId: String,
  userName: String,
  email: String,
  issueType: String,
  priority: String,
  template: String,
  data: NotificationData,
  createdAt: String
)

// Output model for events.notifications.sent
case class NotificationSent(
  notificationId: String,
  userId: String,
  email: String,
  issueType: String,
  sentAt: String,
  success: Boolean,
  errorMessage: Option[String]
)

object NotificationService {
  
  // Pure function: generates deduplication key for a notification
  def dedupKey(notificationId: String): String = {
    s"notification:sent:${notificationId}"
  }
  
  // Pure function: creates email subject from notification command
  def createSubject(command: NotificationCommand): String = {
    s"WWCC Compliance Notification - ${command.issueType}"
  }
  
  // Pure function: creates email body from notification command
  def createBody(command: NotificationCommand): String = {
    val data = command.data
    val wwccInfo = data.wwccNumber.map(n => s"WWCC Number: $n").getOrElse("WWCC Number: Not provided")
    val expiryInfo = data.expiryDate.map(d => s"Expiry Date: $d").getOrElse("Expiry Date: Not provided")
    val daysInfo = data.daysUntilExpiry.map(d => s"Days until expiry: $d").getOrElse("")
    
    s"""
      |Dear ${command.userName},
      |
      |This is an automated notification regarding your WWCC compliance status.
      |
      |Issue Type: ${command.issueType}
      |Priority: ${command.priority}
      |
      |$wwccInfo
      |$expiryInfo
      |${if (daysInfo.nonEmpty) daysInfo else ""}
      |
      |Please take appropriate action to ensure compliance.
      |
      |This is an automated message. Please do not reply.
    """.stripMargin.trim
  }
  
  // Sends email using JavaMail
  def sendEmail(
    smtpHost: String,
    smtpPort: Int,
    smtpFrom: String,
    to: String,
    subject: String,
    body: String
  ): Either[String, Unit] = {
    try {
      val props = new Properties()
      props.put("mail.smtp.host", smtpHost)
      props.put("mail.smtp.port", smtpPort.toString)
      props.put("mail.smtp.auth", "false")
      props.put("mail.smtp.starttls.enable", "false")
      
      val session = Session.getInstance(props, null)
      val message = new MimeMessage(session)
      
      message.setFrom(new InternetAddress(smtpFrom))
      message.setRecipient(Message.RecipientType.TO, new InternetAddress(to))
      message.setSubject(subject)
      message.setText(body)
      
      Transport.send(message)
      Right(())
    } catch {
      case e: Exception =>
        Left(e.getMessage)
    }
  }
  
  def main(args: Array[String]): Unit = {
    val kafkaBootstrap = sys.env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    val redisHost = sys.env.getOrElse("REDIS_HOST", "redis")
    val smtpHost = sys.env.getOrElse("SMTP_HOST", "smtp.murrumbidgee.nsw.gov.au")
    val smtpPort = sys.env.getOrElse("SMTP_PORT", "25").toInt
    val smtpFrom = sys.env.getOrElse("SMTP_FROM", "noreply@murrumbidgee.nsw.gov.au")
    
    println("[INFO] Notification Service Starting")
    println(s"[INFO] Kafka: $kafkaBootstrap")
    println(s"[INFO] Redis: $redisHost:6379")
    println(s"[INFO] SMTP: $smtpHost:$smtpPort")
    println(s"[INFO] SMTP From: $smtpFrom")
    
    // Redis connection for deduplication
    val jedisPool = new JedisPool(redisHost, 6379)
    
    // Consumer for commands.notifications
    val consumerProps = new Properties()
    consumerProps.put("bootstrap.servers", kafkaBootstrap)
    consumerProps.put("group.id", "notification-service-v1")
    consumerProps.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    consumerProps.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    consumerProps.put("auto.offset.reset", "earliest")
    consumerProps.put("enable.auto.commit", "true")
    
    val consumer = new KafkaConsumer[String, String](consumerProps)
    consumer.subscribe(List("commands.notifications").asJava)
    
    // Producer for notification sent events
    val producerProps = new Properties()
    producerProps.put("bootstrap.servers", kafkaBootstrap)
    producerProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    producerProps.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    producerProps.put("acks", "all")
    
    val producer = new KafkaProducer[String, String](producerProps)
    
    println("[INFO] Service ready, consuming from commands.notifications...")
    
    while (true) {
      val jedis = jedisPool.getResource
      try {
        val records = consumer.poll(Duration.ofMillis(1000))
        
        records.asScala.foreach { record =>
          decode[NotificationCommand](record.value()) match {
            case Right(command) =>
              // Check if we've already processed this notification
              val key = dedupKey(command.notificationId)
              val alreadyProcessed = jedis.exists(key)
              
              if (!alreadyProcessed) {
                println(s"[INFO] Processing notification: ${command.notificationId}")
                println(s"      User: ${command.userName} (${command.userId})")
                println(s"      Email: ${command.email}")
                println(s"      Issue: ${command.issueType} (priority: ${command.priority})")
                
                // Send email
                val subject = createSubject(command)
                val body = createBody(command)
                val emailResult = sendEmail(smtpHost, smtpPort, smtpFrom, command.email, subject, body)
                
                // Create notification sent event
                val sentAt = Instant.now().toString
                val notificationSent = emailResult match {
                  case Right(_) =>
                    NotificationSent(
                      notificationId = command.notificationId,
                      userId = command.userId,
                      email = command.email,
                      issueType = command.issueType,
                      sentAt = sentAt,
                      success = true,
                      errorMessage = None
                    )
                  case Left(errorMsg) =>
                    NotificationSent(
                      notificationId = command.notificationId,
                      userId = command.userId,
                      email = command.email,
                      issueType = command.issueType,
                      sentAt = sentAt,
                      success = false,
                      errorMessage = Some(errorMsg)
                    )
                }
                
                try {
                  // Publish notification sent event
                  val metadata = producer.send(new ProducerRecord[String, String](
                    "events.notifications.sent",
                    command.notificationId,
                    notificationSent.asJson.noSpaces
                  )).get() // Block until send completes
                  
                  // Mark as processed in Redis (24 hour expiry)
                  jedis.setex(key, 86400, "sent")
                  
                  if (notificationSent.success) {
                    println(s"[INFO] Successfully sent notification: ${command.notificationId}")
                    println(s"      Published to partition ${metadata.partition()} at offset ${metadata.offset()}")
                  } else {
                    println(s"[ERROR] Failed to send notification: ${command.notificationId}")
                    println(s"      Error: ${notificationSent.errorMessage.getOrElse("Unknown error")}")
                    println(s"      Published failure event to partition ${metadata.partition()} at offset ${metadata.offset()}")
                  }
                } catch {
                  case e: Exception =>
                    println(s"[ERROR] Failed to publish notification sent event ${command.notificationId}: ${e.getMessage}")
                    e.printStackTrace()
                }
              } else {
                println(s"[DEBUG] Skipping duplicate notification: ${command.notificationId}")
              }
              
            case Left(e) =>
              println(s"[WARN] Failed to parse notification command: ${e.getMessage}")
          }
        }
        
      } catch {
        case e: Exception =>
          println(s"[ERROR] Unexpected error: ${e.getMessage}")
          e.printStackTrace()
          Thread.sleep(5000)
      } finally {
        jedis.close()
      }
    }
  }
}
