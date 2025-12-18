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
import org.yaml.snakeyaml.Yaml
import java.io.{File, FileInputStream}

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

// Notification config models
case class NotificationSettings(
  dedupTtlHours: Int,
  overrideRecipient: Option[String]
)

case class IssueTypeConfig(
  priority: String,
  template: String
)

case class FrequencyRule(
  name: String,
  condition: String,
  intervalHours: Int
)

case class DigestConfig(
  enabled: Boolean,
  dayOfWeek: String,
  time: String,
  timezone: String
)

case class NotificationConfig(
  settings: NotificationSettings,
  ccRecipients: Seq[String],
  issueTypes: Map[String, IssueTypeConfig],
  frequencyRules: Seq[FrequencyRule],
  departmentManagers: Map[String, String],
  defaultManager: String,
  digest: DigestConfig
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
  to: Seq[String],
  cc: Option[Seq[String]],
  bcc: Option[Seq[String]],
  subject: String,
  issueType: String,
  priority: String,
  template: String,
  isHtml: Boolean,
  data: NotificationData,
  createdAt: String
)

object ComplianceNotificationRouterService {
  
  def loadConfigFromYaml(configPath: String): NotificationConfig = {
    val yaml = new Yaml()
    val file = new File(configPath)
    
    if (!file.exists()) {
      println(s"[WARN] Config file not found at $configPath, using defaults")
      return defaultConfig()
    }
    
    val input = new FileInputStream(file)
    try {
      val data = yaml.load[java.util.Map[String, Any]](input).asScala
      parseConfig(data.toMap)
    } finally {
      input.close()
    }
  }
  
  def parseConfig(data: Map[String, Any]): NotificationConfig = {
    val settingsMap = data.getOrElse("settings", new java.util.HashMap[String, Any]())
      .asInstanceOf[java.util.Map[String, Any]].asScala.toMap
    
    val settings = NotificationSettings(
      dedupTtlHours = settingsMap.getOrElse("dedup_ttl_hours", 24).asInstanceOf[Int],
      overrideRecipient = Option(settingsMap.get("override_recipient")).flatten.map(_.toString)
    )
    
    val ccRecipients = Option(data.get("cc_recipients"))
      .flatten
      .map(_.asInstanceOf[java.util.List[String]].asScala.toSeq)
      .getOrElse(Seq.empty)
    
    val issueTypesRaw = data.getOrElse("issue_types", new java.util.HashMap[String, Any]())
      .asInstanceOf[java.util.Map[String, Any]].asScala.toMap
    
    val issueTypes = issueTypesRaw.map { case (k, v) =>
      val m = v.asInstanceOf[java.util.Map[String, Any]].asScala
      k -> IssueTypeConfig(
        priority = m.getOrElse("priority", "MEDIUM").toString,
        template = m.getOrElse("template", "individual-alert.html").toString
      )
    }
    
    val frequencyRulesRaw = Option(data.get("frequency_rules"))
      .flatten
      .map(_.asInstanceOf[java.util.List[Any]].asScala.toSeq)
      .getOrElse(Seq.empty)
    
    val frequencyRules = frequencyRulesRaw.map { r =>
      val m = r.asInstanceOf[java.util.Map[String, Any]].asScala
      FrequencyRule(
        name = m.getOrElse("name", "").toString,
        condition = m.getOrElse("condition", "always").toString,
        intervalHours = m.getOrElse("interval_hours", 24).asInstanceOf[Int]
      )
    }
    
    val departmentManagersRaw = data.getOrElse("department_managers", new java.util.HashMap[String, String]())
      .asInstanceOf[java.util.Map[String, String]].asScala.toMap
    
    val defaultManager = data.getOrElse("default_manager", "").toString
    
    val digestRaw = data.getOrElse("digest", new java.util.HashMap[String, Any]())
      .asInstanceOf[java.util.Map[String, Any]].asScala.toMap
    
    val digest = DigestConfig(
      enabled = digestRaw.getOrElse("enabled", false).asInstanceOf[Boolean],
      dayOfWeek = digestRaw.getOrElse("day_of_week", "MONDAY").toString,
      time = digestRaw.getOrElse("time", "09:00").toString,
      timezone = digestRaw.getOrElse("timezone", "Australia/Sydney").toString
    )
    
    NotificationConfig(
      settings = settings,
      ccRecipients = ccRecipients,
      issueTypes = issueTypes,
      frequencyRules = frequencyRules,
      departmentManagers = departmentManagersRaw,
      defaultManager = defaultManager,
      digest = digest
    )
  }
  
