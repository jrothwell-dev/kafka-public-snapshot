package com.council.wwcc

import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import java.util.Properties
import java.time.{Duration, Instant, LocalDate}
import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters._
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._
import scala.collection.mutable
import redis.clients.jedis.{Jedis, JedisPool}
import java.security.MessageDigest

// Input models
case class RequiredUser(
  email: String,
  firstName: String,
  lastName: String,
  department: String,
  position: String,
  requiresWwcc: Boolean,
  startDate: String
)

case class RequiredUsersList(
  requiredUsers: Seq[RequiredUser],
  timestamp: String
)

case class ExpiryDate(year: Int, month: Int, day: Int) {
  def toLocalDate: LocalDate = LocalDate.of(year, month, day)
}

case class ApprovalInfo(status: String, reason: String)
case class Metadata(expiry_status: String, approval: ApprovalInfo)
case class SubjectUser(id: String, first_name: String, last_name: String)
case class DocumentType(id: String, name: String)
case class CredentialAttributes(
  expiry_period_start_date: Option[ExpiryDate],
  expiry_period_end_date: Option[ExpiryDate],
  credential_number: Option[String]
)
case class Credential(
  subject_user_id: String,
  document_id: String,
  attributes: CredentialAttributes,
  metadata: Metadata,
  subject_user: SubjectUser,
  document_type: DocumentType
)
case class CredentialMessage(credential: Credential, polledAt: String)

// Output model
case class WwccCompliance(
  userId: String,
  firstName: String,
  lastName: String,
  email: Option[String],
  department: Option[String],
  position: Option[String],
  startDate: Option[String],
  wwccNumber: Option[String],
  expiryDate: Option[String],
  daysUntilExpiry: Option[Long],
  daysSinceStart: Option[Long],
  safetyculture_status: String,
  approval_status: String,
  compliance_status: String,  // COMPLIANT, EXPIRED, EXPIRING, NOT_APPROVED, MISSING, NOT_STARTED, UNEXPECTED
  flags: List[String],  // Additional flags for special cases
  processedAt: String
)

object WwccTransformerService {
  
  def md5Hash(text: String): String = {
    val md = MessageDigest.getInstance("MD5")
    md.digest(text.getBytes).map("%02x".format(_)).mkString
  }
  
  // Pure function: determines compliance status based on various conditions
  def determineComplianceStatus(
    expiryStatus: String,
    approvalStatus: String,
    isInRequiredList: Boolean,
    daysUntilStart: Option[Long]
  ): String = {
    if (!isInRequiredList) {
      "UNEXPECTED"
    } else if (approvalStatus != "DOCUMENT_APPROVAL_STATUS_APPROVED") {
      "NOT_APPROVED"
    } else if (daysUntilStart.exists(_ < 0)) {
      "NOT_STARTED"
    } else expiryStatus match {
      case "EXPIRY_STATUS_EXPIRED" => "EXPIRED"
      case "EXPIRY_STATUS_EXPIRING_SOON" => "EXPIRING"
      case _ => "COMPLIANT"
    }
  }
  
  // Pure function: calculates days until expiry from a reference date
  def calculateDaysUntilExpiry(expiryDate: Option[ExpiryDate], referenceDate: LocalDate): Option[Long] = {
    expiryDate.flatMap { exp =>
      try {
        Some(ChronoUnit.DAYS.between(referenceDate, exp.toLocalDate))
      } catch {
        case _: Exception => None
      }
    }
  }
  
  // Pure function: calculates days since start from a reference date
  def calculateDaysSinceStart(startDate: Option[String], referenceDate: LocalDate): Option[Long] = {
    startDate.flatMap { dateStr =>
      try {
        Some(ChronoUnit.DAYS.between(LocalDate.parse(dateStr), referenceDate))
      } catch {
        case _: Exception => None
      }
    }
  }
  
  // Pure function: finds matching user from required users list
  def findMatchingUser(cred: Credential, requiredUsers: List[RequiredUser]): Option[RequiredUser] = {
    val credFirstName = cred.subject_user.first_name.toLowerCase.trim
    val credLastName = cred.subject_user.last_name.toLowerCase.trim
    
    requiredUsers.find { user =>
      user.firstName.toLowerCase.trim == credFirstName && 
      user.lastName.toLowerCase.trim == credLastName
    }
  }
  
  // Pure function: generates flags based on compliance status and other conditions
  def generateFlags(
    complianceStatus: String,
    hasCredentialNumber: Boolean,
    daysUntilStart: Option[Long]
  ): List[String] = {
    val flags = mutable.ListBuffer[String]()
    
    if (complianceStatus == "UNEXPECTED") {
      flags += "NOT_IN_REQUIRED_LIST"
    } else if (complianceStatus == "NOT_APPROVED") {
      flags += "APPROVAL_PENDING"
    } else if (complianceStatus == "NOT_STARTED") {
      flags += "NOT_YET_STARTED"
    } else if (complianceStatus == "EXPIRED") {
      flags += "WWCC_EXPIRED"
    } else if (complianceStatus == "EXPIRING") {
      flags += "EXPIRING_WITHIN_30_DAYS"
    }
    
    if (!hasCredentialNumber) {
      flags += "NO_CREDENTIAL_NUMBER"
    }
    
    flags.toList
  }
  
