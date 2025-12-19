package com.council.digest

import org.apache.kafka.clients.consumer.{KafkaConsumer, ConsumerRecord}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import java.util.Properties
import java.time.{Duration, Instant, LocalTime, ZoneId, DayOfWeek, ZonedDateTime}
import scala.jdk.CollectionConverters._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import scala.io.Source
import org.yaml.snakeyaml.Yaml
import java.io.{File, FileInputStream}
import scala.collection.mutable

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
  compliance_status: String,
  flags: List[String],
  processedAt: String
)

// Notification config models
case class NotificationSettings(
  dedupTtlHours: Int,
  overrideRecipient: Option[String]
)

case class DigestConfig(
  enabled: Boolean,
  dayOfWeek: String,
  time: String,
  timezone: String
)

case class NotificationConfig(
  settings: NotificationSettings,
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

case class DigestIssue(
  userId: String,
  userName: String,
  email: Option[String],
  position: Option[String],
  complianceStatus: String,
  wwccNumber: Option[String],
  expiryDate: Option[String],
  daysUntilExpiry: Option[Long]
)

case class DepartmentSummary(
  department: String,
  issues: Seq[DigestIssue],
  totalCount: Int,
  expiredCount: Int,
  expiringCount: Int,
  missingCount: Int,
  notApprovedCount: Int
)

case class DigestTemplateData(
  managerName: String,
  department: String,
  summaryDate: String,
  departments: Seq[DepartmentSummary],
  totalIssues: Int,
  totalExpired: Int,
  totalExpiring: Int,
  totalMissing: Int,
  totalNotApproved: Int
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

object ManagerDigestService {
  
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
      departmentManagers = departmentManagersRaw,
      defaultManager = defaultManager,
      digest = digest
    )
  }
  
  def defaultConfig(): NotificationConfig = {
    NotificationConfig(
      settings = NotificationSettings(dedupTtlHours = 24, overrideRecipient = None),
      departmentManagers = Map.empty,
      defaultManager = "",
      digest = DigestConfig(enabled = false, "MONDAY", "09:00", "Australia/Sydney")
    )
  }
  
  // Pure function: parse day of week string to DayOfWeek enum
  def parseDayOfWeek(dayStr: String): DayOfWeek = {
    dayStr.toUpperCase match {
      case "MONDAY" => DayOfWeek.MONDAY
      case "TUESDAY" => DayOfWeek.TUESDAY
      case "WEDNESDAY" => DayOfWeek.WEDNESDAY
      case "THURSDAY" => DayOfWeek.THURSDAY
      case "FRIDAY" => DayOfWeek.FRIDAY
      case "SATURDAY" => DayOfWeek.SATURDAY
      case "SUNDAY" => DayOfWeek.SUNDAY
      case _ => DayOfWeek.MONDAY // Default to Monday
    }
  }
  
  // Pure function: parse time string (HH:mm) to LocalTime
  def parseTime(timeStr: String): LocalTime = {
    try {
      LocalTime.parse(timeStr)
    } catch {
      case _: Exception => LocalTime.of(9, 0) // Default to 9:00 AM
    }
  }
  
  // Pure function: check if current time is within digest schedule window (5 minutes)
  def isDigestTime(
    currentTime: ZonedDateTime,
    dayOfWeek: DayOfWeek,
    scheduledTime: LocalTime,
    timezone: ZoneId,
    lastSentDate: Option[String]
  ): Boolean = {
    // Check if we already sent today
    val today = currentTime.toLocalDate.toString
    if (lastSentDate.contains(today)) {
      return false
    }
    
    // Check if it's the correct day of week
    if (currentTime.getDayOfWeek != dayOfWeek) {
      return false
    }
    
    // Check if current time is within 5-minute window of scheduled time
    val scheduledZoned = currentTime.toLocalDate.atTime(scheduledTime).atZone(timezone)
    val windowStart = scheduledZoned.minusMinutes(2) // 2 minutes before
    val windowEnd = scheduledZoned.plusMinutes(3)    // 3 minutes after (5 min total window)
    
    !currentTime.isBefore(windowStart) && !currentTime.isAfter(windowEnd)
  }
  
  // Pure function: aggregate compliance records by department
  def aggregateByDepartment(
    complianceMap: Map[String, WwccCompliance]
  ): Map[String, Seq[WwccCompliance]] = {
    complianceMap.values
      .filter(c => c.compliance_status != "COMPLIANT") // Only non-compliant
      .groupBy(_.department.getOrElse("Unknown"))
      .map { case (dept, records) => dept -> records.toSeq }
  }
  
  // Pure function: create department summary from compliance records
  def createDepartmentSummary(
    department: String,
    issues: Seq[WwccCompliance]
  ): DepartmentSummary = {
    val digestIssues = issues.map { c =>
      DigestIssue(
        userId = c.userId,
        userName = s"${c.firstName} ${c.lastName}",
        email = c.email,
        position = c.position,
        complianceStatus = c.compliance_status,
        wwccNumber = c.wwccNumber,
        expiryDate = c.expiryDate,
        daysUntilExpiry = c.daysUntilExpiry
      )
    }
    
    val expiredCount = issues.count(_.compliance_status == "EXPIRED")
    val expiringCount = issues.count(_.compliance_status == "EXPIRING")
    val missingCount = issues.count(_.compliance_status == "MISSING")
    val notApprovedCount = issues.count(_.compliance_status == "NOT_APPROVED")
    
    DepartmentSummary(
      department = department,
      issues = digestIssues,
      totalCount = issues.size,
      expiredCount = expiredCount,
      expiringCount = expiringCount,
      missingCount = missingCount,
      notApprovedCount = notApprovedCount
    )
  }
  
  // Pure function: build template data for digest email
  def buildTemplateData(
    departmentSummaries: Seq[DepartmentSummary],
    managerEmail: String,
    department: String
  ): DigestTemplateData = {
    val totalIssues = departmentSummaries.map(_.totalCount).sum
    val totalExpired = departmentSummaries.map(_.expiredCount).sum
    val totalExpiring = departmentSummaries.map(_.expiringCount).sum
    val totalMissing = departmentSummaries.map(_.missingCount).sum
    val totalNotApproved = departmentSummaries.map(_.notApprovedCount).sum
    
    DigestTemplateData(
      managerName = if (managerEmail.contains("@")) {
        managerEmail.split("@").headOption.getOrElse("Manager")
      } else {
        "Manager"
      },
      department = department,
      summaryDate = java.time.LocalDate.now().toString,
      departments = departmentSummaries,
      totalIssues = totalIssues,
      totalExpired = totalExpired,
      totalExpiring = totalExpiring,
      totalMissing = totalMissing,
      totalNotApproved = totalNotApproved
    )
  }
  
  // Pure function: create notification command for digest
  def createDigestNotificationCommand(
    department: String,
    managerEmail: String,
    templateData: DigestTemplateData,
    config: NotificationConfig,
    notificationId: String,
    createdAt: String
  ): NotificationCommand = {
    val toRecipients = config.settings.overrideRecipient match {
      case Some(overrideAddr) => Seq(overrideAddr)
      case None => Seq(managerEmail)
    }
    
    NotificationCommand(
      notificationId = notificationId,
      userId = "system", // System-generated digest
      userName = s"${department} Manager",
      to = toRecipients,
      cc = None,
      bcc = None,
      subject = s"Weekly WWCC Compliance Digest - ${department} (${templateData.totalIssues} issues)",
      issueType = "DIGEST",
      priority = if (templateData.totalExpired > 0) "HIGH" else "MEDIUM",
      template = "manager-digest.html",
      isHtml = true,
      data = NotificationData(None, None, None), // Not used for digest
      createdAt = createdAt
    )
  }
  
  def main(args: Array[String]): Unit = {
    val kafkaBootstrap = sys.env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    
    println("[INFO] Manager Digest Service Starting")
    println(s"[INFO] Kafka: $kafkaBootstrap")
    
    // Load notification config
    val configPath = sys.env.getOrElse("NOTIFICATION_CONFIG_PATH", "/app/config/notification-settings.yaml")
    val config = loadConfigFromYaml(configPath)
    println(s"[INFO] Loaded notification config from $configPath")
    
    if (!config.digest.enabled) {
      println("[WARN] Digest is disabled in config. Exiting.")
      return
    }
    
    config.settings.overrideRecipient.foreach(overrideAddr => 
      println(s"      Override recipient: $overrideAddr")
    )
    println(s"      Digest schedule: ${config.digest.dayOfWeek} at ${config.digest.time} ${config.digest.timezone}")
    
    // Consumer for processed.wwcc.status
    val consumerProps = new Properties()
    consumerProps.put("bootstrap.servers", kafkaBootstrap)
    consumerProps.put("group.id", "manager-digest-service-v1")
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
    
    // In-memory map of latest compliance per userId
    val complianceMap = mutable.Map[String, WwccCompliance]()
    
    // Track last sent date (format: YYYY-MM-DD)
    var lastSentDate: Option[String] = None
    
    // Parse schedule
    val dayOfWeek = parseDayOfWeek(config.digest.dayOfWeek)
    val scheduledTime = parseTime(config.digest.time)
    val timezone = ZoneId.of(config.digest.timezone)
    
    println("[INFO] Service ready, consuming from processed.wwcc.status...")
    println(s"[INFO] Will check for digest time every 30 seconds")
    println(s"[INFO] Scheduled: ${dayOfWeek} at ${scheduledTime} ${timezone}")
    
    var lastCheckTime = Instant.now()
    
    while (true) {
      try {
        // Consume compliance updates
        val records = consumer.poll(Duration.ofMillis(1000))
        
        records.asScala.foreach { record =>
          decode[WwccCompliance](record.value()) match {
            case Right(compliance) =>
              // Update in-memory map (keep latest per userId)
              complianceMap.put(compliance.userId, compliance)
              
            case Left(e) =>
              println(s"[WARN] Failed to parse compliance status: ${e.getMessage}")
          }
        }
        
        // Check every 30 seconds if it's digest time
        val now = Instant.now()
        val secondsSinceLastCheck = java.time.Duration.between(lastCheckTime, now).getSeconds
        if (secondsSinceLastCheck >= 30) {
          lastCheckTime = now
          
          val currentTime = ZonedDateTime.now(timezone)
          
          if (isDigestTime(currentTime, dayOfWeek, scheduledTime, timezone, lastSentDate)) {
            println(s"[INFO] Digest time detected! Generating digests...")
            
            // Aggregate by department
            val byDepartment = aggregateByDepartment(complianceMap.toMap)
            
            if (byDepartment.isEmpty) {
              println("[INFO] No compliance issues found. Skipping digest.")
              lastSentDate = Some(currentTime.toLocalDate.toString)
            } else {
              // Group by manager (one email per manager)
              val byManager = byDepartment.groupBy { case (dept, _) =>
                config.departmentManagers.getOrElse(dept, config.defaultManager)
              }
              
              // Send one digest per manager
              byManager.foreach { case (managerEmail, deptMap) =>
                val summaries = deptMap.map { case (dept, issues) =>
                  createDepartmentSummary(dept, issues)
                }.toSeq
                
                // Get primary department (first one, or use manager email domain)
                val primaryDept = summaries.headOption.map(_.department).getOrElse("All Departments")
                
                val templateData = buildTemplateData(summaries, managerEmail, primaryDept)
                
                val notificationId = java.util.UUID.randomUUID().toString
                val notificationCommand = createDigestNotificationCommand(
                  primaryDept,
                  managerEmail,
                  templateData,
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
                  )).get()
                  
                  println(s"[INFO] Published digest notification: $notificationId")
                  println(s"      Manager: $managerEmail")
                  println(s"      Departments: ${summaries.map(_.department).mkString(", ")}")
                  println(s"      Total issues: ${templateData.totalIssues}")
                  println(s"      Published to partition ${metadata.partition()} at offset ${metadata.offset()}")
                } catch {
                  case e: Exception =>
                    println(s"[ERROR] Failed to publish digest notification $notificationId: ${e.getMessage}")
                    e.printStackTrace()
                }
              }
              
              lastSentDate = Some(currentTime.toLocalDate.toString)
              println(s"[INFO] Digest generation complete. Last sent: $lastSentDate")
            }
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
}

