package com.council.compliance

import org.apache.kafka.clients.consumer.{KafkaConsumer, ConsumerRecord}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import java.util.Properties
import java.time.{Duration, Instant, LocalDateTime}
import scala.jdk.CollectionConverters._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import scala.collection.mutable
import org.apache.kafka.common.TopicPartition


// Input models from processed.wwcc.status
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
  compliance_status: String,  // COMPLIANT, EXPIRED, EXPIRING, NOT_APPROVED, MISSING, NOT_STARTED, UNEXPECTED
  flags: List[String],
  processedAt: String
)

// Compliance rule configuration
case class ComplianceRule(
  ruleId: String,
  name: String,
  description: String,
  enabled: Boolean,
  conditions: RuleConditions,
  notification: NotificationConfig
)

case class RuleConditions(
  compliance_statuses: Option[List[String]],  // List of statuses that trigger this rule
  flags_any: Option[List[String]],           // Trigger if ANY of these flags present
  flags_all: Option[List[String]],           // Trigger if ALL of these flags present
  days_until_expiry_less_than: Option[Int],  // Trigger if expiry within X days
  days_since_start_greater_than: Option[Int] // Trigger if started more than X days ago
)

case class NotificationConfig(
  priority: String,      // HIGH, MEDIUM, LOW
  issue_type: String,    // EXPIRED, EXPIRING_SOON, MISSING, NOT_APPROVED, etc.
  template: String,      // Email template to use
  notify_employee: Boolean,
  notify_manager: Boolean,
  notify_compliance_team: Boolean
)

case class ComplianceRulesConfig(
  rules: List[ComplianceRule],
  default_compliance_email: String,
  timestamp: String
)

// Output model for events.compliance.issues
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

object ComplianceMonitorService {
  
  def main(args: Array[String]): Unit = {
    val kafkaBootstrap = sys.env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    
    println("[INFO] Compliance Monitor Service Starting")
    println(s"[INFO] Kafka: $kafkaBootstrap")
    
    // Rules consumer (reads the entire topic from beginning each time to get latest config)
    val rulesConsumerProps = new Properties()
    rulesConsumerProps.put("bootstrap.servers", kafkaBootstrap)
    rulesConsumerProps.put("group.id", s"compliance-monitor-rules-${java.util.UUID.randomUUID()}")
    rulesConsumerProps.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    rulesConsumerProps.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    rulesConsumerProps.put("auto.offset.reset", "earliest")
    rulesConsumerProps.put("enable.auto.commit", "false")
    
    // Status consumer
    val statusConsumerProps = new Properties()
    statusConsumerProps.put("bootstrap.servers", kafkaBootstrap)
    statusConsumerProps.put("group.id", "compliance-monitor-v1")
    statusConsumerProps.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    statusConsumerProps.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    statusConsumerProps.put("auto.offset.reset", "earliest")
    statusConsumerProps.put("enable.auto.commit", "true")
    
    val statusConsumer = new KafkaConsumer[String, String](statusConsumerProps)
    statusConsumer.subscribe(List("processed.wwcc.status").asJava)
    
    // Producer for compliance issues
    val producerProps = new Properties()
    producerProps.put("bootstrap.servers", kafkaBootstrap)
    producerProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    producerProps.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    producerProps.put("acks", "all")
    
    val producer = new KafkaProducer[String, String](producerProps)
    
    // State tracking
    var currentRules: Option[ComplianceRulesConfig] = None
    val processedIssues = mutable.Map[String, String]() // userId -> last issue hash
    var lastRuleCheck = System.currentTimeMillis()
    
    println("[INFO] Service ready, waiting for compliance rules...")
    
    // Load initial rules
    currentRules = loadLatestRules(kafkaBootstrap)
    currentRules.foreach { config =>
      println(s"[INFO] Loaded ${config.rules.count(_.enabled)} active compliance rules")
      config.rules.filter(_.enabled).foreach { rule =>
        println(s"  - ${rule.name}: ${rule.description}")
      }
    }
    
    while (true) {
      try {
        // Reload rules periodically (every 30 seconds)
        if (System.currentTimeMillis() - lastRuleCheck > 30000) {
          val newRules = loadLatestRules(kafkaBootstrap)
          if (newRules != currentRules) {
            currentRules = newRules
            currentRules.foreach { config =>
              println(s"[INFO] Reloaded ${config.rules.count(_.enabled)} active compliance rules")
            }
          }
          lastRuleCheck = System.currentTimeMillis()
        }
        
        // Process WWCC status updates
        val records = statusConsumer.poll(Duration.ofMillis(1000))
        
        if (!records.isEmpty && currentRules.isDefined) {
          val rulesConfig = currentRules.get
          
          records.asScala.foreach { record =>
            try {
              decode[WwccCompliance](record.value()) match {
                case Right(compliance) =>
                  // Check each active rule
                  rulesConfig.rules.filter(_.enabled).foreach { rule =>
                    if (evaluateRule(compliance, rule)) {
                      val issueHash = s"${compliance.userId}-${rule.ruleId}-${compliance.compliance_status}"
                      
                      // Only create issue if status changed or not seen before
                      if (!processedIssues.contains(compliance.userId) || 
                          processedIssues(compliance.userId) != issueHash) {
                        
                        val issue = createComplianceIssue(compliance, rule)
                        
                        producer.send(new ProducerRecord[String, String](
                          "events.compliance.issues",
                          compliance.userId,
                          issue.asJson.noSpaces
                        ))
                        
                        processedIssues(compliance.userId) = issueHash
                        
                        println(s"[INFO] Compliance issue detected: ${compliance.firstName} ${compliance.lastName}")
                        println(s"      Rule: ${rule.name}")
                        println(s"      Type: ${issue.issueType} (${issue.severity})")
                        println(s"      Status: ${compliance.compliance_status}")
                        if (compliance.flags.nonEmpty) {
                          println(s"      Flags: ${compliance.flags.mkString(", ")}")
                        }
                      }
                    }
                  }
                  
                case Left(e) =>
                  println(s"[WARN] Failed to parse compliance record: ${e.getMessage}")
              }
            } catch {
              case e: Exception =>
                println(s"[ERROR] Error processing compliance record: ${e.getMessage}")
            }
          }
        } else if (currentRules.isEmpty && !records.isEmpty) {
          println("[WARN] Received status updates but no compliance rules configured")
        }
        
      } catch {
        case e: Exception =>
          println(s"[ERROR] Unexpected error: ${e.getMessage}")
          e.printStackTrace()
          Thread.sleep(5000)
      }
    }
  }
  
