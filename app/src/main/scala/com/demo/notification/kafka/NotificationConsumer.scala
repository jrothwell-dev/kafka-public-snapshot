package com.demo.notification.kafka

import com.demo.config.AppConfig
import com.demo.notification.models.NotificationRequest
import com.demo.notification.serialization.JsonCodecs._
import io.circe.parser._
import org.apache.kafka.clients.consumer.{ConsumerRecords, KafkaConsumer}
import java.time.Duration
import java.util.Properties
import scala.jdk.CollectionConverters._

class NotificationConsumer(config: AppConfig) {
  
  private val props = new Properties()
  props.put("bootstrap.servers", config.kafka.bootstrapServers)
  props.put("group.id", config.kafka.consumer.groupId)
  props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
  props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
  props.put("auto.offset.reset", config.kafka.consumer.autoOffsetReset)
  props.put("enable.auto.commit", config.kafka.consumer.enableAutoCommit.toString)
  props.put("max.poll.records", config.kafka.consumer.maxPollRecords.toString)
  
  private val consumer = new KafkaConsumer[String, String](props)
  consumer.subscribe(List(config.kafka.topics.notificationRequests).asJava)
  
  def poll(timeoutMs: Long): Seq[NotificationRequest] = {
    val records: ConsumerRecords[String, String] = consumer.poll(Duration.ofMillis(timeoutMs))
    
    records.asScala.toSeq.flatMap { record =>
      decode[NotificationRequest](record.value()) match {
        case Right(request) => Some(request)
        case Left(error) =>
          println(s"Failed to decode notification request: ${error.getMessage}")
          None
      }
    }
  }
  
  def commitSync(): Unit = consumer.commitSync()
  
  def close(): Unit = consumer.close()
}

object NotificationConsumer {
  def apply(config: AppConfig): NotificationConsumer = new NotificationConsumer(config)
}