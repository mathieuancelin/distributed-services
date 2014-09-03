package com.distributedstuff.services.api

import java.net.InetAddress

import akka.actor.ActorRef
import com.distributedstuff.services.common.{IdGenerator, Configuration, Network}
import com.distributedstuff.services.internal.{ServiceRegistration, ServiceDirectory}
import com.typesafe.config.{ConfigFactory, Config, ConfigObject, ConfigValue}

import scala.concurrent.{ExecutionContext, Future}

object Services {
  private val STANDARD_ROLE = "DISTRIBUTED-SERVICES-NODE"
  def apply() = new Services(IdGenerator.token(6))
  def apply(name: String) = new Services(name)
  def apply(configuration: Configuration) = new Services(IdGenerator.token(6), configuration)
  def apply(name: String, configuration: Configuration) = new Services(name, configuration)
  def bootFromConfig(configuration: Configuration = Configuration.load()): (ServicesApi, List[Registration]) = {
    val name = configuration.getString("services.nodename").get
    new Services(name, configuration).bootFromConfig(configuration)
  }
}

class Services(name: String, configuration: Configuration = Configuration.load()) {
  // Tiny bootstrap piece to bind with internal API
  def start(host: String = InetAddress.getLocalHost.getHostAddress, port: Int = Network.freePort, role: String = Services.STANDARD_ROLE): JoinableServices = {
    ServiceDirectory.start(name, host, port, role, configuration)
  }

  def startAndJoin(host: String = InetAddress.getLocalHost.getHostAddress, port: Int = Network.freePort, role: String = Services.STANDARD_ROLE): ServicesApi = start(host, port, role).joinSelf()
  def bootFromConfig(configuration: Configuration = Configuration.load()): (ServicesApi, List[Registration]) = {
    import collection.JavaConversions._
    val host = configuration.getString("services.boot.host").getOrElse(InetAddress.getLocalHost.getHostAddress)
    val port = configuration.getInt("services.boot.port").getOrElse(Network.freePort)
    val role = configuration.getString("services.boot.role").getOrElse(role)
    val servicesToExpose = configuration.getObjectList("services.autoexpose").getOrElse(new java.util.ArrayList[ConfigObject]()).toList.map { obj =>
      val config = new Configuration(obj.toConfig)
      val name = config.getString("name").get // mandatory
      val url = config.getString("url").get   // mandatory
      val uid = config.getString("uid").getOrElse(IdGenerator.uuid)
      val version = config.getString("version")
      val roles = Option(obj.get("roles")).map(_.unwrapped().asInstanceOf[java.util.List[String]].toSeq).getOrElse(Seq[String]())
      Service(name = name, version = version, url = url, uid = uid, roles = roles)
    }
    val seed = configuration.getString("services.join.seed")
    val joinable = start(host, port, role)
    val services = seed.map(seed => joinable.join(seed)).getOrElse(joinable.joinSelf())
    val registered = servicesToExpose.map(service => services.registerService(service))
    (services, registered)
  }
}

trait JoinableServices {
  def join(addr: String): ServicesApi
  def join(addr: Seq[String]): ServicesApi
  def joinSelf(): ServicesApi
}

trait ServicesApi {

  def stop(): Services

  def allServices(roles: Seq[String] = Seq(), version: Option[String] = None): Set[Service]

  def services(name: String, roles: Seq[String] = Seq(), version: Option[String] = None): Set[Service]

  def service(name: String, roles: Seq[String] = Seq(), version: Option[String] = None): Option[Service]

  def client(name: String, roles: Seq[String] = Seq(), version: Option[String] = None): Client

  def registerService(service: Service): Registration

  def printState(): Unit

  def registerServiceListener(listener: ActorRef): Registration

  // TODO : search with meta searchable
}
