package com.council.notifications

import com.dimafeng.testcontainers.KafkaContainer
import org.apache.kafka.clients.consumer.{ConsumerConfig, KafkaConsumer}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import java.util.Properties
import java.time.{Duration, Instant}
import scala.jdk.CollectionConverters._
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}
import com.council.notification._

class NotificationRouterIntegrationSpec extends AnyFlatSpec with Matchers {
  
  // Helper to create Kafka producer
  def createProducer(bootstrapServers: String): KafkaProducer[String, String] = {
    val props = new Properties()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    props.put(ProducerConfig.ACKS_CONFIG, "all")
    new KafkaProducer[String, String](props)
  }
  
  // Helper to create Kafka consumer
  def createConsumer(bootstrapServers: String, groupId: String): KafkaConsumer[String, String] = {
    val props = new Properties()
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, classOf[StringDeserializer].getName)
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
    new KafkaConsumer[String, String](props)
  }
  
  // Helper to create topics (Kafka testcontainers creates topics automatically, but we'll ensure they exist)
  def ensureTopics(bootstrapServers: String, topics: Seq[String]): Unit = {
    // Topics are auto-created in testcontainers Kafka, but we can verify they exist
    // by trying to produce/consume
  }
  
  // Helper to create test compliance record
  def createTestCompliance(
    userId: String = "test-user-123",
    firstName: String = "Test",
    lastName: String = "User",
    complianceStatus: String = "EXPIRED",
    wwccNumber: Option[String] = Some("WWC123456"),
    expiryDate: Option[String] = Some("2024-01-01"),
    daysUntilExpiry: Option[Long] = Some(-10)
  ): WwccCompliance = {
    WwccCompliance(
      userId = userId,
      firstName = firstName,
      lastName = lastName,
      email = Some("test@example.com"),
      department = Some("IT"),
      position = Some("Developer"),
      startDate = Some("2023-01-01"),
      wwccNumber = wwccNumber,
      expiryDate = expiryDate,
      daysUntilExpiry = daysUntilExpiry,
      daysSinceStart = Some(365),
      safetyculture_status = "EXPIRY_STATUS_EXPIRED",
      approval_status = "DOCUMENT_APPROVAL_STATUS_APPROVED",
      compliance_status = complianceStatus,
      flags = List("WWCC_EXPIRED"),
      processedAt = Instant.now().toString
    )
  }
  
  // Simple in-memory Redis mock
  class InMemoryRedis {
    private val store = new java.util.concurrent.ConcurrentHashMap[String, String]()
    
    def exists(key: String): Boolean = store.containsKey(key)
    
    def setex(key: String, seconds: Int, value: String): String = {
      store.put(key, value)
      "OK"
    }
    
    def clear(): Unit = store.clear()
  }
  
  // Process a single message (simulating the router service logic)
  def processComplianceMessage(
    compliance: WwccCompliance,
    bootstrapServers: String,
    redis: InMemoryRedis,
    config: NotificationConfig
  ): Unit = {
    if (ComplianceNotificationRouterService.shouldNotify(compliance.compliance_status)) {
      val key = ComplianceNotificationRouterService.dedupKey(compliance.userId, compliance.compliance_status)
      val alreadyNotified = redis.exists(key)
      
      if (!alreadyNotified) {
        val notificationId = java.util.UUID.randomUUID().toString
        val notificationCommand = ComplianceNotificationRouterService.createNotificationCommand(
          compliance,
          config,
          notificationId,
          Instant.now().toString
        )
        
        val producer = createProducer(bootstrapServers)
        try {
          producer.send(new ProducerRecord[String, String](
            "commands.notifications",
            notificationId,
            notificationCommand.asJson.noSpaces
          )).get()
          
          redis.setex(key, 86400, "sent")
        } finally {
          producer.close()
        }
      }
    }
  }
  
  "NotificationRouterIntegrationSpec" should "produce notification command for EXPIRED status" in {
    val kafkaContainer = KafkaContainer()
    kafkaContainer.start()
    try {
      val bootstrapServers = kafkaContainer.bootstrapServers
    
    // Create topics by producing/consuming (testcontainers auto-creates)
    ensureTopics(bootstrapServers, Seq("processed.wwcc.status", "commands.notifications"))
    
    // Load config
    val config = ComplianceNotificationRouterService.loadConfig()
    
    // Create in-memory Redis mock (using a simple map for deduplication)
    // Since we're skipping Redis, we'll use a simple in-memory approach
    val redis = new InMemoryRedis()
    
    // Create test compliance record
    val compliance = createTestCompliance(
      userId = "test-user-123",
      complianceStatus = "EXPIRED"
    )
    
    // Process the message
    processComplianceMessage(compliance, bootstrapServers, redis, config)
    
      // Consume from commands.notifications
      val consumer = createConsumer(bootstrapServers, "test-consumer-group-1")
      consumer.subscribe(List("commands.notifications").asJava)
      
      try {
        val records = consumer.poll(Duration.ofSeconds(5))
        records.asScala should not be empty
        
        val record = records.asScala.head
        val notificationJson = record.value()
        
        decode[NotificationCommand](notificationJson) match {
          case Right(command: NotificationCommand) =>
            command.userId should be("test-user-123")
            command.userName should be("Test User")
            command.issueType should be("EXPIRED")
            command.priority should be("HIGH") // From config
            command.email should be(config.override_recipient)
            command.data.wwccNumber should be(compliance.wwccNumber)
          case Left(e) =>
            fail(s"Failed to parse notification command: $e")
        }
      } finally {
        consumer.close()
      }
    } finally {
      kafkaContainer.stop()
    }
  }
  
  it should "NOT produce notification command for COMPLIANT status" in {
    val kafkaContainer = KafkaContainer()
    kafkaContainer.start()
    try {
      val bootstrapServers = kafkaContainer.bootstrapServers
    
    ensureTopics(bootstrapServers, Seq("processed.wwcc.status", "commands.notifications"))
    
    val config = ComplianceNotificationRouterService.loadConfig()
    val redis = new InMemoryRedis()
    
    // Create COMPLIANT compliance record
    val compliance = createTestCompliance(
      userId = "test-user-456",
      complianceStatus = "COMPLIANT"
    )
    
    // Process the message
    processComplianceMessage(compliance, bootstrapServers, redis, config)
    
      // Consume from commands.notifications (should be empty)
      val consumer = createConsumer(bootstrapServers, "test-consumer-group-2")
      consumer.subscribe(List("commands.notifications").asJava)
      
      try {
        val records = consumer.poll(Duration.ofSeconds(2))
        records.asScala should be(empty)
      } finally {
        consumer.close()
      }
    } finally {
      kafkaContainer.stop()
    }
  }
  
  it should "include correct priority in notification command" in {
    val kafkaContainer = KafkaContainer()
    kafkaContainer.start()
    try {
      val bootstrapServers = kafkaContainer.bootstrapServers
    
    ensureTopics(bootstrapServers, Seq("processed.wwcc.status", "commands.notifications"))
    
    val config = ComplianceNotificationRouterService.loadConfig()
    val redis1 = new InMemoryRedis()
    val redis2 = new InMemoryRedis()
    
    // Test EXPIRED -> HIGH priority
    val expiredCompliance = createTestCompliance(
      userId = "test-user-expired",
      complianceStatus = "EXPIRED"
    )
    processComplianceMessage(expiredCompliance, bootstrapServers, redis1, config)
    
    // Test EXPIRING -> MEDIUM priority
    val expiringCompliance = createTestCompliance(
      userId = "test-user-expiring",
      complianceStatus = "EXPIRING"
    )
    processComplianceMessage(expiringCompliance, bootstrapServers, redis2, config)
    
      // Consume and verify priorities
      val consumer = createConsumer(bootstrapServers, "test-consumer-group-3")
      consumer.subscribe(List("commands.notifications").asJava)
      
      try {
        val records = consumer.poll(Duration.ofSeconds(5))
        records.asScala should have size 2
        
        val commands: Seq[NotificationCommand] = records.asScala.flatMap { record =>
          decode[NotificationCommand](record.value()).right.toOption.map(_.asInstanceOf[NotificationCommand])
        }.toSeq
        
        commands should have size 2
        val expiredCommand = commands.find(_.userId == "test-user-expired").get
        expiredCommand.priority should be("HIGH")
        
        val expiringCommand = commands.find(_.userId == "test-user-expiring").get
        expiringCommand.priority should be("MEDIUM")
      } finally {
        consumer.close()
      }
    } finally {
      kafkaContainer.stop()
    }
  }
}

