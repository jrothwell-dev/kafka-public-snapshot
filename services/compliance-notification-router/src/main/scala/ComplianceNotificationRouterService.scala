package com.council.notification

import org.apache.kafka.clients.consumer.{KafkaConsumer, ConsumerRecord}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import java.util.Properties
import java.time.{Duration, Instant}
import scala.jdk.CollectionConverters._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import io.circe.{Encoder, Decoder}
import NotificationCodecs._

// Input model from events.compliance.issues
case class ComplianceIssue(
  issueId: String,
  userId: String,
  userName: String,
  email: Option[String],
  department: Option[String],
  position: Option[String],
  issueType: String,
  severity: String,
  description: String,
  detectedAt: String,
  wwccNumber: Option[String],
  expiryDate: Option[String],
  daysUntilExpiry: Option[Long],
  compliance_status: String,
  flags: List[String],
  triggeredRule: String,
  notificationRequired: Boolean,
  notificationConfig: NotificationConfig
)

case class NotificationConfig(
  priority: String,
  issue_type: String,
  template: String,
  notify_employee: Boolean,
  notify_manager: Boolean,
  notify_compliance_team: Boolean
)

// Output model for commands.notifications
sealed trait NotificationType
object NotificationType {
  case object Email extends NotificationType
  case object SMS extends NotificationType
  case object Slack extends NotificationType
  
  def fromString(s: String): Option[NotificationType] = s.toLowerCase match {
    case "email" => Some(Email)
    case "sms" => Some(SMS)
    case "slack" => Some(Slack)
    case _ => None
  }
  
  def toString(nt: NotificationType): String = nt match {
    case Email => "email"
    case SMS => "sms"
    case Slack => "slack"
  }
}

sealed trait Priority
object Priority {
  case object Low extends Priority
  case object Normal extends Priority
  case object High extends Priority
  case object Critical extends Priority
  
  def fromString(s: String): Priority = s.toLowerCase match {
    case "low" => Low
    case "normal" => Normal
    case "high" => High
    case "critical" => Critical
    case _ => Normal
  }
  
  def toString(p: Priority): String = p match {
    case Low => "low"
    case Normal => "normal"
    case High => "high"
    case Critical => "critical"
  }
}

// Implicit codecs for JSON serialization
object NotificationCodecs {
  implicit val notificationTypeEncoder: Encoder[NotificationType] = 
    Encoder.encodeString.contramap(NotificationType.toString)
  
  implicit val notificationTypeDecoder: Decoder[NotificationType] = 
    Decoder.decodeString.emap(s => 
      NotificationType.fromString(s).toRight(s"Invalid notification type: $s")
    )
  
  implicit val priorityEncoder: Encoder[Priority] = Encoder.encodeString.contramap(Priority.toString)
  
  implicit val priorityDecoder: Decoder[Priority] = 
    Decoder.decodeString.map(Priority.fromString)
  
  implicit val instantEncoder: Encoder[Instant] = Encoder.encodeString.contramap(_.toString)
  implicit val instantDecoder: Decoder[Instant] = Decoder.decodeString.map(Instant.parse)
}

case class NotificationRequest(
  id: String,
  notificationType: NotificationType,
  template: String,
  recipients: Seq[String],
  data: Map[String, String],
  priority: Priority,
  requestedBy: String,
  requestedAt: Instant
)

object ComplianceNotificationRouterService {
  
