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

  def startAndJoin(host: String = InetAddress.getLocalHost.getHostAddress, port: Int = Network.freePort, role: String = "DISTRIBUTED-SERVICES-NODE"): ServicesApi = start(host, port, role).joinSelf()
}

trait JoinableServices {
  // TODO : join from config
  def join(addr: String): ServicesApi
  def join(addr: Seq[String]): ServicesApi
  def joinSelf(): ServicesApi
}

trait ServicesApi {

  // TODO : search with meta searchable
  def stop(): Services

  def allServices(roles: Seq[String] = Seq(), version: Option[String] = None): Set[Service]

  def services(name: String, roles: Seq[String] = Seq(), version: Option[String] = None): Set[Service]

  def service(name: String, roles: Seq[String] = Seq(), version: Option[String] = None): Option[Service]

  def client(name: String, roles: Seq[String] = Seq(), version: Option[String] = None): Client

  def registerService(service: Service): Registration

  def printState(): Unit

  //def registerServiceListener(listener: ActorRef): Registration
}