  def loadLatestRules(kafkaBootstrap: String): Option[ComplianceRulesConfig] = {
    val props = new Properties()
    props.put("bootstrap.servers", kafkaBootstrap)
    props.put("group.id", s"compliance-monitor-rules-temp-${java.util.UUID.randomUUID()}")
    props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    props.put("auto.offset.reset", "earliest")
    props.put("enable.auto.commit", "false")
    
    val consumer = new KafkaConsumer[String, String](props)
    
    try {
      // Get partition info
      val partitions = consumer.partitionsFor("reference.compliance.rules")
      if (partitions.isEmpty) {
        println("[WARN] No partitions found for reference.compliance.rules topic")
        return None
      }
      
      // Assign to partition 0
      val topicPartition = new org.apache.kafka.common.TopicPartition("reference.compliance.rules", 0)
      consumer.assign(java.util.Arrays.asList(topicPartition))
      
      // Seek to beginning to read all messages
      consumer.seekToBeginning(java.util.Arrays.asList(topicPartition))
      
      var latestRules: Option[ComplianceRulesConfig] = None
      var keepPolling = true
      
      // Read ALL messages and keep only the last valid one
      while (keepPolling) {
        val records = consumer.poll(Duration.ofMillis(1000))
        
        if (records.isEmpty) {
          keepPolling = false
        } else {
          // Process all records, keeping only successfully parsed ones
          records.asScala.foreach { record =>
            val value = record.value()
            // Skip empty or very short messages (the broken ones)
            if (value != null && value.trim.length > 10) {
              decode[ComplianceRulesConfig](value) match {
                case Right(rules) => 
                  latestRules = Some(rules)
                  println(s"[INFO] Loaded rules from offset ${record.offset()}")
                case Left(e) =>
                  // Skip broken messages silently unless they look like they should be valid
                  if (value.contains("rules") && value.length > 100) {
                    println(s"[WARN] Failed to parse rules at offset ${record.offset()}: ${e.getMessage.take(100)}")
                  }
              }
            }
          }
        }
      }
      
      latestRules match {
        case Some(rules) =>
          println(s"[INFO] Successfully loaded ${rules.rules.size} rules (${rules.rules.count(_.enabled)} enabled)")
        case None =>
          println("[WARN] No valid rules found in topic")
      }
      
      latestRules
      
    } catch {
      case e: Exception =>
        println(s"[ERROR] Failed to load rules: ${e.getMessage}")
        e.printStackTrace()
        None
    } finally {
      consumer.close()
    }
  }
  