  def defaultConfig(): NotificationConfig = {
    NotificationConfig(
      settings = NotificationSettings(dedupTtlHours = 24, overrideRecipient = None),
      ccRecipients = Seq.empty,
      issueTypes = Map(
        "EXPIRED" -> IssueTypeConfig("HIGH", "individual-alert.html"),
        "EXPIRING" -> IssueTypeConfig("MEDIUM", "individual-alert.html"),
        "MISSING" -> IssueTypeConfig("HIGH", "individual-alert.html"),
        "NOT_APPROVED" -> IssueTypeConfig("MEDIUM", "individual-alert.html")
      ),
      frequencyRules = Seq.empty,
      departmentManagers = Map.empty,
      defaultManager = "",
      digest = DigestConfig(enabled = false, "MONDAY", "09:00", "Australia/Sydney")
    )
  }
  
  // Pure function: determines if a notification should be sent for a given status
  def shouldNotify(status: String): Boolean = {
    status != "COMPLIANT"
  }
  
  // Pure function: generates deduplication key for a user and status
  def dedupKey(userId: String, status: String): String = {
    s"notification:last:${userId}:${status}"
  }
  
  // Pure function: gets priority for a status from config, defaults to MEDIUM
  def getPriority(status: String, config: NotificationConfig): String = {
    config.issueTypes.get(status)
      .map(_.priority)
      .getOrElse("MEDIUM")
  }
  
  // Pure function: evaluates a frequency condition against days until expiry
  def evaluateCondition(condition: String, daysUntilExpiry: Option[Long]): Boolean = {
    condition match {
      case "always" => true
      case c if c.startsWith("days_until_expiry") =>
        daysUntilExpiry match {
          case Some(days) =>
            if (c.contains("<=")) {
              val threshold = c.split("<=").last.trim.toInt
              days <= threshold
            } else if (c.contains("<")) {
              val threshold = c.split("<").last.trim.toInt
              days < threshold
            } else if (c.contains(">=")) {
              val threshold = c.split(">=").last.trim.toInt
              days >= threshold
            } else if (c.contains(">")) {
              val threshold = c.split(">").last.trim.toInt
              days > threshold
            } else {
              false
            }
          case None => false  // Can't evaluate without days
        }
      case _ => false
    }
  }
  
  // Pure function: finds the matching frequency rule and returns interval in seconds
  def getNotificationIntervalSeconds(
    daysUntilExpiry: Option[Long],
    frequencyRules: Seq[FrequencyRule],
    defaultIntervalHours: Int = 24
  ): Int = {
    frequencyRules
      .find(rule => evaluateCondition(rule.condition, daysUntilExpiry))
      .map(_.intervalHours * 3600)
      .getOrElse(defaultIntervalHours * 3600)
  }
  
  // Function to check if enough time has passed since last notification
  def shouldSendNotification(
    jedis: Jedis,
    userId: String,
    status: String,
    daysUntilExpiry: Option[Long],
    frequencyRules: Seq[FrequencyRule]
  ): Boolean = {
    val key = dedupKey(userId, status)
    val lastSentStr = jedis.get(key)
    
    if (lastSentStr == null) {
      true  // Never sent, should send
    } else {
      val lastSent = Instant.parse(lastSentStr)
      val intervalSeconds = getNotificationIntervalSeconds(daysUntilExpiry, frequencyRules)
      val nextAllowedTime = lastSent.plusSeconds(intervalSeconds)
      Instant.now().isAfter(nextAllowedTime)
    }
  }
  
  // Update the dedup storage to store timestamp instead of just "sent"
  def markNotificationSent(jedis: Jedis, userId: String, status: String, ttlSeconds: Int): Unit = {
    val key = dedupKey(userId, status)
    jedis.setex(key, ttlSeconds, Instant.now().toString)
  }
  
