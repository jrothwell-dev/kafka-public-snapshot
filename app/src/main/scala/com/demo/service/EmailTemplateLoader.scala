package com.demo.service

import scala.io.Source
import scala.util.{Try, Using}

object EmailTemplateLoader {
  
  case class EmailTemplate(to: Seq[String], subject: String, body: String)
  
  def loadTemplate(templateName: String): Either[String, EmailTemplate] = {
    val resourcePath = s"email-templates/$templateName"
    
    Try {
      Using(Source.fromResource(resourcePath)) { source =>
        val lines = source.getLines().toList
        
        // Parse TO line (can be comma-separated for multiple recipients)
        val to = lines.headOption match {
          case Some(line) if line.startsWith("TO:") => 
            line.stripPrefix("TO:").trim.split(",").map(_.trim).toSeq
          case _ => 
            throw new IllegalArgumentException(s"Template must start with 'TO:' line")
        }
        
        // Parse SUBJECT line
        val subject = lines.drop(1).headOption match {
          case Some(line) if line.startsWith("SUBJECT:") => 
            line.stripPrefix("SUBJECT:").trim
          case _ => 
            throw new IllegalArgumentException(s"Template must have 'SUBJECT:' as second line")
        }
        
        // Rest is the body (skip TO, SUBJECT, and any blank lines)
        val body = lines.drop(2)
          .dropWhile(_.trim.isEmpty)
          .mkString("\n")
        
        EmailTemplate(to, subject, body)
      }.get
    }.toEither.left.map(_.getMessage)
  }
  
  def loadAndReplace(
    templateName: String, 
    replacements: Map[String, String]
  ): Either[String, EmailTemplate] = {
    loadTemplate(templateName).map { template =>
      // Replace in TO addresses
      val replacedTo = template.to.map { address =>
        replacements.foldLeft(address) {
          case (text, (key, value)) => text.replace(s"{{$key}}", value)
        }
      }
      
      // Replace in subject
      val replacedSubject = replacements.foldLeft(template.subject) {
        case (text, (key, value)) => text.replace(s"{{$key}}", value)
      }
      
      // Replace in body
      val replacedBody = replacements.foldLeft(template.body) {
        case (text, (key, value)) => text.replace(s"{{$key}}", value)
      }
      
      EmailTemplate(replacedTo, replacedSubject, replacedBody)
    }
  }
}