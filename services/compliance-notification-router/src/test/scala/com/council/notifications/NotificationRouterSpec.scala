package com.council.notifications

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant
import com.council.notification._

class NotificationRouterSpec extends AnyFlatSpec with Matchers {
  
  // Helper to create a test compliance record
  def createCompliance(
    userId: String = "user123",
    firstName: String = "John",
    lastName: String = "Doe",
    complianceStatus: String = "EXPIRED",
    wwccNumber: Option[String] = Some("WWC123456"),
    expiryDate: Option[String] = Some("2024-01-01"),
    daysUntilExpiry: Option[Long] = Some(-10)
  ): WwccCompliance = {
    WwccCompliance(
      userId = userId,
      firstName = firstName,
      lastName = lastName,
      email = Some("john.doe@example.com"),
      department = Some("IT"),
      position = Some("Developer"),
      startDate = Some("2023-01-01"),
      wwccNumber = wwccNumber,
      expiryDate = expiryDate,
      daysUntilExpiry = daysUntilExpiry,
      daysSinceStart = Some(365),
      safetyculture_status = "ACTIVE",
      approval_status = "APPROVED",
      compliance_status = complianceStatus,
      flags = List.empty,
      processedAt = Instant.now().toString
    )
  }
  
  // Helper to create test config
  def createTestConfig(
    overrideRecipient: String = "test@example.com",
    template: String = "compliance-alert.txt"
  ): NotificationConfig = {
    NotificationConfig(
      rules = Map(
        "EXPIRED" -> NotificationRule("HIGH"),
        "EXPIRING" -> NotificationRule("MEDIUM"),
        "MISSING" -> NotificationRule("HIGH"),
        "NOT_APPROVED" -> NotificationRule("MEDIUM")
      ),
      override_recipient = overrideRecipient,
      template = template
    )
  }
  
  // ========== Config Loading Tests ==========
  
  "loadConfig" should "load notification config from resources" in {
    val config = ComplianceNotificationRouterService.loadConfig()
    config should not be null
    config.override_recipient should not be empty
    config.template should not be empty
    config.rules should not be empty
  }
  
  it should "have override_recipient set" in {
    val config = ComplianceNotificationRouterService.loadConfig()
    config.override_recipient should not be empty
    config.override_recipient should include("@")
  }
  
  it should "have rules for EXPIRED, EXPIRING, MISSING, NOT_APPROVED" in {
    val config = ComplianceNotificationRouterService.loadConfig()
    config.rules should contain key "EXPIRED"
    config.rules should contain key "EXPIRING"
    config.rules should contain key "MISSING"
    config.rules should contain key "NOT_APPROVED"
  }
  
  // ========== Routing Logic Tests ==========
  
  "shouldNotify" should "return true for EXPIRED status" in {
    ComplianceNotificationRouterService.shouldNotify("EXPIRED") should be(true)
  }
  
  it should "return true for EXPIRING status" in {
    ComplianceNotificationRouterService.shouldNotify("EXPIRING") should be(true)
  }
  
  it should "return true for MISSING status" in {
    ComplianceNotificationRouterService.shouldNotify("MISSING") should be(true)
  }
  
  it should "return true for NOT_APPROVED status" in {
    ComplianceNotificationRouterService.shouldNotify("NOT_APPROVED") should be(true)
  }
  
  it should "return false for COMPLIANT status" in {
    ComplianceNotificationRouterService.shouldNotify("COMPLIANT") should be(false)
  }
  
  "createNotificationCommand" should "create notification for EXPIRED status" in {
    val compliance = createCompliance(complianceStatus = "EXPIRED")
    val config = createTestConfig()
    val notificationId = "test-notification-id"
    val createdAt = Instant.now().toString
    
    val command = ComplianceNotificationRouterService.createNotificationCommand(
      compliance, config, notificationId, createdAt
    )
    
    command.notificationId should be(notificationId)
    command.userId should be(compliance.userId)
    command.userName should be("John Doe")
    command.issueType should be("EXPIRED")
    command.email should be(config.override_recipient)
    command.template should be(config.template)
    command.data.wwccNumber should be(compliance.wwccNumber)
    command.data.expiryDate should be(compliance.expiryDate)
    command.data.daysUntilExpiry should be(compliance.daysUntilExpiry)
    command.createdAt should be(createdAt)
  }
  
  it should "create notification for EXPIRING status" in {
    val compliance = createCompliance(complianceStatus = "EXPIRING")
    val config = createTestConfig()
    val notificationId = "test-notification-id-2"
    val createdAt = Instant.now().toString
    
    val command = ComplianceNotificationRouterService.createNotificationCommand(
      compliance, config, notificationId, createdAt
    )
    
    command.issueType should be("EXPIRING")
    command.userId should be(compliance.userId)
  }
  
  it should "create notification for MISSING status" in {
    val compliance = createCompliance(complianceStatus = "MISSING")
    val config = createTestConfig()
    val notificationId = "test-notification-id-3"
    val createdAt = Instant.now().toString
    
    val command = ComplianceNotificationRouterService.createNotificationCommand(
      compliance, config, notificationId, createdAt
    )
    
    command.issueType should be("MISSING")
    command.userId should be(compliance.userId)
  }
  
  it should "create notification for NOT_APPROVED status" in {
    val compliance = createCompliance(complianceStatus = "NOT_APPROVED")
    val config = createTestConfig()
    val notificationId = "test-notification-id-4"
    val createdAt = Instant.now().toString
    
    val command = ComplianceNotificationRouterService.createNotificationCommand(
      compliance, config, notificationId, createdAt
    )
    
    command.issueType should be("NOT_APPROVED")
    command.userId should be(compliance.userId)
  }
  
