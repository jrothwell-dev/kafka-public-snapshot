package com.demo.safetyculture

import com.demo.config.SafetyCultureConfig
import sttp.client3._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._

// User search
case class UserSearchRequest(email: Seq[String])

case class User(
  email: String,
  firstname: String,
  lastname: String,
  id: String,
  status: String,
  seat_type: String
)

case class UserSearchResponse(users: Seq[User])

// Credential types
case class CredentialType(
  id: String,
  name: String,
  category: String
)

case class CredentialTypesResponse(
  credential_types: Seq[CredentialType]
)

// Credentials
case class CredentialMedia(
  id: String,
  token: String,
  filename: String,
  media_type: String
)

case class CredentialAttributes(
  media: Option[Seq[CredentialMedia]],
  expiry_period_start: Option[String],
  expiry_period_end: Option[String],
  credential_number: Option[String]
)

case class DocumentVersion(
  subject_org_id: String,
  subject_user_id: String,
  document_type_id: String,
  document_id: String,
  document_version_id: String,
  attributes: CredentialAttributes
)

case class CredentialsListResponse(
  next_page_token: String,
  latest_document_versions: Seq[DocumentVersion]
)

class SafetyCultureService(config: SafetyCultureConfig) {
  
  private val backend = HttpURLConnectionBackend()
  private val baseUrl = config.apiUrl
  
  def searchUserByEmail(email: String): Either[String, Option[User]] = {
    val url = s"$baseUrl/users/search"
    
    println(s"Searching for user: $email")
    
    val requestBody = UserSearchRequest(email = Seq(email)).asJson.noSpaces
    
    val request = basicRequest
      .post(uri"$url")
      .header("Authorization", s"Bearer ${config.apiToken}")
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .body(requestBody)
      .response(asString)
    
    handleResponse(request, s"searching for user $email") { body =>
      decode[UserSearchResponse](body).map(_.users.headOption)
    }
  }
  
  def listCredentialTypes(): Either[String, Seq[CredentialType]] = {
    val url = s"$baseUrl/credentials/v1/credential-types"
    
    println(s"Fetching credential types...")
    
    val requestBody = """{"document_category":"DOCUMENT_CATEGORY_LICENSES_AND_CREDENTIALS"}"""
    
    val request = basicRequest
      .post(uri"$url")
      .header("Authorization", s"Bearer ${config.apiToken}")
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .body(requestBody)
      .response(asString)
    
    handleResponse(request, "fetching credential types") { body =>
      decode[CredentialTypesResponse](body).map(_.credential_types)
    }
  }
  
  def getUserCredentials(
    userId: String, 
    credentialTypeId: Option[String] = None
  ): Either[String, Seq[DocumentVersion]] = {
    val url = s"$baseUrl/credentials/v1/credentials"
    
    println(s"Fetching credentials for user: $userId")
    
    val userUuid = userId.replace("user_", "")
    
    val typeFilter = credentialTypeId match {
      case Some(typeId) => s""","document_type_ids":["$typeId"]"""
      case None => ""
    }
    
    val requestBody = s"""{"filters":{"user_ids":["$userUuid"]$typeFilter}}"""
    
    println(s"Request body: $requestBody")
    
    val request = basicRequest
      .post(uri"$url")
      .header("Authorization", s"Bearer ${config.apiToken}")
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .body(requestBody)
      .response(asString)
    
    handleResponse(request, "fetching credentials") { body =>
      decode[CredentialsListResponse](body).map(_.latest_document_versions)
    }
  }
  
  private def handleResponse[T](
    request: Request[Either[String, String], Any],
    action: String
  )(parser: String => Either[io.circe.Error, T]): Either[String, T] = {
    
    try {
      val response = request.send(backend)
      
      println(s"HTTP ${response.code.code} when $action")
      
      response.code.code match {
        case 200 =>
          response.body match {
            case Right(body) =>
              println(s"Response: ${body.take(300)}...")
              parser(body).left.map(err => s"Parse error: ${err.getMessage}\nBody: ${body.take(500)}")
            case Left(err) =>
              Left(s"Empty response when $action")
          }
          
        case 401 =>
          Left(s"Authentication failed - check API token")
          
        case 403 =>
          Left(s"Permission denied - enable required permissions")
          
        case 404 =>
          Left(s"Endpoint not found")
          
        case code =>
          val errorMsg = response.body.left.getOrElse("No error details")
          Left(s"HTTP $code: $errorMsg")
      }
      
    } catch {
      case e: Exception =>
        e.printStackTrace()
        Left(s"Exception when $action: ${e.getMessage}")
    }
  }
  
  def close(): Unit = backend.close()
}