  def main(args: Array[String]): Unit = {
    val kafkaBootstrap = sys.env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    val redisHost = sys.env.getOrElse("REDIS_HOST", "redis")
    
    println("[INFO] WWCC Transformer Service Starting")
    println("[INFO] Consumer group: wwcc-transformer-v4")
    println(s"[INFO] Redis: $redisHost:6379")
    
    // Redis connection
    val jedisPool = new JedisPool(redisHost, 6379)
    
    // Required users consumer
    val requiredConsumerProps = new Properties()
    requiredConsumerProps.put("bootstrap.servers", kafkaBootstrap)
    requiredConsumerProps.put("group.id", "wwcc-transformer-required-v2")
    requiredConsumerProps.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    requiredConsumerProps.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    requiredConsumerProps.put("auto.offset.reset", "earliest")
    requiredConsumerProps.put("enable.auto.commit", "true")

    val requiredConsumer = new KafkaConsumer[String, String](requiredConsumerProps)
    requiredConsumer.subscribe(List("reference.wwcc.required").asJava)

    // Credentials consumer
    val credentialConsumerProps = new Properties()
    credentialConsumerProps.put("bootstrap.servers", kafkaBootstrap)
    credentialConsumerProps.put("group.id", "wwcc-transformer-v5")
    credentialConsumerProps.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    credentialConsumerProps.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    credentialConsumerProps.put("auto.offset.reset", "earliest")
    credentialConsumerProps.put("enable.auto.commit", "true")

    val credentialConsumer = new KafkaConsumer[String, String](credentialConsumerProps)
    credentialConsumer.subscribe(List("raw.safetyculture.credentials").asJava)
    
    // Producer
    val producerProps = new Properties()
    producerProps.put("bootstrap.servers", kafkaBootstrap)
    producerProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    producerProps.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    
    val producer = new KafkaProducer[String, String](producerProps)
    
    // State tracking
    var currentRequiredUsers: Map[String, RequiredUser] = Map.empty
    val knownCredentials = mutable.Set[String]()
    var lastRequiredUpdate: Option[String] = None
    var lastMissingCheck = System.currentTimeMillis()
    
    println("[INFO] Service ready, processing messages...")
    
    while (true) {
      val jedis = jedisPool.getResource
      try {
        // Read required users
        val requiredRecords = requiredConsumer.poll(Duration.ofMillis(100))
        requiredRecords.asScala.foreach { record =>
          try {
            decode[RequiredUsersList](record.value()) match {
              case Right(usersList) => 
                currentRequiredUsers = usersList.requiredUsers.map { user =>
                  s"${user.firstName.toLowerCase}_${user.lastName.toLowerCase}" -> user
                }.toMap
                lastRequiredUpdate = Some(usersList.timestamp)
                println(s"[INFO] Updated required users list: ${usersList.requiredUsers.size} users")
                
                // Clear known credentials to force re-check of all users
                knownCredentials.clear()
                
                // Clear missing user tracking when list updates
                val keysToDelete = jedis.keys("wwcc:missing:*")
                if (!keysToDelete.isEmpty) {
                  jedis.del(keysToDelete.toArray(new Array[String](keysToDelete.size)): _*)
                }
                
              case Left(e) => 
                println(s"[WARN] Failed to parse required users list: ${e.getMessage.take(200)}")
            }
          } catch {
            case e: Exception => 
              println(s"[WARN] Error processing required users list: ${e.getMessage}")
          }
        }
        
        // Read credentials
        val credRecords = credentialConsumer.poll(Duration.ofMillis(100))
        credRecords.asScala.foreach { record =>
          try {
            decode[CredentialMessage](record.value()) match {
              case Right(msg) =>
                val cred = msg.credential
                
                // Try multiple matching strategies
                val matchedUser = findMatchingUser(cred, currentRequiredUsers.values.toList)
                
                val now = LocalDate.now()
                val expiryDays = calculateDaysUntilExpiry(cred.attributes.expiry_period_end_date, now)
                
                val startDays = matchedUser.flatMap(u => 
                  calculateDaysSinceStart(Some(u.startDate), now)
                )
                
                // Determine compliance status
                val complianceStatus = determineComplianceStatus(
                  expiryStatus = cred.metadata.expiry_status,
                  approvalStatus = cred.metadata.approval.status,
                  isInRequiredList = matchedUser.isDefined,
                  daysUntilStart = startDays
                )
                
                // Generate flags
                val flags = generateFlags(
                  complianceStatus = complianceStatus,
                  hasCredentialNumber = cred.attributes.credential_number.isDefined,
                  daysUntilStart = startDays
                )
                
                val compliance = WwccCompliance(
                  userId = cred.subject_user_id,
                  firstName = cred.subject_user.first_name,
                  lastName = cred.subject_user.last_name,
                  email = matchedUser.map(_.email),
                  department = matchedUser.map(_.department),
                  position = matchedUser.map(_.position),
                  startDate = matchedUser.map(_.startDate),
                  wwccNumber = cred.attributes.credential_number,
                  expiryDate = cred.attributes.expiry_period_end_date.map(d => 
                    f"${d.year}-${d.month}%02d-${d.day}%02d"
                  ),
                  daysUntilExpiry = expiryDays,
                  daysSinceStart = startDays,
                  safetyculture_status = cred.metadata.expiry_status,
                  approval_status = cred.metadata.approval.status,
                  compliance_status = complianceStatus,
                  flags = flags.toList,
                  processedAt = Instant.now().toString
                )
                
                // Check if this compliance status has changed
                val complianceHash = md5Hash(compliance.asJson.noSpaces)
                val redisKey = s"wwcc:compliance:${cred.subject_user_id}"
                val lastHash = jedis.get(redisKey)
                
                if (lastHash == null || lastHash != complianceHash) {
                  producer.send(new ProducerRecord[String, String](
                    "processed.wwcc.status",
                    cred.subject_user_id,
                    compliance.asJson.noSpaces
                  ))
                  
                  // Store hash with 24 hour expiry
                  jedis.setex(redisKey, 86400, complianceHash)
                  
                  println(s"[INFO] Processed: ${cred.subject_user.first_name} ${cred.subject_user.last_name} - $complianceStatus")
                  val userKey = s"${cred.subject_user.first_name.toLowerCase}_${cred.subject_user.last_name.toLowerCase}"
                  knownCredentials.add(userKey)
                }
                
              case Left(e) => 
                println(s"[WARN] Skipping malformed credential record: ${e.getMessage.take(100)}")
            }
          } catch {
            case e: Exception => 
              println(s"[WARN] Error processing credential record: ${e.getMessage}")
          }
        }
        
        // Check for missing WWCCs periodically (every 60 seconds)
        if (System.currentTimeMillis() - lastMissingCheck > 60000) {
          if (currentRequiredUsers.nonEmpty) {
            checkMissingWwcc(currentRequiredUsers, jedisPool, producer, knownCredentials)
            lastMissingCheck = System.currentTimeMillis()
          }
        }
        
        Thread.sleep(1000)
        
      } catch {
        case e: Exception =>
          println(s"[ERROR] Unexpected error in main loop: ${e.getMessage}")
          Thread.sleep(5000)
      } finally {
        jedis.close()
      }
    }
  }
  
