package com.distributedstuff.services

import java.util.concurrent.Executors

import com.distributedstuff.services.api.Services
import com.distributedstuff.services.common.Configuration
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContext

object Main extends App {
  implicit val ec = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
  val config = Configuration.load().withValue("services.http.port", 9999).withValue("services.http.host", "localhost")
  Services("AutoNode").bootFromConfig(config)
}
