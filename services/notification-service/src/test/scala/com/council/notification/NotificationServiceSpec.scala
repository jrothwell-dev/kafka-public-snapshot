package com.council.notification

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class NotificationServiceSpec extends AnyFlatSpec with Matchers {
  
  // Helper to create a test notification command
  def createNotificationCommand(
    notificationId: String = "test-notification-id",
    userId: String = "user123",
    userName: String = "John Doe",
    to: Seq[String] = Seq("john.doe@example.com"),
    cc: Option[Seq[String]] = None,
    bcc: Option[Seq[String]] = None,
    subject: String = "WWCC Compliance Alert: EXPIRED - John Doe",
    issueType: String = "EXPIRED",
    priority: String = "HIGH",
    template: String = "compliance-alert.txt",
    isHtml: Boolean = true,
    wwccNumber: Option[String] = Some("WWC123456"),
    expiryDate: Option[String] = Some("2024-01-01"),
    daysUntilExpiry: Option[Long] = Some(-10)
  ): NotificationCommand = {
    NotificationCommand(
      notificationId = notificationId,
      userId = userId,
      userName = userName,
      to = to,
      cc = cc,
      bcc = bcc,
      subject = subject,
      issueType = issueType,
      priority = priority,
      template = template,
      isHtml = isHtml,
      data = NotificationData(
        wwccNumber = wwccNumber,
        expiryDate = expiryDate,
        daysUntilExpiry = daysUntilExpiry
      ),
      createdAt = Instant.now().toString
    )
  }
  
  // ========== Deduplication Key Tests ==========
  
  "dedupKey" should "generate consistent dedup key for same notification ID" in {
    val key1 = NotificationService.dedupKey("notification-123")
    val key2 = NotificationService.dedupKey("notification-123")
    
    key1 should be(key2)
    key1 should be("notification:sent:notification-123")
  }
  
  it should "generate different dedup key for different notification IDs" in {
    val key1 = NotificationService.dedupKey("notification-123")
    val key2 = NotificationService.dedupKey("notification-456")
    
    key1 should not be(key2)
    key1 should be("notification:sent:notification-123")
    key2 should be("notification:sent:notification-456")
  }
  
  it should "handle UUID format notification IDs" in {
    val uuid = "550e8400-e29b-41d4-a716-446655440000"
    val key = NotificationService.dedupKey(uuid)
    
    key should be(s"notification:sent:$uuid")
  }
  
  // ========== Notification Command Model Tests ==========
  
  "NotificationCommand" should "support multiple TO recipients" in {
    val command = createNotificationCommand(
      to = Seq("user1@example.com", "user2@example.com", "user3@example.com")
    )
    
    command.to should have size 3
    command.to should contain("user1@example.com")
    command.to should contain("user2@example.com")
    command.to should contain("user3@example.com")
  }
  
  it should "support CC recipients" in {
    val command = createNotificationCommand(
      cc = Some(Seq("cc1@example.com", "cc2@example.com"))
    )
    
    command.cc should be(Some(Seq("cc1@example.com", "cc2@example.com")))
  }
  
  it should "support BCC recipients" in {
    val command = createNotificationCommand(
      bcc = Some(Seq("bcc1@example.com", "bcc2@example.com"))
    )
    
    command.bcc should be(Some(Seq("bcc1@example.com", "bcc2@example.com")))
  }
  
  it should "support CC and BCC together" in {
    val command = createNotificationCommand(
      to = Seq("to@example.com"),
      cc = Some(Seq("cc@example.com")),
      bcc = Some(Seq("bcc@example.com"))
    )
    
    command.to should be(Seq("to@example.com"))
    command.cc should be(Some(Seq("cc@example.com")))
    command.bcc should be(Some(Seq("bcc@example.com")))
  }
  
  it should "have explicit subject field" in {
    val command = createNotificationCommand(
      subject = "Custom Subject Line"
    )
    
    command.subject should be("Custom Subject Line")
  }
  
  it should "support HTML content flag" in {
    val htmlCommand = createNotificationCommand(isHtml = true)
    htmlCommand.isHtml should be(true)
    
    val textCommand = createNotificationCommand(isHtml = false)
    textCommand.isHtml should be(false)
  }
  
  // ========== Email Body Tests ==========
  
  "createBody" should "create email body with all fields populated" in {
    val command = createNotificationCommand(
      userName = "Jane Smith",
      issueType = "EXPIRED",
      priority = "HIGH",
      wwccNumber = Some("WWC789012"),
      expiryDate = Some("2024-12-31"),
      daysUntilExpiry = Some(30)
    )
    val body = NotificationService.createBody(command)
    
    body should include("Dear Jane Smith")
    body should include("Issue Type: EXPIRED")
    body should include("Priority: HIGH")
    body should include("WWCC Number: WWC789012")
    body should include("Expiry Date: 2024-12-31")
    body should include("Days until expiry: 30")
    body should include("Please take appropriate action to ensure compliance")
    body should include("This is an automated message")
  }
  
  it should "handle missing WWCC number gracefully" in {
    val command = createNotificationCommand(
      wwccNumber = None,
      expiryDate = Some("2024-12-31"),
      daysUntilExpiry = Some(30)
    )
    val body = NotificationService.createBody(command)
    
    body should include("WWCC Number: Not provided")
    body should include("Expiry Date: 2024-12-31")
    body should include("Days until expiry: 30")
  }
  
  it should "handle missing expiry date gracefully" in {
    val command = createNotificationCommand(
      wwccNumber = Some("WWC123456"),
      expiryDate = None,
      daysUntilExpiry = Some(30)
    )
    val body = NotificationService.createBody(command)
    
    body should include("WWCC Number: WWC123456")
    body should include("Expiry Date: Not provided")
    body should include("Days until expiry: 30")
  }
  
  it should "handle missing days until expiry gracefully" in {
    val command = createNotificationCommand(
      wwccNumber = Some("WWC123456"),
      expiryDate = Some("2024-12-31"),
      daysUntilExpiry = None
    )
    val body = NotificationService.createBody(command)
    
    body should include("WWCC Number: WWC123456")
    body should include("Expiry Date: 2024-12-31")
    body should not include("Days until expiry:")
  }
  
  it should "handle all optional fields missing" in {
    val command = createNotificationCommand(
      wwccNumber = None,
      expiryDate = None,
      daysUntilExpiry = None
    )
    val body = NotificationService.createBody(command)
    
    body should include("WWCC Number: Not provided")
    body should include("Expiry Date: Not provided")
    body should not include("Days until expiry:")
    body should include("Issue Type: EXPIRED")
    body should include("Priority: HIGH")
  }
  
  it should "include user name in greeting" in {
    val command = createNotificationCommand(userName = "Alice Johnson")
    val body = NotificationService.createBody(command)
    
    body should startWith("Dear Alice Johnson")
  }
  
  it should "format body correctly for different issue types" in {
    val expiredCommand = createNotificationCommand(issueType = "EXPIRED", priority = "HIGH")
    val expiredBody = NotificationService.createBody(expiredCommand)
    expiredBody should include("Issue Type: EXPIRED")
    expiredBody should include("Priority: HIGH")
    
    val expiringCommand = createNotificationCommand(issueType = "EXPIRING", priority = "MEDIUM")
    val expiringBody = NotificationService.createBody(expiringCommand)
    expiringBody should include("Issue Type: EXPIRING")
    expiringBody should include("Priority: MEDIUM")
  }
  
  it should "not include days until expiry line when value is None" in {
    val command = createNotificationCommand(daysUntilExpiry = None)
    val body = NotificationService.createBody(command)
    
    // The body should not have an empty "Days until expiry:" line
    val lines = body.split("\n")
    lines should not contain("Days until expiry:")
  }
  
  // ========== Edge Case Tests ==========
  
  "createBody" should "handle empty user name" in {
    val command = createNotificationCommand(userName = "")
    val body = NotificationService.createBody(command)
    
    body should include("Dear ,")
  }
  
  it should "handle special characters in user name" in {
    val command = createNotificationCommand(userName = "O'Brien-Smith")
    val body = NotificationService.createBody(command)
    
    body should include("Dear O'Brien-Smith")
  }
  
  it should "handle negative days until expiry" in {
    val command = createNotificationCommand(daysUntilExpiry = Some(-5))
    val body = NotificationService.createBody(command)
    
    body should include("Days until expiry: -5")
  }
  
  it should "handle zero days until expiry" in {
    val command = createNotificationCommand(daysUntilExpiry = Some(0))
    val body = NotificationService.createBody(command)
    
    body should include("Days until expiry: 0")
  }
  
  it should "handle very large days until expiry" in {
    val command = createNotificationCommand(daysUntilExpiry = Some(365))
    val body = NotificationService.createBody(command)
    
    body should include("Days until expiry: 365")
  }
  
  // ========== Template Data Building Tests ==========
  
  "buildTemplateData" should "build correct data for EXPIRED status" in {
    val command = createNotificationCommand(
      issueType = "EXPIRED",
      userName = "John Doe",
      wwccNumber = Some("WWC123456"),
      expiryDate = Some("2024-01-01"),
      daysUntilExpiry = Some(-10)
    )
    val data = NotificationService.buildTemplateData(command)
    
    data("userName").asInstanceOf[String] should be("John Doe")
    data("issueType").asInstanceOf[String] should be("EXPIRED")
    data("isExpired").asInstanceOf[Boolean] should be(true)
    data("isExpiring").asInstanceOf[Boolean] should be(false)
    data("isMissing").asInstanceOf[Boolean] should be(false)
    data("isNotApproved").asInstanceOf[Boolean] should be(false)
    data("statusBgColor").asInstanceOf[String] should be("#dc3545")
    data("statusTextColor").asInstanceOf[String] should be("#ffffff")
    data("daysColor").asInstanceOf[String] should be("#dc3545") // Negative days = red
    data("message").asInstanceOf[String] should include("expired")
    data("actionRequired").asInstanceOf[String] should include("apply")
  }
  
  it should "build correct data for EXPIRING status" in {
    val command = createNotificationCommand(
      issueType = "EXPIRING",
      daysUntilExpiry = Some(7)
    )
    val data = NotificationService.buildTemplateData(command)
    
    data("issueType").asInstanceOf[String] should be("EXPIRING")
    data("isExpired").asInstanceOf[Boolean] should be(false)
    data("isExpiring").asInstanceOf[Boolean] should be(true)
    data("statusBgColor").asInstanceOf[String] should be("#fd7e14")
    data("daysColor").asInstanceOf[String] should be("#fd7e14") // Less than 14 days = orange
  }
  
  it should "build correct data for MISSING status" in {
    val command = createNotificationCommand(
      issueType = "MISSING",
      wwccNumber = None,
      expiryDate = None,
      daysUntilExpiry = None
    )
    val data = NotificationService.buildTemplateData(command)
    
    data("issueType").asInstanceOf[String] should be("MISSING")
    data("isMissing").asInstanceOf[Boolean] should be(true)
    data("statusBgColor").asInstanceOf[String] should be("#dc3545")
    data("message").asInstanceOf[String] should include("don't have one on file")
  }
  
  it should "build correct data for NOT_APPROVED status" in {
    val command = createNotificationCommand(
      issueType = "NOT_APPROVED"
    )
    val data = NotificationService.buildTemplateData(command)
    
    data("issueType").asInstanceOf[String] should be("NOT_APPROVED")
    data("isNotApproved").asInstanceOf[Boolean] should be(true)
    data("statusBgColor").asInstanceOf[String] should be("#ffc107")
    data("statusTextColor").asInstanceOf[String] should be("#212529")
    data("actionRequired").asInstanceOf[String] should include("No action required")
  }
  
  it should "set correct days color based on days until expiry" in {
    val expiredCommand = createNotificationCommand(daysUntilExpiry = Some(-5))
    val expiredData = NotificationService.buildTemplateData(expiredCommand)
    expiredData("daysColor").asInstanceOf[String] should be("#dc3545") // Red for negative
    
    val urgentCommand = createNotificationCommand(daysUntilExpiry = Some(5))
    val urgentData = NotificationService.buildTemplateData(urgentCommand)
    urgentData("daysColor").asInstanceOf[String] should be("#fd7e14") // Orange for < 14 days
    
    val warningCommand = createNotificationCommand(daysUntilExpiry = Some(20))
    val warningData = NotificationService.buildTemplateData(warningCommand)
    warningData("daysColor").asInstanceOf[String] should be("#ffc107") // Yellow for < 30 days
    
    val goodCommand = createNotificationCommand(daysUntilExpiry = Some(60))
    val goodData = NotificationService.buildTemplateData(goodCommand)
    goodData("daysColor").asInstanceOf[String] should be("#28a745") // Green for >= 30 days
  }
  
  it should "handle null values for optional fields" in {
    val command = createNotificationCommand(
      wwccNumber = None,
      expiryDate = None,
      daysUntilExpiry = None
    )
    val data = NotificationService.buildTemplateData(command)
    
    Option(data("wwccNumber")) should be(None)
    Option(data("expiryDate")) should be(None)
    Option(data("daysUntilExpiry")) should be(None)
  }
  
  it should "include timestamp in template data" in {
    val command = createNotificationCommand()
    val data = NotificationService.buildTemplateData(command)
    
    data should contain key "timestamp"
    val timestamp = data("timestamp").asInstanceOf[String]
    timestamp should not be null
    timestamp should not be empty
  }
}