  def createRulesConsumerProps(kafkaBootstrap: String): Properties = {
    val props = new Properties()
    props.put("bootstrap.servers", kafkaBootstrap)
    props.put("group.id", s"compliance-monitor-rules-temp-${java.util.UUID.randomUUID()}")
    props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    props.put("auto.offset.reset", "earliest")
    props.put("enable.auto.commit", "false")
    props
  }
  
  def evaluateRule(compliance: WwccCompliance, rule: ComplianceRule): Boolean = {
    val conditions = rule.conditions
    
    // Check compliance status
    val statusMatch = conditions.compliance_statuses match {
      case Some(statuses) => statuses.contains(compliance.compliance_status)
      case None => true
    }
    
    // Check flags (ANY)
    val flagsAnyMatch = conditions.flags_any match {
      case Some(requiredFlags) => requiredFlags.exists(compliance.flags.contains)
      case None => true
    }
    
    // Check flags (ALL)
    val flagsAllMatch = conditions.flags_all match {
      case Some(requiredFlags) => requiredFlags.forall(compliance.flags.contains)
      case None => true
    }
    
    // Check days until expiry
    val expiryMatch = conditions.days_until_expiry_less_than match {
      case Some(days) => compliance.daysUntilExpiry.exists(_ < days)
      case None => true
    }
    
    // Check days since start
    val startMatch = conditions.days_since_start_greater_than match {
      case Some(days) => compliance.daysSinceStart.exists(_ > days)
      case None => true
    }
    
    statusMatch && flagsAnyMatch && flagsAllMatch && expiryMatch && startMatch
  }
  
  def createComplianceIssue(compliance: WwccCompliance, rule: ComplianceRule): ComplianceIssue = {
    val issueId = s"${java.util.UUID.randomUUID()}"
    
    val description = buildDescription(compliance, rule)
    
    ComplianceIssue(
      issueId = issueId,
      userId = compliance.userId,
      userName = s"${compliance.firstName} ${compliance.lastName}",
      email = compliance.email,
      department = compliance.department,
      position = compliance.position,
      issueType = rule.notification.issue_type,
      severity = rule.notification.priority,
      description = description,
      detectedAt = Instant.now().toString,
      wwccNumber = compliance.wwccNumber,
      expiryDate = compliance.expiryDate,
      daysUntilExpiry = compliance.daysUntilExpiry,
      compliance_status = compliance.compliance_status,
      flags = compliance.flags,
      triggeredRule = rule.ruleId,
      notificationRequired = true,
      notificationConfig = rule.notification
    )
  }
  
  def buildDescription(compliance: WwccCompliance, rule: ComplianceRule): String = {
    compliance.compliance_status match {
      case "EXPIRED" =>
        s"WWCC has expired${compliance.expiryDate.map(d => s" on $d").getOrElse("")}. Immediate action required."
      
      case "EXPIRING" =>
        s"WWCC expiring in ${compliance.daysUntilExpiry.getOrElse("unknown")} days. Renewal required."
      
      case "MISSING" =>
        s"No WWCC found in system. Employee started ${compliance.daysSinceStart.getOrElse("unknown")} days ago."
      
      case "NOT_APPROVED" =>
        s"WWCC document uploaded but not approved. Approval status: ${compliance.approval_status}"
      
      case "NOT_STARTED" =>
        s"Employee scheduled to start on ${compliance.startDate.getOrElse("unknown date")}. WWCC required before start date."
      
      case "UNEXPECTED" =>
        s"WWCC found for employee not in required list. Review required."
      
      case _ =>
        s"Compliance issue detected: ${rule.description}"
    }
  }
}