package com.distributedstuff.services.internal

import java.util
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}

import akka.actor._
import akka.cluster.Cluster
import akka.remote.transport.ActorTransportAdapter.ListenerRegistered
import akka.util.Timeout
import com.codahale.metrics.{JmxReporter, MetricRegistry}
import com.distributedstuff.services.api._
import com.distributedstuff.services.common.{Configuration, Futures, Logger}
import com.typesafe.config.ConfigFactory
import org.joda.time.DateTime
import play.api.libs.json.{JsString, JsArray, Json}

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.{Try, Failure, Success}

private[services] object ServiceDirectory {
  private[internal] val systemName = "distributed-services"

  Logger.configure()

  def start(name: String, address: String, port: Int, role: String, configuration: Configuration): ServiceDirectory = {
    val configBuilder = new StringBuilder()
    var config = configuration.underlying.getConfig(systemName)
    val fallback = configuration.underlying.getConfig(systemName)
    configBuilder.append(s"akka.remote.netty.tcp.port=$port\n")
    configBuilder.append(s"akka.remote.netty.tcp.hostname=$address\n")
    configBuilder.append(s"""akka.cluster.roles=["${role}"]\n""")
    config = ConfigFactory.parseString(configBuilder.toString()).withFallback(fallback)
    Logger("InternalServices").debug(s"Akka remoting will be bound to akka.tcp://$systemName@$address:$port")
    val system = ActorSystem(systemName, config)
    val cluster = Cluster(system)
    new ServiceDirectory(name, configuration, system, cluster, address, port)
  }
}