  def main(args: Array[String]): Unit = {
    val kafkaBootstrap = sys.env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    
    println("[INFO] Compliance Notification Router Service Starting")
    println(s"[INFO] Kafka: $kafkaBootstrap")
    
    // Consumer for compliance issues
    val consumerProps = new Properties()
    consumerProps.put("bootstrap.servers", kafkaBootstrap)
    consumerProps.put("group.id", "notification-router-issues-v1")
    consumerProps.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    consumerProps.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    consumerProps.put("auto.offset.reset", "earliest")
    consumerProps.put("enable.auto.commit", "true")
    
    val consumer = new KafkaConsumer[String, String](consumerProps)
    consumer.subscribe(List("events.compliance.issues").asJava)
    
    // Producer for notification requests
    val producerProps = new Properties()
    producerProps.put("bootstrap.servers", kafkaBootstrap)
    producerProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    producerProps.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    producerProps.put("acks", "all")
    
    val producer = new KafkaProducer[String, String](producerProps)
    
    println("[INFO] Service ready, consuming from events.compliance.issues...")
    
    while (true) {
      try {
        val records = consumer.poll(Duration.ofMillis(1000))
        
        records.asScala.foreach { record =>
          decode[ComplianceIssue](record.value()) match {
            case Right(issue) =>
              if (issue.notificationRequired) {
                createNotificationRequest(issue) match {
                  case Some(notificationRequest) =>
                    try {
                      // Wait for send to complete to ensure message is persisted
                      val metadata = producer.send(new ProducerRecord[String, String](
                        "commands.notifications",
                        notificationRequest.id,
                        notificationRequest.asJson.noSpaces
                      )).get() // Block until send completes
                      
                      println(s"[INFO] Created notification request: ${notificationRequest.id}")
                      println(s"      Issue: ${issue.issueType} (${issue.severity})")
                      println(s"      User: ${issue.userName} (${issue.userId})")
                      println(s"      Recipients: ${notificationRequest.recipients.mkString(", ")}")
                      println(s"      Template: ${notificationRequest.template}")
                      println(s"      Published to partition ${metadata.partition()} at offset ${metadata.offset()}")
                    } catch {
                      case e: Exception =>
                        println(s"[ERROR] Failed to publish notification request ${notificationRequest.id}: ${e.getMessage}")
                        e.printStackTrace()
                    }
                  case None =>
                    println(s"[WARN] Skipping notification for issue ${issue.issueId}: No recipients configured")
                    println(s"      notify_employee: ${issue.notificationConfig.notify_employee}, email: ${issue.email.isDefined}")
                    println(s"      notify_manager: ${issue.notificationConfig.notify_manager}")
                    println(s"      notify_compliance_team: ${issue.notificationConfig.notify_compliance_team}")
                }
              } else {
                println(s"[DEBUG] Issue ${issue.issueId} does not require notification")
              }
              
            case Left(e) =>
              println(s"[WARN] Failed to parse compliance issue: ${e.getMessage}")
          }
        }
        
      } catch {
        case e: Exception =>
          println(s"[ERROR] Unexpected error: ${e.getMessage}")
          e.printStackTrace()
          Thread.sleep(5000)
      }
    }
  }
  
  def createNotificationRequest(issue: ComplianceIssue): Option[NotificationRequest] = {
    val recipients = buildRecipients(issue)
    
    // Validate that we have at least one recipient
    if (recipients.isEmpty) {
      return None
    }
    
    val templateData = buildTemplateData(issue)
    
    Some(NotificationRequest(
      id = java.util.UUID.randomUUID().toString,
      notificationType = NotificationType.Email, // Default to email for now
      template = issue.notificationConfig.template,
      recipients = recipients,
      data = templateData,
      priority = Priority.fromString(issue.notificationConfig.priority),
      requestedBy = "compliance-notification-router",
      requestedAt = Instant.now()
    ))
  }
  
  def buildRecipients(issue: ComplianceIssue): Seq[String] = {
    val config = issue.notificationConfig
    var recipients = scala.collection.mutable.Set[String]()
    
    // Add employee email if configured and available
    if (config.notify_employee && issue.email.isDefined) {
      recipients += issue.email.get
    }
    
    // Add manager email if configured
    if (config.notify_manager) {
      recipients += "manager@murrumbidgee.nsw.gov.au"
    }
    
    // Add compliance team email if configured
    if (config.notify_compliance_team) {
      recipients += "compliance@murrumbidgee.nsw.gov.au"
    }
    
    recipients.toSeq
  }
  
  def buildTemplateData(issue: ComplianceIssue): Map[String, String] = {
    Map(
      "userName" -> issue.userName,
      "email" -> issue.email.getOrElse(""),
      "issueType" -> issue.issueType,
      "severity" -> issue.severity,
      "description" -> issue.description,
      "wwccNumber" -> issue.wwccNumber.getOrElse(""),
      "expiryDate" -> issue.expiryDate.getOrElse(""),
      "daysUntilExpiry" -> issue.daysUntilExpiry.map(_.toString).getOrElse(""),
      "department" -> issue.department.getOrElse(""),
      "position" -> issue.position.getOrElse(""),
      "detectedAt" -> issue.detectedAt
    )
  }
}
