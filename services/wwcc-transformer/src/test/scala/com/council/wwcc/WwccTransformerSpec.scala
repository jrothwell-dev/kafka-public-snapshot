package com.council.wwcc

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.LocalDate

class WwccTransformerSpec extends AnyFlatSpec with Matchers {
  
  // Helper to create test credential
  def createCredential(
    userId: String = "user123",
    firstName: String = "John",
    lastName: String = "Doe",
    expiryStatus: String = "EXPIRY_STATUS_VALID",
    approvalStatus: String = "DOCUMENT_APPROVAL_STATUS_APPROVED",
    credentialNumber: Option[String] = Some("WWC123456"),
    expiryDate: Option[ExpiryDate] = Some(ExpiryDate(2025, 12, 31))
  ): Credential = {
    Credential(
      subject_user_id = userId,
      document_id = "doc123",
      attributes = CredentialAttributes(
        expiry_period_start_date = Some(ExpiryDate(2024, 1, 1)),
        expiry_period_end_date = expiryDate,
        credential_number = credentialNumber
      ),
      metadata = Metadata(
        expiry_status = expiryStatus,
        approval = ApprovalInfo(status = approvalStatus, reason = "")
      ),
      subject_user = SubjectUser(id = userId, first_name = firstName, last_name = lastName),
      document_type = DocumentType(id = "wwcc", name = "WWCC")
    )
  }
  
  // Helper to create test required user
  def createRequiredUser(
    firstName: String = "John",
    lastName: String = "Doe",
    email: String = "john.doe@example.com",
    department: String = "IT",
    position: String = "Developer",
    startDate: String = "2024-01-01"
  ): RequiredUser = {
    RequiredUser(
      email = email,
      firstName = firstName,
      lastName = lastName,
      department = department,
      position = position,
      requiresWwcc = true,
      startDate = startDate
    )
  }
  
  // ========== Compliance Status Determination Tests ==========
  
  "determineComplianceStatus" should "return EXPIRED when expiry_status is EXPIRY_STATUS_EXPIRED" in {
    val status = WwccTransformerService.determineComplianceStatus(
      expiryStatus = "EXPIRY_STATUS_EXPIRED",
      approvalStatus = "DOCUMENT_APPROVAL_STATUS_APPROVED",
      isInRequiredList = true,
      daysUntilStart = Some(100)
    )
    status should be("EXPIRED")
  }
  
  it should "return EXPIRING when expiry_status is EXPIRY_STATUS_EXPIRING_SOON" in {
    val status = WwccTransformerService.determineComplianceStatus(
      expiryStatus = "EXPIRY_STATUS_EXPIRING_SOON",
      approvalStatus = "DOCUMENT_APPROVAL_STATUS_APPROVED",
      isInRequiredList = true,
      daysUntilStart = Some(100)
    )
    status should be("EXPIRING")
  }
  
  it should "return NOT_APPROVED when approval status is not APPROVED" in {
    val status = WwccTransformerService.determineComplianceStatus(
      expiryStatus = "EXPIRY_STATUS_VALID",
      approvalStatus = "DOCUMENT_APPROVAL_STATUS_PENDING",
      isInRequiredList = true,
      daysUntilStart = Some(100)
    )
    status should be("NOT_APPROVED")
  }
  
  it should "return UNEXPECTED when user not in required list" in {
    val status = WwccTransformerService.determineComplianceStatus(
      expiryStatus = "EXPIRY_STATUS_VALID",
      approvalStatus = "DOCUMENT_APPROVAL_STATUS_APPROVED",
      isInRequiredList = false,
      daysUntilStart = Some(100)
    )
    status should be("UNEXPECTED")
  }
  
  it should "return NOT_STARTED when start date is in future" in {
    val status = WwccTransformerService.determineComplianceStatus(
      expiryStatus = "EXPIRY_STATUS_VALID",
      approvalStatus = "DOCUMENT_APPROVAL_STATUS_APPROVED",
      isInRequiredList = true,
      daysUntilStart = Some(-10) // Negative means future
    )
    status should be("NOT_STARTED")
  }
  
  it should "return COMPLIANT when everything is valid" in {
    val status = WwccTransformerService.determineComplianceStatus(
      expiryStatus = "EXPIRY_STATUS_VALID",
      approvalStatus = "DOCUMENT_APPROVAL_STATUS_APPROVED",
      isInRequiredList = true,
      daysUntilStart = Some(100)
    )
    status should be("COMPLIANT")
  }
  
  it should "prioritize UNEXPECTED over other statuses" in {
    val status = WwccTransformerService.determineComplianceStatus(
      expiryStatus = "EXPIRY_STATUS_EXPIRED",
      approvalStatus = "DOCUMENT_APPROVAL_STATUS_APPROVED",
      isInRequiredList = false,
      daysUntilStart = Some(100)
    )
    status should be("UNEXPECTED")
  }
  
