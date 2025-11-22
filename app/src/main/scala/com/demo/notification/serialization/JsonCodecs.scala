package com.demo.notification.serialization

import com.demo.notification.models._
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._
import java.time.Instant

object JsonCodecs {
  
  implicit val instantEncoder: Encoder[Instant] = Encoder.encodeString.contramap(_.toString)
  implicit val instantDecoder: Decoder[Instant] = Decoder.decodeString.map(Instant.parse)
  
  implicit val notificationTypeEncoder: Encoder[NotificationType] = 
    Encoder.encodeString.contramap(NotificationType.toString)
  
  implicit val notificationTypeDecoder: Decoder[NotificationType] = 
    Decoder.decodeString.emap(s => 
      NotificationType.fromString(s).toRight(s"Invalid notification type: $s")
    )
  
  implicit val priorityEncoder: Encoder[Priority] = Encoder.encodeString.contramap {
    case Priority.Low => "low"
    case Priority.Normal => "normal"
    case Priority.High => "high"
    case Priority.Critical => "critical"
  }
  
  implicit val priorityDecoder: Decoder[Priority] = 
    Decoder.decodeString.map(Priority.fromString)
  
  implicit val statusEncoder: Encoder[Status] = Encoder.encodeString.contramap {
    case Status.Success => "success"
    case Status.Failed => "failed"
    case Status.Retrying => "retrying"
  }
  
  implicit val statusDecoder: Decoder[Status] = Decoder.decodeString.emap {
    case "success" => Right(Status.Success)
    case "failed" => Right(Status.Failed)
    case "retrying" => Right(Status.Retrying)
    case other => Left(s"Invalid status: $other")
  }
  
  implicit val eventTypeEncoder: Encoder[EventType] = Encoder.encodeString.contramap {
    case EventType.Requested => "requested"
    case EventType.Sending => "sending"
    case EventType.Sent => "sent"
    case EventType.Failed => "failed"
    case EventType.Retrying => "retrying"
  }
  
  implicit val eventTypeDecoder: Decoder[EventType] = Decoder.decodeString.emap {
    case "requested" => Right(EventType.Requested)
    case "sending" => Right(EventType.Sending)
    case "sent" => Right(EventType.Sent)
    case "failed" => Right(EventType.Failed)
    case "retrying" => Right(EventType.Retrying)
    case other => Left(s"Invalid event type: $other")
  }
  
  implicit val notificationRequestEncoder: Encoder[NotificationRequest] = deriveEncoder
  implicit val notificationRequestDecoder: Decoder[NotificationRequest] = deriveDecoder
  
  implicit val notificationEventEncoder: Encoder[NotificationEvent] = deriveEncoder
  implicit val notificationEventDecoder: Decoder[NotificationEvent] = deriveDecoder
}