  def checkMissingWwcc(
    requiredUsers: Map[String, RequiredUser],
    jedisPool: JedisPool,
    producer: KafkaProducer[String, String],
    knownCredentials: mutable.Set[String]
  ): Unit = {
    val jedis = jedisPool.getResource
    try {
      requiredUsers.foreach { case (userKey, user) =>
        // Skip if user doesn't require WWCC
        if (!user.requiresWwcc) {
          // Continue to next user
        } else {
          val missingKey = s"wwcc:missing:${user.email}"
        
        // Only process if not recently processed (24 hour window)
        if (!jedis.exists(missingKey)) {
          // Check if we have a credential for this user
          val hasCredential = knownCredentials.contains(userKey)
          
          if (!hasCredential) {
            // Calculate days since start date
            val now = LocalDate.now()
            val startDays = calculateDaysSinceStart(Some(user.startDate), now)
            
            // Create a synthetic user ID based on email hash for missing users
            val userId = md5Hash(user.email).take(16)
            
            // Create MISSING compliance record
            val compliance = WwccCompliance(
              userId = userId,
              firstName = user.firstName,
              lastName = user.lastName,
              email = Some(user.email),
              department = Some(user.department),
              position = Some(user.position),
              startDate = Some(user.startDate),
              wwccNumber = None,
              expiryDate = None,
              daysUntilExpiry = None,
              daysSinceStart = startDays,
              safetyculture_status = "EXPIRY_STATUS_UNKNOWN",
              approval_status = "DOCUMENT_APPROVAL_STATUS_UNKNOWN",
              compliance_status = "MISSING",
              flags = List("NO_CREDENTIAL_FOUND", "MISSING_REQUIRED_WWCC"),
              processedAt = Instant.now().toString
            )
            
            // Check if this compliance status has changed
            val complianceHash = md5Hash(compliance.asJson.noSpaces)
            val complianceRedisKey = s"wwcc:compliance:${userId}"
            val lastHash = jedis.get(complianceRedisKey)
            
            if (lastHash == null || lastHash != complianceHash) {
              producer.send(new ProducerRecord[String, String](
                "processed.wwcc.status",
                userId,
                compliance.asJson.noSpaces
              ))
              
              // Store hash with 24 hour expiry
              jedis.setex(complianceRedisKey, 86400, complianceHash)
              
              // Mark as processed to prevent duplicate checks for 24 hours
              jedis.setex(missingKey, 86400, "processed")
              
              println(s"[INFO] Missing WWCC detected: ${user.firstName} ${user.lastName} (${user.email})")
            }
          }
        }
        }
      }
      producer.flush()
    } finally {
      jedis.close()
    }
  }
}