  it should "use override_recipient as email" in {
    val compliance = createCompliance()
    val overrideEmail = "override@test.com"
    val config = createTestConfig(overrideRecipient = overrideEmail)
    val notificationId = "test-notification-id-5"
    val createdAt = Instant.now().toString
    
    val command = ComplianceNotificationRouterService.createNotificationCommand(
      compliance, config, notificationId, createdAt
    )
    
    command.email should be(overrideEmail)
    command.email should not be(compliance.email.get)
  }
  
  it should "set correct priority based on status" in {
    val config = createTestConfig()
    
    // Test EXPIRED -> HIGH
    val expiredCompliance = createCompliance(complianceStatus = "EXPIRED")
    val expiredCommand = ComplianceNotificationRouterService.createNotificationCommand(
      expiredCompliance, config, "id1", Instant.now().toString
    )
    expiredCommand.priority should be("HIGH")
    
    // Test EXPIRING -> MEDIUM
    val expiringCompliance = createCompliance(complianceStatus = "EXPIRING")
    val expiringCommand = ComplianceNotificationRouterService.createNotificationCommand(
      expiringCompliance, config, "id2", Instant.now().toString
    )
    expiringCommand.priority should be("MEDIUM")
    
    // Test MISSING -> HIGH
    val missingCompliance = createCompliance(complianceStatus = "MISSING")
    val missingCommand = ComplianceNotificationRouterService.createNotificationCommand(
      missingCompliance, config, "id3", Instant.now().toString
    )
    missingCommand.priority should be("HIGH")
    
    // Test NOT_APPROVED -> MEDIUM
    val notApprovedCompliance = createCompliance(complianceStatus = "NOT_APPROVED")
    val notApprovedCommand = ComplianceNotificationRouterService.createNotificationCommand(
      notApprovedCompliance, config, "id4", Instant.now().toString
    )
    notApprovedCommand.priority should be("MEDIUM")
    
    // Test unknown status -> defaults to MEDIUM
    val unknownCompliance = createCompliance(complianceStatus = "UNKNOWN_STATUS")
    val unknownCommand = ComplianceNotificationRouterService.createNotificationCommand(
      unknownCompliance, config, "id5", Instant.now().toString
    )
    unknownCommand.priority should be("MEDIUM")
  }
  
  // ========== Deduplication Key Tests ==========
  
  "dedupKey" should "generate consistent dedup key for same user and status" in {
    val key1 = ComplianceNotificationRouterService.dedupKey("user123", "EXPIRED")
    val key2 = ComplianceNotificationRouterService.dedupKey("user123", "EXPIRED")
    
    key1 should be(key2)
    key1 should be("notification:user123:EXPIRED")
  }
  
  it should "generate different dedup key for different status" in {
    val key1 = ComplianceNotificationRouterService.dedupKey("user123", "EXPIRED")
    val key2 = ComplianceNotificationRouterService.dedupKey("user123", "EXPIRING")
    
    key1 should not be(key2)
    key1 should be("notification:user123:EXPIRED")
    key2 should be("notification:user123:EXPIRING")
  }
  
  it should "generate different dedup key for different users" in {
    val key1 = ComplianceNotificationRouterService.dedupKey("user123", "EXPIRED")
    val key2 = ComplianceNotificationRouterService.dedupKey("user456", "EXPIRED")
    
    key1 should not be(key2)
    key1 should be("notification:user123:EXPIRED")
    key2 should be("notification:user456:EXPIRED")
  }
  
  // ========== Additional Edge Case Tests ==========
  
  "getPriority" should "return HIGH for EXPIRED status" in {
    val config = createTestConfig()
    ComplianceNotificationRouterService.getPriority("EXPIRED", config) should be("HIGH")
  }
  
  it should "return MEDIUM for EXPIRING status" in {
    val config = createTestConfig()
    ComplianceNotificationRouterService.getPriority("EXPIRING", config) should be("MEDIUM")
  }
  
  it should "return HIGH for MISSING status" in {
    val config = createTestConfig()
    ComplianceNotificationRouterService.getPriority("MISSING", config) should be("HIGH")
  }
  
  it should "return MEDIUM for NOT_APPROVED status" in {
    val config = createTestConfig()
    ComplianceNotificationRouterService.getPriority("NOT_APPROVED", config) should be("MEDIUM")
  }
  
  it should "return MEDIUM as default for unknown status" in {
    val config = createTestConfig()
    ComplianceNotificationRouterService.getPriority("UNKNOWN_STATUS", config) should be("MEDIUM")
  }
  
  "createNotificationCommand" should "handle missing optional fields gracefully" in {
    val compliance = createCompliance(
      wwccNumber = None,
      expiryDate = None,
      daysUntilExpiry = None
    )
    val config = createTestConfig()
    val notificationId = "test-notification-id-6"
    val createdAt = Instant.now().toString
    
    val command = ComplianceNotificationRouterService.createNotificationCommand(
      compliance, config, notificationId, createdAt
    )
    
    command.data.wwccNumber should be(None)
    command.data.expiryDate should be(None)
    command.data.daysUntilExpiry should be(None)
    command.userName should be("John Doe")
  }
  
  it should "construct userName correctly from firstName and lastName" in {
    val compliance = createCompliance(
      firstName = "Jane",
      lastName = "Smith"
    )
    val config = createTestConfig()
    val notificationId = "test-notification-id-7"
    val createdAt = Instant.now().toString
    
    val command = ComplianceNotificationRouterService.createNotificationCommand(
      compliance, config, notificationId, createdAt
    )
    
    command.userName should be("Jane Smith")
  }
}

