package com.demo.safetyculture

import sttp.client3._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import java.util.Properties
import java.time.Instant

// Models matching actual API response
case class ExpiryDate(year: Int, month: Int, day: Int)
case class ApprovalInfo(status: String, reason: String)
case class CreatedBy(id: String, first_name: String, last_name: String)
case class Metadata(
  created_at: String,
  created_by_user: CreatedBy,
  last_modified: String,
  last_modified_by_user: CreatedBy,
  expiry_status: String,
  approval: ApprovalInfo
)
case class SubjectUser(id: String, first_name: String, last_name: String)
case class DocumentType(id: String, name: String)
case class CredentialMedia(id: String, token: String, filename: String, media_type: String)
case class CredentialAttributes(
  media: Option[Seq[CredentialMedia]],
  expiry_period_start_date: Option[ExpiryDate],
  expiry_period_end_date: Option[ExpiryDate],
  credential_number: Option[String]
)
case class DocumentVersion(
  subject_org_id: String,
  subject_user_id: String,
  document_type_id: String,
  document_id: String,
  document_version_id: String,
  attributes: CredentialAttributes,
  metadata: Metadata,
  subject_user: SubjectUser,
  document_type: DocumentType
)
case class CredentialsResponse(
  next_page_token: String,
  latest_document_versions: Seq[DocumentVersion]
)

object ScPollerService {
  
  def getAllCredentials(apiToken: String): Either[String, Seq[DocumentVersion]] = {
    val url = "https://api.safetyculture.io/credentials/v1/credentials"
    val backend = HttpURLConnectionBackend()
    
    val request = basicRequest
      .post(uri"$url")
      .header("Authorization", s"Bearer $apiToken")
      .header("Content-Type", "application/json")
      .body("""{"sort_direction":"SORT_DIRECTION_UNSPECIFIED","document_version_sort_field":"DOCUMENT_VERSION_SORT_FIELD_EXPIRY"}""")
      .response(asString)
    
    try {
      val response = request.send(backend)
      response.code.code match {
        case 200 => 
          response.body.flatMap(body => 
            decode[CredentialsResponse](body).left.map(_.getMessage)
          ).map(_.latest_document_versions)
        case code => 
          Left(s"HTTP $code: ${response.body.left.getOrElse("No error details")}")
      }
    } finally {
      backend.close()
    }
  }
  
  def main(args: Array[String]): Unit = {
    val apiToken = sys.env.getOrElse("SAFETYCULTURE_API_TOKEN", {
      println("[ERROR] SAFETYCULTURE_API_TOKEN required")
      sys.exit(1)
    })
    
    val kafkaBootstrap = sys.env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    val pollInterval = sys.env.getOrElse("POLL_INTERVAL", "300").toInt
    
    println(s"[INFO] SafetyCulture Poller - polling every $pollInterval seconds")
    
    // Kafka producer
    val props = new Properties()
    props.put("bootstrap.servers", kafkaBootstrap)
    props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    props.put("acks", "all")
    
    val producer = new KafkaProducer[String, String](props)
    
    // Polling loop
    while (true) {
      val timestamp = Instant.now()
      
      getAllCredentials(apiToken) match {
        case Right(credentials) =>
          println(s"[INFO] Got ${credentials.length} credentials")
          
          // Filter for WWCC only
          val wwccCredentials = credentials.filter(_.document_type.name.toLowerCase.contains("children"))
          println(s"[INFO] Found ${wwccCredentials.length} WWCC credentials")
          
          wwccCredentials.foreach { cred =>
            val message = Map(
              "credential" -> cred.asJson,
              "polledAt" -> timestamp.toString.asJson
            ).asJson.noSpaces
            
            val record = new ProducerRecord[String, String](
              "raw.safetyculture.credentials",
              cred.subject_user_id,
              message
            )
            producer.send(record)
          }
          
          producer.flush()
          println(s"[INFO] Published ${wwccCredentials.length} WWCC credentials")
          
        case Left(error) =>
          println(s"[ERROR] Failed to get credentials: $error")
      }
      
      Thread.sleep(pollInterval * 1000)
    }
  }
}