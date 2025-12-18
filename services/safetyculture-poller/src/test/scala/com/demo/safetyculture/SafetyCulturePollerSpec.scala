package com.demo.safetyculture

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SafetyCulturePollerSpec extends AnyFlatSpec with Matchers {
  
  // Helper to create test DocumentVersion
  def createDocumentVersion(
    userId: String = "user123",
    firstName: String = "John",
    lastName: String = "Doe",
    documentTypeName: String = "Working With Children Check",
    credentialNumber: Option[String] = Some("WWC123456"),
    expiryDate: Option[ExpiryDate] = Some(ExpiryDate(2025, 12, 31))
  ): DocumentVersion = {
    DocumentVersion(
      subject_org_id = "org123",
      subject_user_id = userId,
      document_type_id = "doc_type_123",
      document_id = "doc123",
      document_version_id = "version123",
      attributes = CredentialAttributes(
        media = None,
        expiry_period_start_date = Some(ExpiryDate(2024, 1, 1)),
        expiry_period_end_date = expiryDate,
        credential_number = credentialNumber
      ),
      metadata = Metadata(
        created_at = "2024-01-01T00:00:00Z",
        created_by_user = CreatedBy(id = "creator1", first_name = "Admin", last_name = "User"),
        last_modified = "2024-01-01T00:00:00Z",
        last_modified_by_user = CreatedBy(id = "creator1", first_name = "Admin", last_name = "User"),
        expiry_status = "EXPIRY_STATUS_VALID",
        approval = ApprovalInfo(status = "DOCUMENT_APPROVAL_STATUS_APPROVED", reason = "")
      ),
      subject_user = SubjectUser(id = userId, first_name = firstName, last_name = lastName),
      document_type = DocumentType(id = "wwcc", name = documentTypeName)
    )
  }
  
  // Sample valid JSON response
  def validCredentialsResponseJson: String = {
    """{
      "next_page_token": "token123",
      "latest_document_versions": [
        {
          "subject_org_id": "org123",
          "subject_user_id": "user123",
          "document_type_id": "wwcc",
          "document_id": "doc123",
          "document_version_id": "version123",
          "attributes": {
            "expiry_period_start_date": {"year": 2024, "month": 1, "day": 1},
            "expiry_period_end_date": {"year": 2025, "month": 12, "day": 31},
            "credential_number": "WWC123456"
          },
          "metadata": {
            "created_at": "2024-01-01T00:00:00Z",
            "created_by_user": {"id": "creator1", "first_name": "Admin", "last_name": "User"},
            "last_modified": "2024-01-01T00:00:00Z",
            "last_modified_by_user": {"id": "creator1", "first_name": "Admin", "last_name": "User"},
            "expiry_status": "EXPIRY_STATUS_VALID",
            "approval": {"status": "DOCUMENT_APPROVAL_STATUS_APPROVED", "reason": ""}
          },
          "subject_user": {"id": "user123", "first_name": "John", "last_name": "Doe"},
          "document_type": {"id": "wwcc", "name": "Working With Children Check"}
        }
      ]
    }"""
  }
  
  // ========== WWCC Filtering Tests ==========
  
  "isWwccCredential" should "identify WWCC credential by document type name containing 'children'" in {
    val cred = createDocumentVersion(documentTypeName = "Working With Children Check")
    ScPollerService.isWwccCredential(cred) should be(true)
  }
  
  it should "identify 'Working With Children Check' as WWCC" in {
    val cred = createDocumentVersion(documentTypeName = "Working With Children Check")
    ScPollerService.isWwccCredential(cred) should be(true)
  }
  
  it should "NOT identify 'Driver License' as WWCC" in {
    val cred = createDocumentVersion(documentTypeName = "Driver License")
    ScPollerService.isWwccCredential(cred) should be(false)
  }
  
  it should "be case insensitive when matching" in {
    val cred1 = createDocumentVersion(documentTypeName = "WORKING WITH CHILDREN CHECK")
    val cred2 = createDocumentVersion(documentTypeName = "working with children check")
    val cred3 = createDocumentVersion(documentTypeName = "Working With Children Check")
    
    ScPollerService.isWwccCredential(cred1) should be(true)
    ScPollerService.isWwccCredential(cred2) should be(true)
    ScPollerService.isWwccCredential(cred3) should be(true)
  }
  
  it should "match partial name containing 'children'" in {
    val cred = createDocumentVersion(documentTypeName = "Children's Services Credential")
    ScPollerService.isWwccCredential(cred) should be(true)
  }
  
  "filterWwccCredentials" should "filter list to only WWCC credentials" in {
    val credentials = Seq(
      createDocumentVersion(userId = "user1", documentTypeName = "Working With Children Check"),
      createDocumentVersion(userId = "user2", documentTypeName = "Driver License"),
      createDocumentVersion(userId = "user3", documentTypeName = "Working With Children Check"),
      createDocumentVersion(userId = "user4", documentTypeName = "First Aid Certificate")
    )
    
    val filtered = ScPollerService.filterWwccCredentials(credentials)
    filtered should have size 2
    filtered.map(_.subject_user_id) should contain("user1")
    filtered.map(_.subject_user_id) should contain("user3")
    filtered.map(_.subject_user_id) should not contain("user2")
    filtered.map(_.subject_user_id) should not contain("user4")
  }
  
  it should "handle empty credentials list" in {
    val credentials = Seq.empty[DocumentVersion]
    val filtered = ScPollerService.filterWwccCredentials(credentials)
    filtered should be(empty)
  }
  
  it should "return all credentials when all are WWCC" in {
    val credentials = Seq(
      createDocumentVersion(userId = "user1", documentTypeName = "Working With Children Check"),
      createDocumentVersion(userId = "user2", documentTypeName = "Working With Children Check")
    )
    
    val filtered = ScPollerService.filterWwccCredentials(credentials)
    filtered should have size 2
  }
  
  it should "return empty list when no WWCC credentials" in {
    val credentials = Seq(
      createDocumentVersion(userId = "user1", documentTypeName = "Driver License"),
      createDocumentVersion(userId = "user2", documentTypeName = "First Aid Certificate")
    )
    
    val filtered = ScPollerService.filterWwccCredentials(credentials)
    filtered should be(empty)
  }
  
  // ========== Response Parsing Tests ==========
  
  "parseCredentialsResponse" should "parse valid credentials response" in {
    val result = ScPollerService.parseCredentialsResponse(validCredentialsResponseJson)
    result should be('right)
    result.right.get should have size 1
    result.right.get.head.subject_user_id should be("user123")
    result.right.get.head.document_type.name should be("Working With Children Check")
  }
  
  it should "return error for invalid JSON" in {
    val invalidJson = "{ invalid json }"
    val result = ScPollerService.parseCredentialsResponse(invalidJson)
    result should be('left)
    result.left.get should not be empty
  }
  
  it should "handle empty latest_document_versions array" in {
    val emptyResponse = """{
      "next_page_token": "",
      "latest_document_versions": []
    }"""
    
    val result = ScPollerService.parseCredentialsResponse(emptyResponse)
    result should be('right)
    result.right.get should be(empty)
  }
  
  it should "parse all credential fields correctly" in {
    val result = ScPollerService.parseCredentialsResponse(validCredentialsResponseJson)
    result should be('right)
    
    val cred = result.right.get.head
    cred.subject_user_id should be("user123")
    cred.document_id should be("doc123")
    cred.document_version_id should be("version123")
    cred.attributes.credential_number should be(Some("WWC123456"))
    cred.attributes.expiry_period_end_date should be(Some(ExpiryDate(2025, 12, 31)))
    cred.subject_user.first_name should be("John")
    cred.subject_user.last_name should be("Doe")
    cred.metadata.expiry_status should be("EXPIRY_STATUS_VALID")
    cred.metadata.approval.status should be("DOCUMENT_APPROVAL_STATUS_APPROVED")
  }
  
  it should "parse multiple credentials" in {
    val multiResponse = """{
      "next_page_token": "token123",
      "latest_document_versions": [
        {
          "subject_org_id": "org123",
          "subject_user_id": "user1",
          "document_type_id": "wwcc",
          "document_id": "doc1",
          "document_version_id": "version1",
          "attributes": {
            "expiry_period_end_date": {"year": 2025, "month": 12, "day": 31},
            "credential_number": "WWC111"
          },
          "metadata": {
            "created_at": "2024-01-01T00:00:00Z",
            "created_by_user": {"id": "c1", "first_name": "A", "last_name": "B"},
            "last_modified": "2024-01-01T00:00:00Z",
            "last_modified_by_user": {"id": "c1", "first_name": "A", "last_name": "B"},
            "expiry_status": "EXPIRY_STATUS_VALID",
            "approval": {"status": "DOCUMENT_APPROVAL_STATUS_APPROVED", "reason": ""}
          },
          "subject_user": {"id": "user1", "first_name": "John", "last_name": "Doe"},
          "document_type": {"id": "wwcc", "name": "Working With Children Check"}
        },
        {
          "subject_org_id": "org123",
          "subject_user_id": "user2",
          "document_type_id": "wwcc",
          "document_id": "doc2",
          "document_version_id": "version2",
          "attributes": {
            "expiry_period_end_date": {"year": 2025, "month": 6, "day": 15},
            "credential_number": "WWC222"
          },
          "metadata": {
            "created_at": "2024-01-01T00:00:00Z",
            "created_by_user": {"id": "c1", "first_name": "A", "last_name": "B"},
            "last_modified": "2024-01-01T00:00:00Z",
            "last_modified_by_user": {"id": "c1", "first_name": "A", "last_name": "B"},
            "expiry_status": "EXPIRY_STATUS_VALID",
            "approval": {"status": "DOCUMENT_APPROVAL_STATUS_APPROVED", "reason": ""}
          },
          "subject_user": {"id": "user2", "first_name": "Jane", "last_name": "Smith"},
          "document_type": {"id": "wwcc", "name": "Working With Children Check"}
        }
      ]
    }"""
    
    val result = ScPollerService.parseCredentialsResponse(multiResponse)
    result should be('right)
    result.right.get should have size 2
    result.right.get.map(_.subject_user_id) should contain("user1")
    result.right.get.map(_.subject_user_id) should contain("user2")
  }
  
  // ========== Date Formatting Tests ==========
  
  "formatExpiryDate" should "format ExpiryDate to YYYY-MM-DD string" in {
    val date = Some(ExpiryDate(2025, 12, 31))
    val formatted = ScPollerService.formatExpiryDate(date)
    formatted should be(Some("2025-12-31"))
  }
  
  it should "handle single digit month and day" in {
    val date = Some(ExpiryDate(2025, 1, 5))
    val formatted = ScPollerService.formatExpiryDate(date)
    formatted should be(Some("2025-01-05"))
  }
  
  it should "return None for None input" in {
    val formatted = ScPollerService.formatExpiryDate(None)
    formatted should be(None)
  }
  
  it should "format dates with leading zeros" in {
    val date1 = Some(ExpiryDate(2025, 1, 1))
    val date2 = Some(ExpiryDate(2025, 9, 9))
    val date3 = Some(ExpiryDate(2025, 10, 10))
    
    ScPollerService.formatExpiryDate(date1) should be(Some("2025-01-01"))
    ScPollerService.formatExpiryDate(date2) should be(Some("2025-09-09"))
    ScPollerService.formatExpiryDate(date3) should be(Some("2025-10-10"))
  }
  
  // ========== Credential Field Extraction Tests ==========
  
  "CredentialAttributes" should "extract credential_number from attributes" in {
    val cred = createDocumentVersion(credentialNumber = Some("WWC123456"))
    cred.attributes.credential_number should be(Some("WWC123456"))
  }
  
  it should "handle missing credential_number" in {
    val cred = createDocumentVersion(credentialNumber = None)
    cred.attributes.credential_number should be(None)
  }
  
  it should "extract expiry dates from attributes" in {
    val expiryDate = Some(ExpiryDate(2025, 12, 31))
    val cred = createDocumentVersion(expiryDate = expiryDate)
    cred.attributes.expiry_period_end_date should be(expiryDate)
  }
  
  it should "extract user info from subject_user" in {
    val cred = createDocumentVersion(
      userId = "user123",
      firstName = "John",
      lastName = "Doe"
    )
    cred.subject_user.id should be("user123")
    cred.subject_user.first_name should be("John")
    cred.subject_user.last_name should be("Doe")
  }
  
  it should "extract document type information" in {
    val cred = createDocumentVersion(documentTypeName = "Working With Children Check")
    cred.document_type.id should be("wwcc")
    cred.document_type.name should be("Working With Children Check")
  }
  
  it should "extract metadata information" in {
    val cred = createDocumentVersion()
    cred.metadata.expiry_status should be("EXPIRY_STATUS_VALID")
    cred.metadata.approval.status should be("DOCUMENT_APPROVAL_STATUS_APPROVED")
  }
  
  // ========== Integration-style Tests ==========
  
  "parseCredentialsResponse and filterWwccCredentials" should "work together to filter WWCC credentials" in {
    val mixedResponse = """{
      "next_page_token": "token123",
      "latest_document_versions": [
        {
          "subject_org_id": "org123",
          "subject_user_id": "user1",
          "document_type_id": "wwcc",
          "document_id": "doc1",
          "document_version_id": "version1",
          "attributes": {
            "expiry_period_end_date": {"year": 2025, "month": 12, "day": 31},
            "credential_number": "WWC111"
          },
          "metadata": {
            "created_at": "2024-01-01T00:00:00Z",
            "created_by_user": {"id": "c1", "first_name": "A", "last_name": "B"},
            "last_modified": "2024-01-01T00:00:00Z",
            "last_modified_by_user": {"id": "c1", "first_name": "A", "last_name": "B"},
            "expiry_status": "EXPIRY_STATUS_VALID",
            "approval": {"status": "DOCUMENT_APPROVAL_STATUS_APPROVED", "reason": ""}
          },
          "subject_user": {"id": "user1", "first_name": "John", "last_name": "Doe"},
          "document_type": {"id": "wwcc", "name": "Working With Children Check"}
        },
        {
          "subject_org_id": "org123",
          "subject_user_id": "user2",
          "document_type_id": "license",
          "document_id": "doc2",
          "document_version_id": "version2",
          "attributes": {
            "expiry_period_end_date": {"year": 2025, "month": 6, "day": 15},
            "credential_number": "DL222"
          },
          "metadata": {
            "created_at": "2024-01-01T00:00:00Z",
            "created_by_user": {"id": "c1", "first_name": "A", "last_name": "B"},
            "last_modified": "2024-01-01T00:00:00Z",
            "last_modified_by_user": {"id": "c1", "first_name": "A", "last_name": "B"},
            "expiry_status": "EXPIRY_STATUS_VALID",
            "approval": {"status": "DOCUMENT_APPROVAL_STATUS_APPROVED", "reason": ""}
          },
          "subject_user": {"id": "user2", "first_name": "Jane", "last_name": "Smith"},
          "document_type": {"id": "license", "name": "Driver License"}
        }
      ]
    }"""
    
    val parseResult = ScPollerService.parseCredentialsResponse(mixedResponse)
    parseResult should be('right)
    
    val allCredentials = parseResult.right.get
    val wwccCredentials = ScPollerService.filterWwccCredentials(allCredentials)
    
    wwccCredentials should have size 1
    wwccCredentials.head.subject_user_id should be("user1")
    wwccCredentials.head.document_type.name should be("Working With Children Check")
  }
}

