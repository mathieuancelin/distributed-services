package com.distributedstuff.services.api

import java.net.InetAddress
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem}
import com.codahale.metrics.MetricRegistry
import com.distributedstuff.services.common.{Configuration, IdGenerator, Logger, Network}
import com.distributedstuff.services.internal.ServiceDirectory
import play.api.libs.json.JsArray

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

/**
 * Bootstrap API
 */
object Services {
  private val STANDARD_ROLE = "DISTRIBUTED-SERVICES-NODE"
  private val STANDARD_NAME = "DISTRIBUTED-SERVICES"

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
    val name = configuration.getString("services.nodename").getOrElse(STANDARD_NAME)
    new Services(name, configuration, None).bootFromConfig()
  }

  def bootFromConfig(configuration: Configuration, metrics: MetricRegistry): (ServicesApi, List[Registration]) = {
    val name = configuration.getString("services.nodename").getOrElse(STANDARD_NAME)
    new Services(name, configuration, Some(metrics)).bootFromConfig()
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
   * @return
   */
  def bootFromConfig(): (ServicesApi, List[Registration]) = {
    val services = startFromConfig()
    val exposed = services.exposeFromConfig()
    (services, exposed)
  }

  /**
   * Start the current node base on configuration.
   * Will not expose services automatically.
   *
   * @return
   */
  def startFromConfig(): ServicesApi = {
    import collection.JavaConversions._
    val host = configuration.getString("services.boot.host").getOrElse(InetAddress.getLocalHost.getHostAddress)
    val port = configuration.getInt("services.boot.port").getOrElse(Network.freePort)
    val role = configuration.getString("services.boot.role").getOrElse(Services.STANDARD_ROLE)
    val seedsOpt = configuration.getStringList("services.boot.seeds")
    Logger("Services").info(s"Starting system $role@$host:$port")
    val joinable = start(host, port, role)
    Logger("Services").info(s"Joining seed $seedsOpt")
    seedsOpt.map(seeds => joinable.join(seeds.toSeq)).getOrElse(joinable.joinSelf())
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
  def asyncAllServices(roles: Seq[String] = Seq(), version: Option[String] = None): Future[Set[Service]]
  def allServices(roles: Seq[String] = Seq(), version: Option[String] = None): Set[Service] = Await.result(asyncAllServices(roles, version), Duration(10, TimeUnit.SECONDS))

  /**
   * Find all services in the cluster that match name and query
   * @param name the name of the service
   * @param roles roles of the service
   * @param version version of the service
   */
  def asyncServices(name: String, roles: Seq[String] = Seq(), version: Option[String] = None): Future[Set[Service]]
  def services(name: String, roles: Seq[String] = Seq(), version: Option[String] = None): Set[Service] = Await.result(asyncServices(name, roles, version), Duration(10, TimeUnit.SECONDS))

  /**
   * Find the first service in the cluster that match name and query
   * @param name the name of the service
   * @param roles roles of the service
   * @param version version of the service
   */
  def asyncService(name: String, roles: Seq[String] = Seq(), version: Option[String] = None): Future[Option[Service]]
  def service(name: String, roles: Seq[String] = Seq(), version: Option[String] = None): Option[Service] = Await.result(asyncService(name, roles, version), Duration(10, TimeUnit.SECONDS))

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
   * @return a registration object to be able to unregister the service at any time
   */
  def asyncRegisterService(service: Service): Future[Registration]
  def registerService(service: Service): Registration = Await.result(asyncRegisterService(service), Duration(10, TimeUnit.SECONDS))

  def printState(): Unit

  /**
   * Register a service listener to be informed when a new service arrives and leaves the cluster
   * @param listener actor ref that is a listener of LifecycleEvent
   * @return a registration object to be able to unregister the listener at any time
   */
  def registerServiceListener(listener: ActorRef): Registration

  /**
   * Automatically expose services description based on config file
   * @return the list of registration
   */
  def exposeFromConfig(): List[Registration]

  def actors(): ActorSystem

  def useMetrics(metrics: MetricRegistry): ServicesApi

  def getMetrics: JsArray
}
