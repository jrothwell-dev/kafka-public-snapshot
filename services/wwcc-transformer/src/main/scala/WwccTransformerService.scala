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

// Input models
case class RequiredUser(
  email: String,
  department: String,
  position: String,
  requiresWwcc: Boolean,
  startDate: String
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
  
  def main(args: Array[String]): Unit = {
    val kafkaBootstrap = sys.env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    
    println("[INFO] WWCC Transformer Service Starting")
    println("[INFO] Consumer group: wwcc-transformer-v3")  // New group name
    
    // Consumer props
    val consumerProps = new Properties()
    consumerProps.put("bootstrap.servers", kafkaBootstrap)
    consumerProps.put("group.id", "wwcc-transformer-v3")  // New clean group
    consumerProps.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    consumerProps.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    consumerProps.put("auto.offset.reset", "latest")  // Only process new messages
    consumerProps.put("enable.auto.commit", "true")
    
    val requiredConsumer = new KafkaConsumer[String, String](consumerProps)
    requiredConsumer.subscribe(List("reference.wwcc.required").asJava)
    
    val credentialConsumer = new KafkaConsumer[String, String](consumerProps)
    credentialConsumer.subscribe(List("raw.safetyculture.credentials").asJava)
    
    // Producer
    val producerProps = new Properties()
    producerProps.put("bootstrap.servers", kafkaBootstrap)
    producerProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    producerProps.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
    
    val producer = new KafkaProducer[String, String](producerProps)
    
    // State tracking
    val requiredUsers = mutable.Map[String, RequiredUser]()
    val userCredentials = mutable.Map[String, Credential]()
    val processedMissingUsers = mutable.Set[String]()
    var lastMissingCheck = System.currentTimeMillis()
    
    println("[INFO] Service ready, processing messages...")
    
    while (true) {
      try {
        // Read required users
        val requiredRecords = requiredConsumer.poll(Duration.ofMillis(100))
        requiredRecords.asScala.foreach { record =>
          try {
            decode[RequiredUser](record.value()) match {
              case Right(user) => 
                requiredUsers(user.email.toLowerCase) = user
                println(s"[INFO] Tracked required user: ${user.email}")
              case Left(e) => 
                println(s"[WARN] Skipping malformed required user record: ${e.getMessage.take(100)}")
            }
          } catch {
            case e: Exception => 
              println(s"[WARN] Error processing required user record: ${e.getMessage}")
          }
        }
        
        // Read credentials
        val credRecords = credentialConsumer.poll(Duration.ofMillis(100))
        credRecords.asScala.foreach { record =>
          try {
            decode[CredentialMessage](record.value()) match {
              case Right(msg) =>
                val cred = msg.credential
                userCredentials(cred.subject_user_id) = cred
                
                // Try multiple matching strategies
                val matchedUser = findMatchingUser(cred, requiredUsers.values.toList)
                
                val expiryDays = try {
                  cred.attributes.expiry_period_end_date.map { exp =>
                    ChronoUnit.DAYS.between(LocalDate.now(), exp.toLocalDate)
                  }
                } catch {
                  case _: Exception => None
                }
                
                val startDays = matchedUser.flatMap(u => 
                  try Some(ChronoUnit.DAYS.between(LocalDate.parse(u.startDate), LocalDate.now()))
                  catch { case _: Exception => None }
                )
                
                val flags = mutable.ListBuffer[String]()
                
                // Determine compliance status and flags
                val complianceStatus = if (matchedUser.isEmpty) {
                  flags += "NOT_IN_REQUIRED_LIST"
                  "UNEXPECTED"  // Found WWCC but not in required list
                } else if (cred.metadata.approval.status != "DOCUMENT_APPROVAL_STATUS_APPROVED") {
                  flags += "APPROVAL_PENDING"
                  "NOT_APPROVED"
                } else if (startDays.exists(_ < 0)) {
                  flags += "NOT_YET_STARTED"
                  "NOT_STARTED"
                } else cred.metadata.expiry_status match {
                  case "EXPIRY_STATUS_EXPIRED" => 
                    flags += "WWCC_EXPIRED"
                    "EXPIRED"
                  case "EXPIRY_STATUS_EXPIRING_SOON" => 
                    flags += "EXPIRING_WITHIN_30_DAYS"
                    "EXPIRING"
                  case _ => 
                    "COMPLIANT"
                }
                
                if (cred.attributes.credential_number.isEmpty) {
                  flags += "NO_CREDENTIAL_NUMBER"
                }
                
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
                
                producer.send(new ProducerRecord[String, String](
                  "processed.wwcc.status",
                  cred.subject_user_id,
                  compliance.asJson.noSpaces
                ))
                
                println(s"[INFO] Processed: ${cred.subject_user.first_name} ${cred.subject_user.last_name} - $complianceStatus ${if (flags.nonEmpty) s"[${flags.mkString(", ")}]" else ""}")
                
              case Left(e) => 
                println(s"[WARN] Skipping malformed credential record: ${e.getMessage.take(100)}")
            }
          } catch {
            case e: Exception => 
              println(s"[WARN] Error processing credential record: ${e.getMessage}")
          }
        }
        
        // Check for missing WWCCs periodically (every 30 seconds)
        if (System.currentTimeMillis() - lastMissingCheck > 30000) {
          checkMissingWwcc(requiredUsers, userCredentials, producer, processedMissingUsers)
          lastMissingCheck = System.currentTimeMillis()
        }
        
        Thread.sleep(1000) // Check every second
        
      } catch {
        case e: Exception =>
          println(s"[ERROR] Unexpected error in main loop: ${e.getMessage}")
          Thread.sleep(5000) // Wait before retrying
      }
    }
  }
  
  def findMatchingUser(cred: Credential, requiredUsers: List[RequiredUser]): Option[RequiredUser] = {
    val firstName = cred.subject_user.first_name.toLowerCase
    val lastName = cred.subject_user.last_name.toLowerCase
    
    // Try exact email match
    requiredUsers.find { user =>
      val emailPrefix = user.email.split("@").head.toLowerCase
      emailPrefix == s"$firstName.$lastName" ||
      emailPrefix == s"$firstName$lastName" ||
      emailPrefix == s"${firstName.head}$lastName" ||
      emailPrefix == s"$firstName${lastName.head}"
    }.orElse {
      // Try partial matches
      requiredUsers.find { user =>
        val emailPrefix = user.email.split("@").head.toLowerCase
        emailPrefix.contains(firstName) || emailPrefix.contains(lastName)
      }
    }
  }
  
  def checkMissingWwcc(
    requiredUsers: mutable.Map[String, RequiredUser],
    userCredentials: mutable.Map[String, Credential],
    producer: KafkaProducer[String, String],
    processedMissing: mutable.Set[String]
  ): Unit = {
    requiredUsers.foreach { case (email, user) =>
      if (!processedMissing.contains(email)) {
        val hasCredential = userCredentials.values.exists { cred =>
          val firstName = cred.subject_user.first_name.toLowerCase
          val lastName = cred.subject_user.last_name.toLowerCase
          val emailPrefix = email.split("@").head.toLowerCase
          
          emailPrefix == s"$firstName.$lastName" ||
          emailPrefix.contains(firstName) || 
          emailPrefix.contains(lastName)
        }
        
        if (!hasCredential) {
          val startDays = try {
            Some(ChronoUnit.DAYS.between(LocalDate.parse(user.startDate), LocalDate.now()))
          } catch { case _: Exception => None }
          
          val flags = mutable.ListBuffer[String]("NO_CREDENTIAL_FOUND")
          val complianceStatus = if (startDays.exists(_ < 0)) {
            flags += "NOT_YET_STARTED"
            "NOT_STARTED"
          } else {
            flags += "MISSING_REQUIRED_WWCC"
            "MISSING"
          }
          
          val nameParts = email.split("@").head.split("\\.")
          val firstName = nameParts.headOption.map(_.capitalize).getOrElse("")
          val lastName = nameParts.lastOption.map(_.capitalize).getOrElse("")
          
          val compliance = WwccCompliance(
            userId = email,
            firstName = firstName,
            lastName = lastName,
            email = Some(email),
            department = Some(user.department),
            position = Some(user.position),
            startDate = Some(user.startDate),
            wwccNumber = None,
            expiryDate = None,
            daysUntilExpiry = None,
            daysSinceStart = startDays,
            safetyculture_status = "MISSING",
            approval_status = "MISSING",
            compliance_status = complianceStatus,
            flags = flags.toList,
            processedAt = Instant.now().toString
          )
          
          producer.send(new ProducerRecord[String, String](
            "processed.wwcc.status",
            email,
            compliance.asJson.noSpaces
          ))
          
          processedMissing += email
          println(s"[INFO] Flagged missing WWCC: $email - $complianceStatus [${flags.mkString(", ")}]")
        }
      }
    }
  }
}