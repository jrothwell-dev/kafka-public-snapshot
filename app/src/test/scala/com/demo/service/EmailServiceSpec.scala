package com.demo.service

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.demo.config.{ConfigLoader, SmtpConfig}

class EmailServiceSpec extends AnyFlatSpec with Matchers {

  "EmailService" should "send a test email" in {
    val config = ConfigLoader.load()
    val emailService = EmailService(config.smtp)
    
    val result = emailService.sendEmail(
      to = "isabellad@murrumbidgee.nsw.gov.au",
      subject = "Test Email from Kafka Demo - EmailService Test",
      body = """
        |This is a test email from the Kafka Demo application.
        |
        |This email was sent as part of automated testing to verify SMTP configuration.
        |
        |Test Details:
        | - Sender: notifications@murrumbidgee.nsw.gov.au
        | - SMTP Server: smtp.murrumbidgee.nsw.gov.au:25
        | - Test Type: ScalaTest Unit Test
        |
        |If you received this email, the email service is working correctly!
      """.stripMargin
    )
    
    result shouldBe Right(())
  }
  
  it should "send an HTML email" in {
    val config = ConfigLoader.load()
    val emailService = EmailService(config.smtp)
    
    val htmlBody = """
      |<html>
      |<body>
      |  <h2>Test Email - HTML Format</h2>
      |  <p>This is a <strong>test email</strong> from the Kafka Demo application.</p>
      |  <p>This demonstrates HTML email capability.</p>
      |  <hr>
      |  <p style="color: #666; font-size: 12px;">
      |    Sent from: notifications@murrumbidgee.nsw.gov.au
      |  </p>
      |</body>
      |</html>
    """.stripMargin
    
    val result = emailService.sendEmail(
      to = "isabellad@murrumbidgee.nsw.gov.au",
      subject = "Test Email from Kafka Demo - HTML Test",
      body = htmlBody,
      isHtml = true
    )
    
    result shouldBe Right(())
  }
}