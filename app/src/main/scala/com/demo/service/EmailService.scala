package com.demo.service

import com.demo.config.SmtpConfig
import javax.mail._
import javax.mail.internet._
import java.util.Properties

class EmailService(smtpConfig: SmtpConfig) {
  
  private val properties = new Properties()
  properties.put("mail.smtp.host", smtpConfig.host)
  properties.put("mail.smtp.port", smtpConfig.port.toString)
  properties.put("mail.smtp.auth", smtpConfig.auth.toString)
  properties.put("mail.smtp.starttls.enable", smtpConfig.starttls.toString)
  
  private val session = Session.getInstance(properties)
  
  // Single recipient
  def sendEmail(
    to: String,
    subject: String,
    body: String,
    isHtml: Boolean = false
  ): Either[String, Unit] = {
    sendEmail(Seq(to), subject, body, isHtml)
  }
  
  // Multiple recipients
  def sendEmail(
    to: Seq[String],
    subject: String,
    body: String,
    isHtml: Boolean
  ): Either[String, Unit] = {
    try {
      val message = new MimeMessage(session)
      message.setFrom(new InternetAddress(smtpConfig.from))
      
      val recipients: Array[Address] = to.map(new InternetAddress(_)).toArray
      message.setRecipients(Message.RecipientType.TO, recipients)
      
      message.setSubject(subject)
      
      if (isHtml) {
        message.setContent(body, "text/html; charset=utf-8")
      } else {
        message.setText(body)
      }
      
      Transport.send(message)
      
      println(s"Email sent successfully to ${to.mkString(", ")}")
      Right(())
      
    } catch {
      case e: Exception =>
        val errorMsg = s"Failed to send email: ${e.getMessage}"
        println(errorMsg)
        e.printStackTrace()
        Left(errorMsg)
    }
  }
}

object EmailService {
  def apply(smtpConfig: SmtpConfig): EmailService = new EmailService(smtpConfig)
}