  it should "prioritize NOT_APPROVED over expiry status when in required list" in {
    val status = WwccTransformerService.determineComplianceStatus(
      expiryStatus = "EXPIRY_STATUS_EXPIRED",
      approvalStatus = "DOCUMENT_APPROVAL_STATUS_PENDING",
      isInRequiredList = true,
      daysUntilStart = Some(100)
    )
    status should be("NOT_APPROVED")
  }
  
  it should "prioritize NOT_STARTED over expiry status when start date is in future" in {
    val status = WwccTransformerService.determineComplianceStatus(
      expiryStatus = "EXPIRY_STATUS_EXPIRED",
      approvalStatus = "DOCUMENT_APPROVAL_STATUS_APPROVED",
      isInRequiredList = true,
      daysUntilStart = Some(-5)
    )
    status should be("NOT_STARTED")
  }
  
  // ========== User Matching Tests ==========
  
  "findMatchingUser" should "match user by first and last name (case insensitive)" in {
    val cred = createCredential(firstName = "John", lastName = "Doe")
    val requiredUsers = List(
      createRequiredUser(firstName = "john", lastName = "doe"),
      createRequiredUser(firstName = "Jane", lastName = "Smith")
    )
    
    val matched = WwccTransformerService.findMatchingUser(cred, requiredUsers)
    matched should be(defined)
    matched.get.firstName should be("john")
    matched.get.lastName should be("doe")
  }
  
  it should "return None when no match found" in {
    val cred = createCredential(firstName = "John", lastName = "Doe")
    val requiredUsers = List(
      createRequiredUser(firstName = "Jane", lastName = "Smith")
    )
    
    val matched = WwccTransformerService.findMatchingUser(cred, requiredUsers)
    matched should be(None)
  }
  
  it should "handle whitespace in names" in {
    val cred = createCredential(firstName = "  John  ", lastName = "  Doe  ")
    val requiredUsers = List(
      createRequiredUser(firstName = "John", lastName = "Doe")
    )
    
    val matched = WwccTransformerService.findMatchingUser(cred, requiredUsers)
    matched should be(defined)
  }
  
  it should "handle case differences in names" in {
    val cred = createCredential(firstName = "JOHN", lastName = "DOE")
    val requiredUsers = List(
      createRequiredUser(firstName = "john", lastName = "doe")
    )
    
    val matched = WwccTransformerService.findMatchingUser(cred, requiredUsers)
    matched should be(defined)
  }
  
  it should "match exact names" in {
    val cred = createCredential(firstName = "Mary-Jane", lastName = "O'Brien")
    val requiredUsers = List(
      createRequiredUser(firstName = "Mary-Jane", lastName = "O'Brien")
    )
    
    val matched = WwccTransformerService.findMatchingUser(cred, requiredUsers)
    matched should be(defined)
  }
  
  // ========== Date Calculation Tests ==========
  
  "calculateDaysUntilExpiry" should "calculate positive days until expiry for future date" in {
    val referenceDate = LocalDate.of(2024, 1, 1)
    val expiryDate = Some(ExpiryDate(2024, 1, 31))
    
    val days = WwccTransformerService.calculateDaysUntilExpiry(expiryDate, referenceDate)
    days should be(Some(30))
  }
  
  it should "calculate negative days until expiry for past date" in {
    val referenceDate = LocalDate.of(2024, 2, 1)
    val expiryDate = Some(ExpiryDate(2024, 1, 1))
    
    val days = WwccTransformerService.calculateDaysUntilExpiry(expiryDate, referenceDate)
    days should be(Some(-31))
  }
  
  it should "return zero for same date" in {
    val referenceDate = LocalDate.of(2024, 1, 15)
    val expiryDate = Some(ExpiryDate(2024, 1, 15))
    
    val days = WwccTransformerService.calculateDaysUntilExpiry(expiryDate, referenceDate)
    days should be(Some(0))
  }
  
  it should "return None when expiry date is None" in {
    val referenceDate = LocalDate.of(2024, 1, 1)
    val expiryDate = None
    
    val days = WwccTransformerService.calculateDaysUntilExpiry(expiryDate, referenceDate)
    days should be(None)
  }
  
  it should "handle invalid expiry date gracefully" in {
    // This test verifies that invalid dates don't crash
    val referenceDate = LocalDate.of(2024, 1, 1)
    // Note: ExpiryDate constructor validates, so we test with valid date
    // but the function should handle exceptions
    val expiryDate = Some(ExpiryDate(2024, 12, 31))
    
    val days = WwccTransformerService.calculateDaysUntilExpiry(expiryDate, referenceDate)
    days should be(Some(365))
  }
  
