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
import scala.io.Source

// Input model from processed.wwcc.status
case class WwccCompliance(
  userId: String,
  firstName: String,
  lastName: String,
  email: Option[String],
  department: Option[String],
  position: Option[String],
  startDate: Option[String],
  wwccNumber: Option[String],
  expiryDate: Option[String],
  daysUntilExpiry: Option[Long],
  daysSinceStart: Option[Long],
  safetyculture_status: String,
  approval_status: String,
  compliance_status: String,  // COMPLIANT, EXPIRED, EXPIRING, NOT_APPROVED, MISSING, etc.
  flags: List[String],
  processedAt: String
)

// Notification config model
case class NotificationRule(priority: String)
case class NotificationConfig(
  rules: Map[String, NotificationRule],
  override_recipient: String,
  template: String
)

// Output model for commands.notifications
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

object ComplianceNotificationRouterService {
  
  def loadConfig(): NotificationConfig = {
    val configStream = getClass.getResourceAsStream("/notification-config.json")
    if (configStream == null) {
      throw new RuntimeException("notification-config.json not found in resources")
    }
    val configJson = Source.fromInputStream(configStream).mkString
    decode[NotificationConfig](configJson) match {
      case Right(config) => config
      case Left(e) => throw new RuntimeException(s"Failed to parse notification-config.json: ${e.getMessage}")
    }
  }
  
  def main(args: Array[String]): Unit = {
    val kafkaBootstrap = sys.env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    val redisHost = sys.env.getOrElse("REDIS_HOST", "redis")
    
    println("[INFO] Compliance Notification Router Service Starting")
    println(s"[INFO] Kafka: $kafkaBootstrap")
    println(s"[INFO] Redis: $redisHost:6379")
    
    // Load notification config
    val config = loadConfig()
    println(s"[INFO] Loaded notification config")
    println(s"      Override recipient: ${config.override_recipient}")
    println(s"      Template: ${config.template}")
    println(s"      Rules: ${config.rules.keys.mkString(", ")}")
    
    // Redis connection for deduplication
    val jedisPool = new JedisPool(redisHost, 6379)
    
    // Consumer for processed.wwcc.status
    val consumerProps = new Properties()
    consumerProps.put("bootstrap.servers", kafkaBootstrap)
    consumerProps.put("group.id", "notification-router-v1")
    consumerProps.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    consumerProps.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    consumerProps.put("auto.offset.reset", "earliest")
    consumerProps.put("enable.auto.commit", "true")
    
    val consumer = new KafkaConsumer[String, String](consumerProps)
    consumer.subscribe(List("processed.wwcc.status").asJava)
    
    // Producer for notification commands
    val producerProps = new Properties()
    producerProps.put("bootstrap.servers", kafkaBootstrap)
    producerProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    producerProps.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    producerProps.put("acks", "all")
    
    val producer = new KafkaProducer[String, String](producerProps)
    
    println("[INFO] Service ready, consuming from processed.wwcc.status...")
    
    while (true) {
      val jedis = jedisPool.getResource
      try {
        val records = consumer.poll(Duration.ofMillis(1000))
        
        records.asScala.foreach { record =>
          decode[WwccCompliance](record.value()) match {
            case Right(compliance) =>
              // Only process non-compliant statuses
              if (compliance.compliance_status != "COMPLIANT") {
                // Check if we've already notified for this user/status in the last 24 hours
                val dedupKey = s"notification:${compliance.userId}:${compliance.compliance_status}"
                val alreadyNotified = jedis.exists(dedupKey)
                
                if (!alreadyNotified) {
                  // Get priority from config rules, default to MEDIUM
                  val priority = config.rules.get(compliance.compliance_status)
                    .map(_.priority)
                    .getOrElse("MEDIUM")
                  
                  // Create notification command
                  val notificationId = java.util.UUID.randomUUID().toString
                  val userName = s"${compliance.firstName} ${compliance.lastName}"
                  
                  val notificationCommand = NotificationCommand(
                    notificationId = notificationId,
                    userId = compliance.userId,
                    userName = userName,
                    email = config.override_recipient,  // Always use override for now
                    issueType = compliance.compliance_status,
                    priority = priority,
                    template = config.template,
                    data = NotificationData(
                      wwccNumber = compliance.wwccNumber,
                      expiryDate = compliance.expiryDate,
                      daysUntilExpiry = compliance.daysUntilExpiry
                    ),
                    createdAt = Instant.now().toString
                  )
                  
                  try {
                    // Publish notification command
                    val metadata = producer.send(new ProducerRecord[String, String](
                      "commands.notifications",
                      notificationId,
                      notificationCommand.asJson.noSpaces
                    )).get() // Block until send completes
                    
                    // Mark as notified in Redis (24 hour expiry)
                    jedis.setex(dedupKey, 86400, "sent")
                    
                    println(s"[INFO] Created notification: $notificationId")
                    println(s"      User: $userName (${compliance.userId})")
                    println(s"      Issue: ${compliance.compliance_status} (priority: $priority)")
                    println(s"      Email: ${config.override_recipient}")
                    println(s"      Published to partition ${metadata.partition()} at offset ${metadata.offset()}")
                  } catch {
                    case e: Exception =>
                      println(s"[ERROR] Failed to publish notification $notificationId: ${e.getMessage}")
                      e.printStackTrace()
                  }
                } else {
                  println(s"[DEBUG] Skipping duplicate notification for ${compliance.userId}:${compliance.compliance_status}")
                }
              } else {
                println(s"[DEBUG] Skipping COMPLIANT status for ${compliance.userId}")
              }
              
            case Left(e) =>
              println(s"[WARN] Failed to parse compliance status: ${e.getMessage}")
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
