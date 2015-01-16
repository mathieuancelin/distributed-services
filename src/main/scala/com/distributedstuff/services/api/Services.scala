package com.distributedstuff.services.api

import java.net.InetAddress

import akka.actor.ActorRef
import com.codahale.metrics.MetricRegistry
import com.distributedstuff.services.common.{IdGenerator, Configuration, Network}
import com.distributedstuff.services.internal.{ServiceRegistration, ServiceDirectory}
import com.typesafe.config.{ConfigFactory, Config, ConfigObject, ConfigValue}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Bootstrap API
 */
object Services {
  private val STANDARD_ROLE = "DISTRIBUTED-SERVICES-NODE"

  /**
   * @return a new Services instance
   */
  def apply() = new Services(IdGenerator.token(6), Configuration.load(), None)
  def apply(metrics: MetricRegistry) = new Services(IdGenerator.token(6), Configuration.load(), Some(metrics))
  /**
   * @return a new Services instance
   */
  def apply(name: String) = new Services(name)
  def apply(name: String, metrics: MetricRegistry) = new Services(name, Configuration.load(), Some(metrics))
  /**
   * @return a new Services instance
   */
  def apply(configuration: Configuration) = new Services(IdGenerator.token(6), configuration, None)
  def apply(configuration: Configuration, metrics: MetricRegistry) = new Services(IdGenerator.token(6), configuration, Some(metrics))
  /**
   * @return a new Services instance
   */
  def apply(name: String, configuration: Configuration) = new Services(name, configuration, None)
  def apply(name: String, configuration: Configuration, metrics: MetricRegistry) = new Services(name, configuration, Some(metrics))
  /**
   * @return a new Services instance based on the configuration
   */
  def bootFromConfig(configuration: Configuration = Configuration.load()): (ServicesApi, List[Registration]) = {
    val name = configuration.getString("services.nodename").get
    new Services(name, configuration, None).bootFromConfig(configuration)
  }

  def bootFromConfig(configuration: Configuration, metrics: MetricRegistry): (ServicesApi, List[Registration]) = {
    val name = configuration.getString("services.nodename").get
    new Services(name, configuration, Some(metrics)).bootFromConfig(configuration)
  }
}

/**
 * API to start nodes
 *
 * @param name name of the Services node
 * @param configuration configuration of the node
 */
class Services(name: String, configuration: Configuration = Configuration.load(), metrics: Option[MetricRegistry] = None) {

  /**
   * Start the current node.Tiny bootstrap piece to bind with internal API.
   *
   * @param host where your node can be contacted
   * @param port where your node can be contacted
   * @param role akka role
   * @return
   */
  def start(host: String = InetAddress.getLocalHost.getHostAddress, port: Int = Network.freePort, role: String = Services.STANDARD_ROLE): JoinableServices = {
    ServiceDirectory.start(name, host, port, role, configuration, metrics)
  }

  /**
   * Start the current node and join it
   *
   * @param host where your node can be contacted
   * @param port where your node can be contacted
   * @param role akka role
   * @return
   */
  def startAndJoin(host: String = InetAddress.getLocalHost.getHostAddress, port: Int = Network.freePort, role: String = Services.STANDARD_ROLE): ServicesApi = start(host, port, role).joinSelf()

  /**
   * Boot the current node based on configuration and automatically expose services
   *
   * @param configuration
   * @return
   */
  def bootFromConfig(configuration: Configuration = Configuration.load()): (ServicesApi, List[Registration]) = {
    val services = startFromConfig(configuration)
    val exposed = services.exposeFromConfig()
    (services, exposed)
  }

  /**
   * Start the current node base on configuration.
   * Will not expose services automatically.
   *
   * @param configuration
   * @return
   */
  def startFromConfig(configuration: Configuration = Configuration.load()): ServicesApi = {
    val host = configuration.getString("services.boot.host").getOrElse(InetAddress.getLocalHost.getHostAddress)
    val port = configuration.getInt("services.boot.port").getOrElse(Network.freePort)
    val role = configuration.getString("services.boot.role").getOrElse(Services.STANDARD_ROLE)
    val seed = configuration.getString("services.join.seed")
    val joinable = start(host, port, role)
    seed.map(seed => joinable.join(seed)).getOrElse(joinable.joinSelf())
  }
}

/**
 * API to join an existing cluster
 */
trait JoinableServices {
  /**
   * Join a seed node
   * @param addr address of the cluster seed
   * @return the final services API
   */
  def join(addr: String): ServicesApi

  /**
   * The a multiples seed nodes
   * @param addr address of the cluster seed
   * @return the final services API
   */
  def join(addr: Seq[String]): ServicesApi

  /**
   * Join itself as a cluster
   * @return the final services API
   */
  def joinSelf(): ServicesApi
}

/**
 * The main API to manipulation service desriptions registered in the cluster.
 */
trait ServicesApi {

  /**
   * Stop the current node. All services will leave the cluster.
   */
  def stop(): Services

  /**
   * Find all services in the cluster that match query
   * @param roles roles of the service
   * @param version version of the service
   */
  def allServices(roles: Seq[String] = Seq(), version: Option[String] = None): Set[Service]

  /**
   * Find all services in the cluster that match name and query
   * @param name the name of the service
   * @param roles roles of the service
   * @param version version of the service
   */
  def services(name: String, roles: Seq[String] = Seq(), version: Option[String] = None): Set[Service]

  /**
   * Find the first service in the cluster that match name and query
   * @param name the name of the service
   * @param roles roles of the service
   * @param version version of the service
   */
  def service(name: String, roles: Seq[String] = Seq(), version: Option[String] = None): Option[Service]

  /**
   * Find the first service in the cluster that match name and query
   * @param name the name of the service
   * @param roles roles of the service
   * @param version version of the service
   */
  def client(name: String, roles: Seq[String] = Seq(), version: Option[String] = None, retry: Int = 5): Client

  /**
   * Register a new service description in the cluster
   * @param service the new service description
   * @return a regisration object to be able to unregister the service at any time
   */
  def registerService(service: Service): Registration

  private[services] def printState(): Unit

  /**
   * Register a service listener to be informed when a new service arrives and leaves the cluster
   * @param listener actor ref that is a listener of LifecycleEvent
   * @return a regisration object to be able to unregister the listener at any time
   */
  def registerServiceListener(listener: ActorRef): Registration

  /**
   * Automatically expose services description based on config file
   * @return the list of registration
   */
  def exposeFromConfig(): List[Registration]
}