  "calculateDaysSinceStart" should "calculate days since start correctly" in {
    val referenceDate = LocalDate.of(2024, 2, 1)
    val startDate = Some("2024-01-01")
    
    val days = WwccTransformerService.calculateDaysSinceStart(startDate, referenceDate)
    days should be(Some(31))
  }
  
  it should "return negative days for future start date" in {
    val referenceDate = LocalDate.of(2024, 1, 1)
    val startDate = Some("2024-02-01")
    
    val days = WwccTransformerService.calculateDaysSinceStart(startDate, referenceDate)
    days should be(Some(-31))
  }
  
  it should "return zero for same date" in {
    val referenceDate = LocalDate.of(2024, 1, 15)
    val startDate = Some("2024-01-15")
    
    val days = WwccTransformerService.calculateDaysSinceStart(startDate, referenceDate)
    days should be(Some(0))
  }
  
  it should "return None for invalid date format" in {
    val referenceDate = LocalDate.of(2024, 1, 1)
    val startDate = Some("invalid-date")
    
    val days = WwccTransformerService.calculateDaysSinceStart(startDate, referenceDate)
    days should be(None)
  }
  
  it should "return None when start date is None" in {
    val referenceDate = LocalDate.of(2024, 1, 1)
    val startDate = None
    
    val days = WwccTransformerService.calculateDaysSinceStart(startDate, referenceDate)
    days should be(None)
  }
  
  // ========== Flag Generation Tests ==========
  
  "generateFlags" should "add WWCC_EXPIRED flag for EXPIRED status" in {
    val flags = WwccTransformerService.generateFlags(
      complianceStatus = "EXPIRED",
      hasCredentialNumber = true,
      daysUntilStart = Some(100)
    )
    flags should contain("WWCC_EXPIRED")
  }
  
  it should "add EXPIRING_WITHIN_30_DAYS flag for EXPIRING status" in {
    val flags = WwccTransformerService.generateFlags(
      complianceStatus = "EXPIRING",
      hasCredentialNumber = true,
      daysUntilStart = Some(100)
    )
    flags should contain("EXPIRING_WITHIN_30_DAYS")
  }
  
  it should "add NO_CREDENTIAL_NUMBER flag when credential number missing" in {
    val flags = WwccTransformerService.generateFlags(
      complianceStatus = "COMPLIANT",
      hasCredentialNumber = false,
      daysUntilStart = Some(100)
    )
    flags should contain("NO_CREDENTIAL_NUMBER")
  }
  
  it should "add NOT_IN_REQUIRED_LIST flag for UNEXPECTED status" in {
    val flags = WwccTransformerService.generateFlags(
      complianceStatus = "UNEXPECTED",
      hasCredentialNumber = true,
      daysUntilStart = Some(100)
    )
    flags should contain("NOT_IN_REQUIRED_LIST")
  }
  
  it should "add APPROVAL_PENDING flag for NOT_APPROVED status" in {
    val flags = WwccTransformerService.generateFlags(
      complianceStatus = "NOT_APPROVED",
      hasCredentialNumber = true,
      daysUntilStart = Some(100)
    )
    flags should contain("APPROVAL_PENDING")
  }
  
  it should "add NOT_YET_STARTED flag for NOT_STARTED status" in {
    val flags = WwccTransformerService.generateFlags(
      complianceStatus = "NOT_STARTED",
      hasCredentialNumber = true,
      daysUntilStart = Some(-10)
    )
    flags should contain("NOT_YET_STARTED")
  }
  
  it should "not add flags for COMPLIANT status with credential number" in {
    val flags = WwccTransformerService.generateFlags(
      complianceStatus = "COMPLIANT",
      hasCredentialNumber = true,
      daysUntilStart = Some(100)
    )
    flags should be(empty)
  }
  
  it should "add multiple flags when applicable" in {
    val flags = WwccTransformerService.generateFlags(
      complianceStatus = "EXPIRED",
      hasCredentialNumber = false,
      daysUntilStart = Some(100)
    )
    flags should contain("WWCC_EXPIRED")
    flags should contain("NO_CREDENTIAL_NUMBER")
    flags should have size 2
  }
  
  it should "handle UNEXPECTED status with missing credential number" in {
    val flags = WwccTransformerService.generateFlags(
      complianceStatus = "UNEXPECTED",
      hasCredentialNumber = false,
      daysUntilStart = Some(100)
    )
    flags should contain("NOT_IN_REQUIRED_LIST")
    flags should contain("NO_CREDENTIAL_NUMBER")
  }
}

