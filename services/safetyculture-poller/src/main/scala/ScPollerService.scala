package com.demo.safetyculture

import sttp.client3._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import java.util.Properties
import java.time.Instant
import redis.clients.jedis.{Jedis, JedisPool}
import java.security.MessageDigest

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
  
  def md5Hash(text: String): String = {
    val md = MessageDigest.getInstance("MD5")
    md.digest(text.getBytes).map("%02x".format(_)).mkString
  }
  
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
    val redisHost = sys.env.getOrElse("REDIS_HOST", "redis")
    
    println(s"[INFO] SafetyCulture Poller - polling every $pollInterval seconds")
    println(s"[INFO] Redis: $redisHost:6379")
    
    // Redis connection
    val jedisPool = new JedisPool(redisHost, 6379)
    
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
      val jedis = jedisPool.getResource
      
      try {
        getAllCredentials(apiToken) match {
          case Right(credentials) =>
            println(s"[INFO] Got ${credentials.length} credentials")
            
            // Filter for WWCC only
            val wwccCredentials = credentials.filter(_.document_type.name.toLowerCase.contains("children"))
            println(s"[INFO] Found ${wwccCredentials.length} WWCC credentials")
            
            var newCredentials = 0
            var unchangedCredentials = 0
            
            wwccCredentials.foreach { cred =>
              // Create a content hash for deduplication
              val credentialContent = Map(
                "user_id" -> cred.subject_user_id,
                "document_id" -> cred.document_id,
                "version_id" -> cred.document_version_id,
                "expiry_status" -> cred.metadata.expiry_status,
                "approval_status" -> cred.metadata.approval.status,
                "credential_number" -> cred.attributes.credential_number.getOrElse(""),
                "expiry_date" -> cred.attributes.expiry_period_end_date.map(d => 
                  s"${d.year}-${d.month}-${d.day}"
                ).getOrElse("")
              ).asJson.noSpaces
              
              val contentHash = md5Hash(credentialContent)
              val redisKey = s"sc:credential:${cred.subject_user_id}:${cred.document_id}"
              val lastHash = jedis.get(redisKey)
              
              if (lastHash == null || lastHash != contentHash) {
                // New or changed credential - send to Kafka
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
                
                // Store hash with 7 day expiry
                jedis.setex(redisKey, 604800, contentHash)
                newCredentials += 1
                
                println(s"[INFO] Published credential for ${cred.subject_user.first_name} ${cred.subject_user.last_name}")
              } else {
                unchangedCredentials += 1
              }
            }
            
            producer.flush()
            println(s"[INFO] Poll complete - New/Changed: $newCredentials, Unchanged: $unchangedCredentials")
            
          case Left(error) =>
            println(s"[ERROR] Failed to get credentials: $error")
        }
      } finally {
        jedis.close()
      }
      
      Thread.sleep(pollInterval * 1000)
    }
  }
}