private[services] class ServiceDirectory(val name: String, val configuration: Configuration, val system: ActorSystem, val cluster: Cluster, address: String, port: Int) extends ServicesApi with JoinableServices {

  implicit val ec = system.dispatcher
  val logger = Logger("InternalServices")
  val metrics = new MetricRegistry
  val globalState = new ConcurrentHashMap[Address, util.Set[Service]]()
  val jmxRegistry = JmxReporter.forRegistry(metrics).inDomain(ServiceDirectory.systemName).build()
  val stateManager = system.actorOf(Props(classOf[StateManagerActor], this), "StateManagerActor")
  val clusterListener = system.actorOf(Props(classOf[ClusterListener], this), "ClusterListener")

  jmxRegistry.start()

  askEveryoneButMe()
  tellEveryoneToAskMe()

  def stateAsString() = {
    var json = Json.obj()
    import collection.JavaConversions._
    for (e <- globalState.entrySet()) {
      var services = Json.arr()
      for (service <- e.getValue) {
        services = services.append(Json.obj(
          "uid" -> service.uid,
          "name" -> service.name,
          "url" -> service.url,
          "version" -> JsString(service.version.getOrElse("*")),
          "roles" -> JsArray(service.roles.map(JsString))
        ))
      }
      json = json ++ Json.obj(e.getKey.toString -> services)
    }
    val size = globalState.values().toList.flatMap(_.toList).size
    s"[$name] State is ($size services registered) : ${Json.prettyPrint(json)}"
  }

  def askEveryoneButMe(): Unit = {
    import scala.collection.JavaConversions._
    cluster.state.getMembers.toList.filter(_.address != cluster.selfAddress).foreach { member =>
      askState(member.address).andThen {
        case Success(state) => {
          // TODO : handle lifecycle events here
          globalState.put(member.address, new util.HashSet[Service]())
          globalState.get(member.address).addAll(state)
        }
        case Failure(e) => logger.error(s"[$name] Error while asking state", e)
      }
    }
  }

  def tellEveryoneToAskMe(): Unit = {
    import scala.collection.JavaConversions._
    cluster.state.getMembers.toList.filter(_.address != cluster.selfAddress).foreach { member =>
      system.actorSelection(RootActorPath(member.address) / "user" / "StateManagerActor").tell(AskMeMyState(cluster.selfAddress), stateManager)
    }
  }

  def askState(to: Address): Future[java.util.Set[Service]] = {
    def askIt = akka.pattern.ask(system.actorSelection(RootActorPath(to) / "user" / "StateManagerActor"), WhatIsYourState())(Timeout(10, TimeUnit.SECONDS)).mapTo[NodeState].map(_.state)
    Futures.retry(5)(askIt)(system.dispatcher).andThen {
      case state => //logger.debug(s"State of $to is $state")
    }
  }

  override def joinSelf(): ServicesApi = join(Seq(s"$address:$port"))

  override def join(seedNode: String): ServicesApi = join(Seq(seedNode))

  override def join(seedNodes: Seq[String]): ServicesApi = {
    val addresses = scala.collection.immutable.Seq().++(seedNodes.:+(s"$address:$port").map { message =>
      message.split("\\:").toList match {
        case addr :: prt :: Nil => akka.actor.Address("akka.tcp", ServiceDirectory.systemName, addr, prt.toInt)
      }
    }.toSeq)
    cluster.joinSeedNodes(addresses)
    if (!globalState.containsKey(cluster.selfAddress)) globalState.putIfAbsent(cluster.selfAddress, new util.HashSet[Service]())
    askEveryoneButMe()
    tellEveryoneToAskMe()
    def ping(): Unit = {
      if (!system.isTerminated) {
        Try(system.scheduler.scheduleOnce(Duration(5, TimeUnit.SECONDS)) {
          tellEveryoneToAskMe()
          if (!system.isTerminated) ping()
        })
      }
    }
    ping()
    this
  }

  override def stop(): Services = {
    jmxRegistry.stop()
    stateManager ! PoisonPill
    clusterListener ! PoisonPill
    cluster.leave(cluster.selfAddress)
    system.shutdown()
    Services(name, configuration)
  }

  override def registerService(service: Service): ServiceRegistration = {
    logger.trace(s"[$name] Register service : $service")
    if (!globalState.containsKey(cluster.selfAddress)) {
      globalState.putIfAbsent(cluster.selfAddress, new util.HashSet[Service]())
    }
    globalState.get(cluster.selfAddress).add(service)
    askEveryoneButMe()
    tellEveryoneToAskMe()
    logger.trace(s"[$name] ${stateAsString()}")
    system.eventStream.publish(ServiceRegistered(DateTime.now(), service))
    new ServiceRegistration(this, service)
  }

  private[this] def merge(a: ConcurrentHashMap[Address, util.Set[Service]], name: Option[String], roles: Seq[String], version: Option[String]): Set[Service] = {
    import collection.JavaConversions._
    a.values().toList.flatMap(_.toList).filter(s => name.getOrElse(s.name) == s.name).filter { service =>
      (roles, version) match {
        case (seq, Some(v)) if seq.nonEmpty && seq.forall(service.roles.contains(_)) && version == service.version => true
        case (seq, Some(v)) if seq.isEmpty && version == service.version => true
        case (seq, None) if seq.nonEmpty && seq.forall(service.roles.contains(_)) => true
        case (seq, None) if seq.isEmpty => true
        case _ => false
      }
    }.toSet[Service]
  }

  override def client(name: String, roles: Seq[String] = Seq(), version: Option[String] = None): Client = new LoadBalancedClient(name, this)

  override def services(name: String, roles: Seq[String] = Seq(), version: Option[String] = None): Set[Service] = merge(globalState, Some(name), roles, version)

  override def service(name: String, roles: Seq[String] = Seq(), version: Option[String] = None): Option[Service] = merge(globalState, Some(name), roles, version).headOption

  override def allServices(roles: Seq[String] = Seq(), version: Option[String] = None): Set[Service] = merge(globalState, None, roles, version)

  override def printState(): Unit = println(stateAsString())

  override def registerServiceListener(listener: ActorRef): Registration = {
    system.eventStream.unsubscribe(listener, classOf[LifecycleEvent])
    def unregister(): Unit = {
      system.eventStream.unsubscribe(listener, classOf[LifecycleEvent])
    }
    new ListenerRegistration(unregister)
  }
}
