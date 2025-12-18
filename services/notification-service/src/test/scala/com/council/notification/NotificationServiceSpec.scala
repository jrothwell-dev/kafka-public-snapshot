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
    email: String = "john.doe@example.com",
    issueType: String = "EXPIRED",
    priority: String = "HIGH",
    template: String = "compliance-alert.txt",
    wwccNumber: Option[String] = Some("WWC123456"),
    expiryDate: Option[String] = Some("2024-01-01"),
    daysUntilExpiry: Option[Long] = Some(-10)
  ): NotificationCommand = {
    NotificationCommand(
      notificationId = notificationId,
      userId = userId,
      userName = userName,
      email = email,
      issueType = issueType,
      priority = priority,
      template = template,
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
  
  // ========== Email Subject Tests ==========
  
  "createSubject" should "create subject with issue type for EXPIRED" in {
    val command = createNotificationCommand(issueType = "EXPIRED")
    val subject = NotificationService.createSubject(command)
    
    subject should be("WWCC Compliance Notification - EXPIRED")
  }
  
  it should "create subject with issue type for EXPIRING" in {
    val command = createNotificationCommand(issueType = "EXPIRING")
    val subject = NotificationService.createSubject(command)
    
    subject should be("WWCC Compliance Notification - EXPIRING")
  }
  
  it should "create subject with issue type for MISSING" in {
    val command = createNotificationCommand(issueType = "MISSING")
    val subject = NotificationService.createSubject(command)
    
    subject should be("WWCC Compliance Notification - MISSING")
  }
  
  it should "create subject with issue type for NOT_APPROVED" in {
    val command = createNotificationCommand(issueType = "NOT_APPROVED")
    val subject = NotificationService.createSubject(command)
    
    subject should be("WWCC Compliance Notification - NOT_APPROVED")
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
}

