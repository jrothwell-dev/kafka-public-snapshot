package com.demo.notification.models

import java.time.Instant
import java.util.UUID

case class NotificationRequest(
  id: String = UUID.randomUUID().toString,
  notificationType: NotificationType,
  template: String,
  recipients: Seq[String],
  data: Map[String, String],
  priority: Priority = Priority.Normal,
  requestedBy: String,
  requestedAt: Instant = Instant.now()
)

case class NotificationEvent(
  requestId: String,
  eventType: EventType,
  notificationType: NotificationType,
  recipients: Seq[String],
  status: Status,
  timestamp: Instant = Instant.now(),
  error: Option[String] = None,
  metadata: Map[String, String] = Map.empty
)

sealed trait NotificationType
object NotificationType {
  case object Email extends NotificationType
  case object SMS extends NotificationType
  case object Slack extends NotificationType
  
  def fromString(s: String): Option[NotificationType] = s.toLowerCase match {
    case "email" => Some(Email)
    case "sms" => Some(SMS)
    case "slack" => Some(Slack)
    case _ => None
  }
  
  def toString(nt: NotificationType): String = nt match {
    case Email => "email"
    case SMS => "sms"
    case Slack => "slack"
  }
}

sealed trait Priority
object Priority {
  case object Low extends Priority
  case object Normal extends Priority
  case object High extends Priority
  case object Critical extends Priority
  
  def fromString(s: String): Priority = s.toLowerCase match {
    case "low" => Low
    case "normal" => Normal
    case "high" => High
    case "critical" => Critical
    case _ => Normal
  }
}

sealed trait Status
object Status {
  case object Success extends Status
  case object Failed extends Status
  case object Retrying extends Status
}

sealed trait EventType
object EventType {
  case object Requested extends EventType
  case object Sending extends EventType
  case object Sent extends EventType
  case object Failed extends EventType
  case object Retrying extends EventType
}