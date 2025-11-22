package com.demo

import com.demo.config.ConfigLoader
import com.demo.service.{EmailService, EmailTemplateLoader}
import java.time.LocalDateTime

object SendTestEmail {
  
  def main(args: Array[String]): Unit = {
    val config = ConfigLoader.load()
    val emailService = EmailService(config.smtp)
    
    println("=" * 60)
    println("Kafka Demo - Email Test Utility")
    println("=" * 60)
    println(s"SMTP Server: ${config.smtp.host}:${config.smtp.port}")
    println(s"From: ${config.smtp.from}")
    println("=" * 60)
    println()
    
    // Load and send plain text email
    println("Sending plain text email...")
    val textResult = EmailTemplateLoader.loadAndReplace(
      "test-email.txt",
      Map("timestamp" -> LocalDateTime.now().toString)
    ).flatMap { template =>
      println(s"To: ${template.to.mkString(", ")}")
      emailService.sendEmail(
        to = template.to,
        subject = template.subject,
        body = template.body,
        isHtml = false
      )
    }
    
    textResult match {
      case Right(_) => println("✓ Plain text email sent!")
      case Left(error) => println(s"✗ Failed: $error")
    }
    
    println()
    
    // Load and send HTML email
    println("Sending HTML email...")
    val htmlResult = EmailTemplateLoader.loadAndReplace(
      "test-email-html.html",
      Map("timestamp" -> LocalDateTime.now().toString)
    ).flatMap { template =>
      println(s"To: ${template.to.mkString(", ")}")
      emailService.sendEmail(
        to = template.to,
        subject = template.subject,
        body = template.body,
        isHtml = true
      )
    }
    
    htmlResult match {
      case Right(_) => println("✓ HTML email sent!")
      case Left(error) => println(s"✗ Failed: $error")
    }
    
    println()
    println("=" * 60)
    println("Emails sent based on template configuration")
    println()
    println("To customize emails, edit files in:")
    println("  app/src/main/resources/email-templates/")
    println()
    println("Template format:")
    println("  TO: email@example.com")
    println("  SUBJECT: Your subject here")
    println("  ")
    println("  Email body here...")
    println("=" * 60)
  }
}
