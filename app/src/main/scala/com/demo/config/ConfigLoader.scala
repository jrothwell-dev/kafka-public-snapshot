package com.demo.config

import pureconfig._
import pureconfig.generic.auto._
import com.typesafe.config.ConfigFactory

object ConfigLoader {
  
  def load(): AppConfig = {
    val config = ConfigFactory.load()
    
    ConfigSource.fromConfig(config).loadOrThrow[AppConfig]
  }
  
  def loadOrThrow(): AppConfig = {
    load()
  }
}