  // Pure function: creates a notification command from compliance data and config
  def createNotificationCommand(
    compliance: WwccCompliance,
    config: NotificationConfig,
    notificationId: String,
    createdAt: String
  ): NotificationCommand = {
    val userName = s"${compliance.firstName} ${compliance.lastName}"
    val issueConfig = config.issueTypes.getOrElse(compliance.compliance_status, 
      IssueTypeConfig("MEDIUM", "individual-alert.html"))
    
    // Determine recipients
    val toRecipients = config.settings.overrideRecipient match {
      case Some(overrideAddr) => Seq(overrideAddr)
      case None => compliance.email.toSeq
    }
    
    // Add CC recipients
    val ccRecipients = if (config.ccRecipients.nonEmpty) Some(config.ccRecipients) else None
    
    NotificationCommand(
      notificationId = notificationId,
      userId = compliance.userId,
      userName = userName,
      to = toRecipients,
      cc = ccRecipients,
      bcc = None,
      subject = s"WWCC Compliance Alert: ${compliance.compliance_status} - ${compliance.firstName} ${compliance.lastName}",
      issueType = compliance.compliance_status,
      priority = issueConfig.priority,
      template = issueConfig.template,
      isHtml = true,
      data = NotificationData(
        wwccNumber = compliance.wwccNumber,
        expiryDate = compliance.expiryDate,
        daysUntilExpiry = compliance.daysUntilExpiry
      ),
      createdAt = createdAt
    )
  }
  
  def main(args: Array[String]): Unit = {
    val kafkaBootstrap = sys.env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    val redisHost = sys.env.getOrElse("REDIS_HOST", "redis")
    
    println("[INFO] Compliance Notification Router Service Starting")
    println(s"[INFO] Kafka: $kafkaBootstrap")
    println(s"[INFO] Redis: $redisHost:6379")
    
    // Load notification config
    val configPath = sys.env.getOrElse("NOTIFICATION_CONFIG_PATH", "/app/config/notification-settings.yaml")
    val config = loadConfigFromYaml(configPath)
    println(s"[INFO] Loaded notification config from $configPath")
    config.settings.overrideRecipient.foreach(overrideAddr => 
      println(s"      Override recipient: $overrideAddr")
    )
    println(s"      Issue types: ${config.issueTypes.keys.mkString(", ")}")
    println(s"      CC recipients: ${config.ccRecipients.mkString(", ")}")
    println(s"      Dedup TTL: ${config.settings.dedupTtlHours} hours")
    
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
              if (shouldNotify(compliance.compliance_status)) {
                // Check if enough time has passed since last notification based on frequency rules
                val shouldSend = shouldSendNotification(
                  jedis,
                  compliance.userId,
                  compliance.compliance_status,
                  compliance.daysUntilExpiry,
                  config.frequencyRules
                )
                
                if (shouldSend) {
                  // Create notification command
                  val notificationId = java.util.UUID.randomUUID().toString
                  val notificationCommand = createNotificationCommand(
                    compliance,
                    config,
                    notificationId,
                    Instant.now().toString
                  )
                  
                  try {
                    // Publish notification command
                    val metadata = producer.send(new ProducerRecord[String, String](
                      "commands.notifications",
                      notificationId,
                      notificationCommand.asJson.noSpaces
                    )).get() // Block until send completes
                    
                    // Store with a long TTL (we check timestamps, not just existence)
                    markNotificationSent(jedis, compliance.userId, compliance.compliance_status, 86400 * 30)  // 30 days
                    
                    // Log which frequency rule matched
                    val matchedRule = config.frequencyRules
                      .find(rule => evaluateCondition(rule.condition, compliance.daysUntilExpiry))
                      .map(_.name)
                      .getOrElse("default")
                    
                    println(s"[INFO] Created notification: $notificationId (frequency rule: $matchedRule)")
                    println(s"      User: ${notificationCommand.userName} (${compliance.userId})")
                    println(s"      Issue: ${compliance.compliance_status} (priority: ${notificationCommand.priority})")
                    println(s"      To: ${notificationCommand.to.mkString(", ")}")
                    println(s"      Published to partition ${metadata.partition()} at offset ${metadata.offset()}")
                  } catch {
                    case e: Exception =>
                      println(s"[ERROR] Failed to publish notification $notificationId: ${e.getMessage}")
                      e.printStackTrace()
                  }
                } else {
                  val intervalHours = getNotificationIntervalSeconds(compliance.daysUntilExpiry, config.frequencyRules) / 3600
                  println(s"[DEBUG] Skipping notification for ${compliance.userId}:${compliance.compliance_status} - next allowed in $intervalHours hours")
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
