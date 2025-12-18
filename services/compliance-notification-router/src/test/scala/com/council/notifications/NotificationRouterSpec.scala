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
    overrideRecipient: Option[String] = Some("test@example.com"),
    ccRecipients: Seq[String] = Seq.empty,
    template: String = "individual-alert.html"
  ): NotificationConfig = {
    NotificationConfig(
      settings = NotificationSettings(
        dedupTtlHours = 24,
        overrideRecipient = overrideRecipient
      ),
      ccRecipients = ccRecipients,
      issueTypes = Map(
        "EXPIRED" -> IssueTypeConfig("HIGH", template),
        "EXPIRING" -> IssueTypeConfig("MEDIUM", template),
        "MISSING" -> IssueTypeConfig("HIGH", template),
        "NOT_APPROVED" -> IssueTypeConfig("MEDIUM", template)
      ),
      frequencyRules = Seq.empty,
      departmentManagers = Map.empty,
      defaultManager = "",
      digest = DigestConfig(enabled = false, "MONDAY", "09:00", "Australia/Sydney")
    )
  }
  
  // ========== Config Loading Tests ==========
  
  "loadConfigFromYaml" should "load default config when file not found" in {
    val config = ComplianceNotificationRouterService.loadConfigFromYaml("/nonexistent/path.yaml")
    config should not be null
    config.issueTypes should contain key "EXPIRED"
    config.issueTypes should contain key "EXPIRING"
    config.issueTypes should contain key "MISSING"
    config.issueTypes should contain key "NOT_APPROVED"
  }
  
  it should "have default issue types configured" in {
    val config = ComplianceNotificationRouterService.defaultConfig()
    config.issueTypes should contain key "EXPIRED"
    config.issueTypes should contain key "EXPIRING"
    config.issueTypes should contain key "MISSING"
    config.issueTypes should contain key "NOT_APPROVED"
    config.issueTypes("EXPIRED").priority should be("HIGH")
    config.issueTypes("EXPIRING").priority should be("MEDIUM")
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
    command.to should be(Seq("test@example.com"))
    command.cc should be(None)
    command.bcc should be(None)
    command.subject should include("WWCC Compliance Alert: EXPIRED")
    command.subject should include("John Doe")
    command.isHtml should be(true)
    command.template should be("individual-alert.html")
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
  
  it should "use override_recipient as to address when set" in {
    val compliance = createCompliance()
    val overrideEmail = "override@test.com"
    val config = createTestConfig(overrideRecipient = Some(overrideEmail))
    val notificationId = "test-notification-id-5"
    val createdAt = Instant.now().toString
    
    val command = ComplianceNotificationRouterService.createNotificationCommand(
      compliance, config, notificationId, createdAt
    )
    
    command.to should be(Seq(overrideEmail))
    command.to should not contain(compliance.email.get)
  }
  
  it should "use compliance email when override not set" in {
    val compliance = createCompliance().copy(email = Some("user@example.com"))
    val config = createTestConfig(overrideRecipient = None)
    val notificationId = "test-notification-id-5b"
    val createdAt = Instant.now().toString
    
    val command = ComplianceNotificationRouterService.createNotificationCommand(
      compliance, config, notificationId, createdAt
    )
    
    command.to should be(Seq("user@example.com"))
  }
  
  it should "include CC recipients when configured" in {
    val compliance = createCompliance()
    val config = createTestConfig(ccRecipients = Seq("cc1@example.com", "cc2@example.com"))
    val notificationId = "test-notification-id-5c"
    val createdAt = Instant.now().toString
    
    val command = ComplianceNotificationRouterService.createNotificationCommand(
      compliance, config, notificationId, createdAt
    )
    
    command.cc should be(Some(Seq("cc1@example.com", "cc2@example.com")))
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
    key1 should be("notification:last:user123:EXPIRED")
  }
  
  it should "generate different dedup key for different status" in {
    val key1 = ComplianceNotificationRouterService.dedupKey("user123", "EXPIRED")
    val key2 = ComplianceNotificationRouterService.dedupKey("user123", "EXPIRING")
    
    key1 should not be(key2)
    key1 should be("notification:last:user123:EXPIRED")
    key2 should be("notification:last:user123:EXPIRING")
  }
  
  it should "generate different dedup key for different users" in {
    val key1 = ComplianceNotificationRouterService.dedupKey("user123", "EXPIRED")
    val key2 = ComplianceNotificationRouterService.dedupKey("user456", "EXPIRED")
    
    key1 should not be(key2)
    key1 should be("notification:last:user123:EXPIRED")
    key2 should be("notification:last:user456:EXPIRED")
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
  
  // ========== Frequency Rule Tests ==========
  
  "evaluateCondition" should "return true for 'always' condition" in {
    ComplianceNotificationRouterService.evaluateCondition("always", Some(30)) should be(true)
    ComplianceNotificationRouterService.evaluateCondition("always", None) should be(true)
  }
  
  it should "evaluate 'days_until_expiry < X' correctly" in {
    ComplianceNotificationRouterService.evaluateCondition("days_until_expiry < 0", Some(-5)) should be(true)
    ComplianceNotificationRouterService.evaluateCondition("days_until_expiry < 0", Some(0)) should be(false)
    ComplianceNotificationRouterService.evaluateCondition("days_until_expiry < 0", Some(5)) should be(false)
  }
  
  it should "evaluate 'days_until_expiry <= X' correctly" in {
    ComplianceNotificationRouterService.evaluateCondition("days_until_expiry <= 7", Some(5)) should be(true)
    ComplianceNotificationRouterService.evaluateCondition("days_until_expiry <= 7", Some(7)) should be(true)
    ComplianceNotificationRouterService.evaluateCondition("days_until_expiry <= 7", Some(8)) should be(false)
  }
  
  it should "return false for None daysUntilExpiry on day-based conditions" in {
    ComplianceNotificationRouterService.evaluateCondition("days_until_expiry <= 7", None) should be(false)
  }
  
  "getNotificationIntervalSeconds" should "return matching rule interval" in {
    val rules = Seq(
      FrequencyRule("Expired", "days_until_expiry < 0", 24),
      FrequencyRule("Critical", "days_until_expiry <= 7", 24),
      FrequencyRule("Urgent", "days_until_expiry <= 14", 72),
      FrequencyRule("Default", "always", 168)
    )
    
    // Expired: -5 days -> 24 hours
    ComplianceNotificationRouterService.getNotificationIntervalSeconds(Some(-5), rules) should be(24 * 3600)
    
    // Critical: 5 days -> 24 hours  
    ComplianceNotificationRouterService.getNotificationIntervalSeconds(Some(5), rules) should be(24 * 3600)
    
    // Urgent: 10 days -> 72 hours
    ComplianceNotificationRouterService.getNotificationIntervalSeconds(Some(10), rules) should be(72 * 3600)
    
    // Default: 30 days -> 168 hours
    ComplianceNotificationRouterService.getNotificationIntervalSeconds(Some(30), rules) should be(168 * 3600)
  }
  
  it should "return default interval when no rules match" in {
    val rules = Seq.empty[FrequencyRule]
    ComplianceNotificationRouterService.getNotificationIntervalSeconds(Some(30), rules) should be(24 * 3600)
  }
  
  it should "return default interval for None daysUntilExpiry with day-based rules" in {
    val rules = Seq(
      FrequencyRule("Critical", "days_until_expiry <= 7", 24),
      FrequencyRule("Default", "always", 168)
    )
    // None days -> falls through to "always" rule
    ComplianceNotificationRouterService.getNotificationIntervalSeconds(None, rules) should be(168 * 3600)
  }
}

