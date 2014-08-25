package com.distributedstuff.services.api

import java.net.InetAddress

import akka.actor.ActorRef
import com.distributedstuff.services.common.{IdGenerator, Configuration, Network}
import com.distributedstuff.services.internal.ServiceDirectory

import scala.concurrent.{ExecutionContext, Future}

object Services {
  def apply() = new Services(IdGenerator.token(6))
  def apply(name: String) = new Services(name)
  def apply(configuration: Configuration) = new Services(IdGenerator.token(6), configuration)
  def apply(name: String, configuration: Configuration) = new Services(name, configuration)
}

class Services(name: String, configuration: Configuration = Configuration.load()) {
  // TODO : start from config
  // Tiny bootstrap piece to bind with internal API
  def start(host: String = InetAddress.getLocalHost.getHostAddress, port: Int = Network.freePort, role: String = "DISTRIBUTED-SERVICES-NODE"): JoinableServices = {
    ServiceDirectory.start(name, host, port, role, configuration)
  }
}

trait JoinableServices {
  // TODO : join from config
  def join(addr: String): ServicesApi
  def join(addr: Seq[String]): ServicesApi
  def joinSelf(): ServicesApi
}

trait ServicesApi {

  // TODO : add version management
  // TODO : merge roles in API with default param values
  // TODO : search with meta searchable
  def stop(): Services

  def services(): Map[String, Set[Service]]

  def services(roles: Seq[String]): Map[String, Set[Service]]

  def services(name: String): Set[Service]

  def services(name: String, roles: Seq[String]): Set[Service]

  def service(name: String): Option[Service]

  def service(name: String, roles: Seq[String]): Option[Service]

  def client(name: String): Client

  def client(name: String, roles: Seq[String]): Client

  def registerService(service: Service): Registration

  //def registerServiceListener(listener: ActorRef): Registration
}
