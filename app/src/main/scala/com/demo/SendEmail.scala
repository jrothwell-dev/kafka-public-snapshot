package com.demo

import com.demo.config.ConfigLoader
import com.demo.service.{EmailService, EmailTemplateLoader}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object SendEmail {
  
  def main(args: Array[String]): Unit = {
    if (args.isEmpty) {
      println("Error: No template specified")
      println("Usage: sbt \"runMain com.demo.SendEmail <template-name>\"")
      println("Example: sbt \"runMain com.demo.SendEmail test-email.txt\"")
      System.exit(1)
    }
    
    val templateName = args(0)
    val config = ConfigLoader.load()
    val emailService = EmailService(config.smtp)
    
    val timestamp = LocalDateTime.now()
    val formattedTimestamp = timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    
    println("=" * 60)
    println("Kafka Demo - Send Email")
    println("=" * 60)
    println(s"Template: $templateName")
    println(s"Time: $formattedTimestamp")
    println("=" * 60)
    println()
    
    val isHtml = templateName.endsWith(".html")
    
    val result = EmailTemplateLoader.loadAndReplace(
      templateName,
      Map(
        "timestamp" -> formattedTimestamp,
        "issue_type" -> "Sample Issue Type",
        "severity" -> "HIGH",
        "detected_at" -> formattedTimestamp,
        "description" -> "This is a sample description for testing.",
        "affected_entity_type" -> "User",
        "affected_entity_id" -> "123"
      )
    ).flatMap { template =>
      println(s"To: ${template.to.mkString(", ")}")
      println(s"Subject: ${template.subject}")
      println(s"HTML: $isHtml")
      println()
      
      emailService.sendEmail(
        to = template.to,
        subject = template.subject,
        body = template.body,
        isHtml = isHtml
      )
    }
    
    result match {
      case Right(_) =>
        println()
        println("✓ Email sent successfully!")
        
      case Left(error) =>
        println()
        println(s"✗ Failed to send email: $error")
        System.exit(1)
    }
  }
}