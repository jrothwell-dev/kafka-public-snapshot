package com.demo.notification.kafka

import com.demo.config.AppConfig
import com.demo.notification.models.{NotificationRequest, NotificationEvent}
import com.demo.notification.serialization.JsonCodecs._
import io.circe.syntax._
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord, RecordMetadata}
import java.util.Properties
import scala.util.{Try, Success, Failure}

class NotificationProducer(config: AppConfig) {
  
  private val props = new Properties()
  props.put("bootstrap.servers", config.kafka.bootstrapServers)
  props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
  props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
  props.put("acks", config.kafka.producer.acks)
  props.put("retries", config.kafka.producer.retries.toString)
  
  private val producer = new KafkaProducer[String, String](props)
  
  def publishRequest(request: NotificationRequest): Either[String, Unit] = {
    Try {
      val json = request.asJson.noSpaces
      val record = new ProducerRecord[String, String](
        config.kafka.topics.notificationRequests,
        request.id,
        json
      )
      producer.send(record).get()
    } match {
      case Success(_) => Right(())
      case Failure(e) => Left(s"Failed to publish notification request: ${e.getMessage}")
    }
  }
  
  def publishEvent(event: NotificationEvent): Either[String, Unit] = {
    Try {
      val json = event.asJson.noSpaces
      val record = new ProducerRecord[String, String](
        config.kafka.topics.notificationEvents,
        event.requestId,
        json
      )
      producer.send(record).get()
    } match {
      case Success(_) => Right(())
      case Failure(e) => Left(s"Failed to publish notification event: ${e.getMessage}")
    }
  }
  
  def close(): Unit = producer.close()
}

object NotificationProducer {
  def apply(config: AppConfig): NotificationProducer = new NotificationProducer(config)
}