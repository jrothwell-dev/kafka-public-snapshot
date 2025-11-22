package com.demo.config

import pureconfig._
import pureconfig.generic.auto._

case class AppConfig(
  app: AppSettings,
  database: DatabaseConfig,
  kafka: KafkaConfig,
  redis: RedisConfig,
  smtp: SmtpConfig,
  apiPoller: ApiPollerConfig,
  notificationService: NotificationServiceConfig
)

case class AppSettings(
  name: String,
  logLevel: String
)

case class DatabaseConfig(
  host: String,
  port: Int,
  name: String,
  user: String,
  password: String,
  url: String,
  driver: String,
  connectionPool: ConnectionPoolConfig
)

case class ConnectionPoolConfig(
  maxPoolSize: Int,
  minIdle: Int
)

case class KafkaConfig(
  bootstrapServers: String,
  topics: KafkaTopics,
  producer: KafkaProducerConfig,
  consumer: KafkaConsumerConfig
)

case class KafkaTopics(
  userEvents: String,
  databaseChanges: String,
  complianceIssues: String,
  notificationRequests: String,
  notificationEvents: String
)

case class KafkaProducerConfig(
  acks: String,
  retries: Int,
  batchSize: Int,
  lingerMs: Int
)

case class KafkaConsumerConfig(
  groupId: String,
  autoOffsetReset: String,
  enableAutoCommit: Boolean,
  maxPollRecords: Int
)

case class RedisConfig(
  host: String,
  port: Int,
  database: Int,
  cache: RedisCacheConfig
)

case class RedisCacheConfig(
  defaultTtl: Int,
  apiResponseTtl: Int
)

case class SmtpConfig(
  host: String,
  port: Int,
  from: String,
  auth: Boolean,
  starttls: Boolean
)

case class ApiPollerConfig(
  pollInterval: Int,
  timeout: Int
)

case class NotificationServiceConfig(
  enabled: Boolean,
  pollInterval: Int,
  maxRetries: Int,
  retryDelay: Int,
  rateLimit: RateLimitConfig
)

case class RateLimitConfig(
  enabled: Boolean,
  maxPerMinute: Int
)