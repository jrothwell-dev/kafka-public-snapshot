package com.council.digest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.{DayOfWeek, LocalTime, ZoneId, ZonedDateTime, LocalDate}
import com.council.digest._

class ManagerDigestServiceSpec extends AnyFlatSpec with Matchers {
  
  // Helper to create a test compliance record
  def createCompliance(
    userId: String = "user123",
    firstName: String = "John",
    lastName: String = "Doe",
    department: Option[String] = Some("IT Services"),
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
      department = department,
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
      processedAt = java.time.Instant.now().toString
    )
  }
  
  // ========== Day/Time Parsing Tests ==========
  
  "parseDayOfWeek" should "parse MONDAY correctly" in {
    ManagerDigestService.parseDayOfWeek("MONDAY") should be(DayOfWeek.MONDAY)
  }
  
  it should "parse TUESDAY correctly" in {
    ManagerDigestService.parseDayOfWeek("TUESDAY") should be(DayOfWeek.TUESDAY)
  }
  
  it should "parse WEDNESDAY correctly" in {
    ManagerDigestService.parseDayOfWeek("WEDNESDAY") should be(DayOfWeek.WEDNESDAY)
  }
  
  it should "parse THURSDAY correctly" in {
    ManagerDigestService.parseDayOfWeek("THURSDAY") should be(DayOfWeek.THURSDAY)
  }
  
  it should "parse FRIDAY correctly" in {
    ManagerDigestService.parseDayOfWeek("FRIDAY") should be(DayOfWeek.FRIDAY)
  }
  
  it should "parse SATURDAY correctly" in {
    ManagerDigestService.parseDayOfWeek("SATURDAY") should be(DayOfWeek.SATURDAY)
  }
  
  it should "parse SUNDAY correctly" in {
    ManagerDigestService.parseDayOfWeek("SUNDAY") should be(DayOfWeek.SUNDAY)
  }
  
  it should "be case insensitive" in {
    ManagerDigestService.parseDayOfWeek("monday") should be(DayOfWeek.MONDAY)
    ManagerDigestService.parseDayOfWeek("Monday") should be(DayOfWeek.MONDAY)
  }
  
  it should "default to MONDAY for invalid input" in {
    ManagerDigestService.parseDayOfWeek("INVALID") should be(DayOfWeek.MONDAY)
  }
  
  "parseTime" should "parse valid time string correctly" in {
    ManagerDigestService.parseTime("09:00") should be(LocalTime.of(9, 0))
    ManagerDigestService.parseTime("14:30") should be(LocalTime.of(14, 30))
    ManagerDigestService.parseTime("23:59") should be(LocalTime.of(23, 59))
  }
  
  it should "default to 09:00 for invalid input" in {
    ManagerDigestService.parseTime("invalid") should be(LocalTime.of(9, 0))
    ManagerDigestService.parseTime("25:00") should be(LocalTime.of(9, 0))
  }
  
  // ========== isDigestTime Tests ==========
  
  "isDigestTime" should "return false if already sent today" in {
    val timezone = ZoneId.of("Australia/Sydney")
    val currentTime = ZonedDateTime.of(2024, 12, 23, 9, 0, 0, 0, timezone)
    val dayOfWeek = DayOfWeek.MONDAY
    val scheduledTime = LocalTime.of(9, 0)
    val lastSentDate = Some("2024-12-23")
    
    ManagerDigestService.isDigestTime(
      currentTime, dayOfWeek, scheduledTime, timezone, lastSentDate
    ) should be(false)
  }
  
  it should "return false if wrong day of week" in {
    val timezone = ZoneId.of("Australia/Sydney")
    val currentTime = ZonedDateTime.of(2024, 12, 24, 9, 0, 0, 0, timezone) // Tuesday
    val dayOfWeek = DayOfWeek.MONDAY
    val scheduledTime = LocalTime.of(9, 0)
    val lastSentDate = None
    
    ManagerDigestService.isDigestTime(
      currentTime, dayOfWeek, scheduledTime, timezone, lastSentDate
    ) should be(false)
  }
  
  it should "return true if correct day and time within window" in {
    val timezone = ZoneId.of("Australia/Sydney")
    val currentTime = ZonedDateTime.of(2024, 12, 23, 9, 0, 0, 0, timezone) // Monday
    val dayOfWeek = DayOfWeek.MONDAY
    val scheduledTime = LocalTime.of(9, 0)
    val lastSentDate = None
    
    ManagerDigestService.isDigestTime(
      currentTime, dayOfWeek, scheduledTime, timezone, lastSentDate
    ) should be(true)
  }
  
  it should "return true if time is 2 minutes before scheduled" in {
    val timezone = ZoneId.of("Australia/Sydney")
    val currentTime = ZonedDateTime.of(2024, 12, 23, 8, 58, 0, 0, timezone) // Monday, 8:58
    val dayOfWeek = DayOfWeek.MONDAY
    val scheduledTime = LocalTime.of(9, 0)
    val lastSentDate = None
    
    ManagerDigestService.isDigestTime(
      currentTime, dayOfWeek, scheduledTime, timezone, lastSentDate
    ) should be(true)
  }
  
  it should "return true if time is 3 minutes after scheduled" in {
    val timezone = ZoneId.of("Australia/Sydney")
    val currentTime = ZonedDateTime.of(2024, 12, 23, 9, 3, 0, 0, timezone) // Monday, 9:03
    val dayOfWeek = DayOfWeek.MONDAY
    val scheduledTime = LocalTime.of(9, 0)
    val lastSentDate = None
    
    ManagerDigestService.isDigestTime(
      currentTime, dayOfWeek, scheduledTime, timezone, lastSentDate
    ) should be(true)
  }
  
  it should "return false if time is before window" in {
    val timezone = ZoneId.of("Australia/Sydney")
    val currentTime = ZonedDateTime.of(2024, 12, 23, 8, 57, 0, 0, timezone) // Monday, 8:57
    val dayOfWeek = DayOfWeek.MONDAY
    val scheduledTime = LocalTime.of(9, 0)
    val lastSentDate = None
    
    ManagerDigestService.isDigestTime(
      currentTime, dayOfWeek, scheduledTime, timezone, lastSentDate
    ) should be(false)
  }
  
  it should "return false if time is after window" in {
    val timezone = ZoneId.of("Australia/Sydney")
    val currentTime = ZonedDateTime.of(2024, 12, 23, 9, 4, 0, 0, timezone) // Monday, 9:04
    val dayOfWeek = DayOfWeek.MONDAY
    val scheduledTime = LocalTime.of(9, 0)
    val lastSentDate = None
    
    ManagerDigestService.isDigestTime(
      currentTime, dayOfWeek, scheduledTime, timezone, lastSentDate
    ) should be(false)
  }
  
  it should "return true if last sent was yesterday" in {
    val timezone = ZoneId.of("Australia/Sydney")
    val currentTime = ZonedDateTime.of(2024, 12, 23, 9, 0, 0, 0, timezone) // Monday
    val dayOfWeek = DayOfWeek.MONDAY
    val scheduledTime = LocalTime.of(9, 0)
    val lastSentDate = Some("2024-12-22") // Yesterday
    
    ManagerDigestService.isDigestTime(
      currentTime, dayOfWeek, scheduledTime, timezone, lastSentDate
    ) should be(true)
  }
  
  // ========== aggregateByDepartment Tests ==========
  
  "aggregateByDepartment" should "group compliance records by department" in {
    val compliance1 = createCompliance(userId = "user1", department = Some("IT Services"), complianceStatus = "EXPIRED")
    val compliance2 = createCompliance(userId = "user2", department = Some("IT Services"), complianceStatus = "EXPIRING")
    val compliance3 = createCompliance(userId = "user3", department = Some("HR"), complianceStatus = "MISSING")
    
    val complianceMap = Map(
      "user1" -> compliance1,
      "user2" -> compliance2,
      "user3" -> compliance3
    )
    
    val result = ManagerDigestService.aggregateByDepartment(complianceMap)
    
    result should contain key "IT Services"
    result should contain key "HR"
    result("IT Services").size should be(2)
    result("HR").size should be(1)
  }
  
  it should "filter out COMPLIANT records" in {
    val compliance1 = createCompliance(userId = "user1", complianceStatus = "EXPIRED")
    val compliance2 = createCompliance(userId = "user2", complianceStatus = "COMPLIANT")
    val compliance3 = createCompliance(userId = "user3", complianceStatus = "EXPIRING")
    
    val complianceMap = Map(
      "user1" -> compliance1,
      "user2" -> compliance2,
      "user3" -> compliance3
    )
    
    val result = ManagerDigestService.aggregateByDepartment(complianceMap)
    
    result.values.flatten.size should be(2) // Only EXPIRED and EXPIRING
    result.values.flatten.map(_.compliance_status) should not contain "COMPLIANT"
  }
  
  it should "handle missing department as 'Unknown'" in {
    val compliance1 = createCompliance(userId = "user1", department = None, complianceStatus = "EXPIRED")
    val compliance2 = createCompliance(userId = "user2", department = Some("IT Services"), complianceStatus = "EXPIRING")
    
    val complianceMap = Map(
      "user1" -> compliance1,
      "user2" -> compliance2
    )
    
    val result = ManagerDigestService.aggregateByDepartment(complianceMap)
    
    result should contain key "Unknown"
    result should contain key "IT Services"
    result("Unknown").size should be(1)
  }
  
  it should "return empty map when all records are COMPLIANT" in {
    val compliance1 = createCompliance(userId = "user1", complianceStatus = "COMPLIANT")
    val compliance2 = createCompliance(userId = "user2", complianceStatus = "COMPLIANT")
    
    val complianceMap = Map(
      "user1" -> compliance1,
      "user2" -> compliance2
    )
    
    val result = ManagerDigestService.aggregateByDepartment(complianceMap)
    
    result should be(empty)
  }
  
  // ========== createDepartmentSummary Tests ==========
  
  "createDepartmentSummary" should "create summary with correct counts" in {
    val issues = Seq(
      createCompliance(userId = "user1", complianceStatus = "EXPIRED"),
      createCompliance(userId = "user2", complianceStatus = "EXPIRED"),
      createCompliance(userId = "user3", complianceStatus = "EXPIRING"),
      createCompliance(userId = "user4", complianceStatus = "MISSING"),
      createCompliance(userId = "user5", complianceStatus = "NOT_APPROVED")
    )
    
    val summary = ManagerDigestService.createDepartmentSummary("IT Services", issues)
    
    summary.department should be("IT Services")
    summary.totalCount should be(5)
    summary.expiredCount should be(2)
    summary.expiringCount should be(1)
    summary.missingCount should be(1)
    summary.notApprovedCount should be(1)
    summary.issues.size should be(5)
  }
  
  it should "create digest issues with correct data" in {
    val issues = Seq(
      createCompliance(
        userId = "user1",
        firstName = "John",
        lastName = "Doe",
        complianceStatus = "EXPIRED",
        wwccNumber = Some("WWC123"),
        expiryDate = Some("2024-01-01"),
        daysUntilExpiry = Some(-10)
      )
    )
    
    val summary = ManagerDigestService.createDepartmentSummary("IT Services", issues)
    
    summary.issues.head.userId should be("user1")
    summary.issues.head.userName should be("John Doe")
    summary.issues.head.complianceStatus should be("EXPIRED")
    summary.issues.head.wwccNumber should be(Some("WWC123"))
    summary.issues.head.expiryDate should be(Some("2024-01-01"))
    summary.issues.head.daysUntilExpiry should be(Some(-10))
  }
  
  // ========== buildTemplateData Tests ==========
  
  "buildTemplateData" should "calculate totals correctly" in {
    val summaries = Seq(
      DepartmentSummary(
        department = "IT Services",
        issues = Seq.empty,
        totalCount = 3,
        expiredCount = 2,
        expiringCount = 1,
        missingCount = 0,
        notApprovedCount = 0
      ),
      DepartmentSummary(
        department = "HR",
        issues = Seq.empty,
        totalCount = 2,
        expiredCount = 0,
        expiringCount = 0,
        missingCount = 1,
        notApprovedCount = 1
      )
    )
    
    val templateData = ManagerDigestService.buildTemplateData(
      summaries, "manager@example.com", "IT Services"
    )
    
    templateData.totalIssues should be(5)
    templateData.totalExpired should be(2)
    templateData.totalExpiring should be(1)
    templateData.totalMissing should be(1)
    templateData.totalNotApproved should be(1)
    templateData.departments.size should be(2)
  }
  
  it should "extract manager name from email" in {
    val summaries = Seq.empty[DepartmentSummary]
    
    val templateData = ManagerDigestService.buildTemplateData(
      summaries, "john.doe@example.com", "IT Services"
    )
    
    templateData.managerName should be("john.doe")
  }
  
  it should "handle email without @ symbol" in {
    val summaries = Seq.empty[DepartmentSummary]
    
    val templateData = ManagerDigestService.buildTemplateData(
      summaries, "invalid-email", "IT Services"
    )
    
    templateData.managerName should be("Manager")
  }
  
  it should "set correct department and summary date" in {
    val summaries = Seq.empty[DepartmentSummary]
    val today = LocalDate.now().toString
    
    val templateData = ManagerDigestService.buildTemplateData(
      summaries, "manager@example.com", "IT Services"
    )
    
    templateData.department should be("IT Services")
    templateData.summaryDate should be(today)
  }
  
  // ========== createDigestNotificationCommand Tests ==========
  
  "createDigestNotificationCommand" should "create command with correct issueType DIGEST" in {
    val config = NotificationConfig(
      settings = NotificationSettings(24, None),
      departmentManagers = Map.empty,
      defaultManager = "",
      digest = DigestConfig(enabled = true, "MONDAY", "09:00", "Australia/Sydney")
    )
    
    val templateData = DigestTemplateData(
      managerName = "Manager",
      department = "IT Services",
      summaryDate = "2024-12-23",
      departments = Seq.empty,
      totalIssues = 5,
      totalExpired = 2,
      totalExpiring = 1,
      totalMissing = 1,
      totalNotApproved = 1
    )
    
    val command = ManagerDigestService.createDigestNotificationCommand(
      "IT Services",
      "manager@example.com",
      templateData,
      config,
      "test-id",
      "2024-12-23T09:00:00Z"
    )
    
    command.issueType should be("DIGEST")
    command.userId should be("system")
    command.template should be("manager-digest.html")
    command.to should be(Seq("manager@example.com"))
    command.subject should include("Weekly WWCC Compliance Digest")
    command.subject should include("IT Services")
    command.subject should include("5 issues")
  }
  
  it should "use override recipient when configured" in {
    val config = NotificationConfig(
      settings = NotificationSettings(24, Some("override@example.com")),
      departmentManagers = Map.empty,
      defaultManager = "",
      digest = DigestConfig(enabled = true, "MONDAY", "09:00", "Australia/Sydney")
    )
    
    val templateData = DigestTemplateData(
      managerName = "Manager",
      department = "IT Services",
      summaryDate = "2024-12-23",
      departments = Seq.empty,
      totalIssues = 0,
      totalExpired = 0,
      totalExpiring = 0,
      totalMissing = 0,
      totalNotApproved = 0
    )
    
    val command = ManagerDigestService.createDigestNotificationCommand(
      "IT Services",
      "manager@example.com",
      templateData,
      config,
      "test-id",
      "2024-12-23T09:00:00Z"
    )
    
    command.to should be(Seq("override@example.com"))
    command.to should not contain "manager@example.com"
  }
  
  it should "set HIGH priority when expired issues exist" in {
    val config = NotificationConfig(
      settings = NotificationSettings(24, None),
      departmentManagers = Map.empty,
      defaultManager = "",
      digest = DigestConfig(enabled = true, "MONDAY", "09:00", "Australia/Sydney")
    )
    
    val templateData = DigestTemplateData(
      managerName = "Manager",
      department = "IT Services",
      summaryDate = "2024-12-23",
      departments = Seq.empty,
      totalIssues = 5,
      totalExpired = 2,
      totalExpiring = 1,
      totalMissing = 1,
      totalNotApproved = 1
    )
    
    val command = ManagerDigestService.createDigestNotificationCommand(
      "IT Services",
      "manager@example.com",
      templateData,
      config,
      "test-id",
      "2024-12-23T09:00:00Z"
    )
    
    command.priority should be("HIGH")
  }
  
  it should "set MEDIUM priority when no expired issues" in {
    val config = NotificationConfig(
      settings = NotificationSettings(24, None),
      departmentManagers = Map.empty,
      defaultManager = "",
      digest = DigestConfig(enabled = true, "MONDAY", "09:00", "Australia/Sydney")
    )
    
    val templateData = DigestTemplateData(
      managerName = "Manager",
      department = "IT Services",
      summaryDate = "2024-12-23",
      departments = Seq.empty,
      totalIssues = 3,
      totalExpired = 0,
      totalExpiring = 2,
      totalMissing = 1,
      totalNotApproved = 0
    )
    
    val command = ManagerDigestService.createDigestNotificationCommand(
      "IT Services",
      "manager@example.com",
      templateData,
      config,
      "test-id",
      "2024-12-23T09:00:00Z"
    )
    
    command.priority should be("MEDIUM")
  }
}

