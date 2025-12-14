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
import redis.clients.jedis.{Jedis, JedisPool}
import java.security.MessageDigest

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
  compliance_status: String,
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
  compliance_statuses: Option[List[String]],
  flags_any: Option[List[String]],
  flags_all: Option[List[String]],
  days_until_expiry_less_than: Option[Int],
  days_since_start_greater_than: Option[Int]
)

case class NotificationConfig(
  priority: String,
  issue_type: String,
  template: String,
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
  
  def md5Hash(text: String): String = {
    val md = MessageDigest.getInstance("MD5")
    md.digest(text.getBytes).map("%02x".format(_)).mkString
  }
  
  def getNotificationWindow(priority: String): Int = {
    priority match {
      case "HIGH" => 86400      // 24 hours for high priority
      case "MEDIUM" => 259200   // 3 days for medium priority  
      case "LOW" => 604800      // 7 days for low priority
      case _ => 86400           // Default 24 hours
    }
  }
  
  def main(args: Array[String]): Unit = {
    val kafkaBootstrap = sys.env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    val redisHost = sys.env.getOrElse("REDIS_HOST", "redis")
    
    println("[INFO] Compliance Monitor Service Starting")
    println(s"[INFO] Kafka: $kafkaBootstrap")
    println(s"[INFO] Redis: $redisHost:6379")
    
    // Redis connection
    val jedisPool = new JedisPool(redisHost, 6379)
    
    // Status consumer
    val statusConsumerProps = new Properties()
    statusConsumerProps.put("bootstrap.servers", kafkaBootstrap)
    statusConsumerProps.put("group.id", "compliance-monitor-v2")
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
            val jedis = jedisPool.getResource
            try {
              decode[WwccCompliance](record.value()) match {
                case Right(compliance) =>
                  // Check each active rule
                  rulesConfig.rules.filter(_.enabled).foreach { rule =>
                    if (evaluateRule(compliance, rule)) {
                      // Create a unique key for this issue type and user
                      val issueKey = s"issue:${rule.ruleId}:${compliance.userId}"
                      val notificationWindow = getNotificationWindow(rule.notification.priority)
                      
                      // Check if we've already raised this issue recently
                      if (!jedis.exists(issueKey)) {
                        val issue = createComplianceIssue(compliance, rule)
                        
                        producer.send(new ProducerRecord[String, String](
                          "events.compliance.issues",
                          compliance.userId,
                          issue.asJson.noSpaces
                        ))
                        
                        // Mark this issue as raised with appropriate TTL based on priority
                        jedis.setex(issueKey, notificationWindow, issue.issueId)
                        
                        println(s"[INFO] Compliance issue detected: ${compliance.firstName} ${compliance.lastName}")
                        println(s"      Rule: ${rule.name}")
                        println(s"      Type: ${issue.issueType} (${issue.severity})")
                        println(s"      Status: ${compliance.compliance_status}")
                        println(s"      Next notification allowed in: ${notificationWindow/3600} hours")
                        if (compliance.flags.nonEmpty) {
                          println(s"      Flags: ${compliance.flags.mkString(", ")}")
                        }
                      } else {
                        // Issue already raised recently
                        val ttl = jedis.ttl(issueKey)
                        if (ttl > 0) {
                          println(s"[DEBUG] Skipping duplicate issue for ${compliance.userId} - ${rule.name} (${ttl/3600}h remaining)")
                        }
                      }
                    }
                  }
                  
                  // Store last seen compliance state for this user
                  val complianceStateKey = s"compliance:state:${compliance.userId}"
                  jedis.setex(complianceStateKey, 86400, compliance.asJson.noSpaces)
                  
                case Left(e) =>
                  println(s"[WARN] Failed to parse compliance record: ${e.getMessage}")
              }
              
            } catch {
              case e: Exception =>
                println(s"[ERROR] Error processing compliance record: ${e.getMessage}")
            } finally {
              jedis.close()
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
      val partitions = consumer.partitionsFor("reference.compliance.rules")
      if (partitions.isEmpty) {
        println("[WARN] No partitions found for reference.compliance.rules topic")
        return None
      }
      
      val topicPartition = new org.apache.kafka.common.TopicPartition("reference.compliance.rules", 0)
      consumer.assign(java.util.Arrays.asList(topicPartition))
      consumer.seekToBeginning(java.util.Arrays.asList(topicPartition))
      
      var latestRules: Option[ComplianceRulesConfig] = None
      var keepPolling = true
      
      while (keepPolling) {
        val records = consumer.poll(Duration.ofMillis(1000))
        
        if (records.isEmpty) {
          keepPolling = false
        } else {
          records.asScala.foreach { record =>
            val value = record.value()
            if (value != null && value.trim.length > 10) {
              decode[ComplianceRulesConfig](value) match {
                case Right(rules) => 
                  latestRules = Some(rules)
                  println(s"[DEBUG] Loaded rules from offset ${record.offset()}")
                case Left(e) =>
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
  
  def evaluateRule(compliance: WwccCompliance, rule: ComplianceRule): Boolean = {
    val conditions = rule.conditions
    
    val statusMatch = conditions.compliance_statuses match {
      case Some(statuses) => statuses.contains(compliance.compliance_status)
      case None => true
    }
    
    val flagsAnyMatch = conditions.flags_any match {
      case Some(requiredFlags) => requiredFlags.exists(compliance.flags.contains)
      case None => true
    }
    
    val flagsAllMatch = conditions.flags_all match {
      case Some(requiredFlags) => requiredFlags.forall(compliance.flags.contains)
      case None => true
    }
    
    val expiryMatch = conditions.days_until_expiry_less_than match {
      case Some(days) => compliance.daysUntilExpiry.exists(_ < days)
      case None => true
    }
    
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