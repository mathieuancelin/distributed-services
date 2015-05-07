package com.distributedstuff.services.internal

import java.util.concurrent.TimeUnit

import akka.actor._
import akka.cluster.Cluster
import akka.contrib.datareplication.{DataReplication, ORSet}
import akka.pattern.ask
import akka.util.Timeout
import com.codahale.metrics.{JmxReporter, MetricRegistry}
import com.distributedstuff.services.api._
import com.distributedstuff.services.common.{Configuration, IdGenerator, Logger}
import com.distributedstuff.services.internal.ReplicatedCache._
import com.typesafe.config.{ConfigFactory, ConfigObject}
import org.joda.time.DateTime
import play.api.libs.json.{JsArray, JsString, Json}

import scala.concurrent.{Await, Future}

private[services] object ServiceDirectory {
  private[internal] val systemName = "distributed-services"

  Logger.configure()

  def start(name: String, address: String, port: Int, role: String, configuration: Configuration, metrics: Option[MetricRegistry]): ServiceDirectory = {
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
    new ServiceDirectory(name, configuration, system, metrics, cluster, address, port)
  }
}

private[services] class ServiceDirectory(val name: String, val configuration: Configuration, val system: ActorSystem, val m: Option[MetricRegistry], val cluster: Cluster, address: String, port: Int) extends ServicesApi with JoinableServices {

  implicit val ec = system.dispatcher
  implicit val askTimeout = Timeout(10, TimeUnit.SECONDS)
  val logger = Logger("InternalServices")
  val replicatedCache = system.actorOf(ReplicatedCache.props)
  var metrics = m.getOrElse(new MetricRegistry)
  var jmxRegistry = JmxReporter.forRegistry(metrics).inDomain(ServiceDirectory.systemName).build()

  if (configuration.getObject("services.http").isDefined) {
    val host = configuration.getString("services.http.host").getOrElse("127.0.0.1")
    val port = configuration.getInt("services.http.port").getOrElse(9999)
    // TODO : run http server
  }

  jmxRegistry.start()

  override def useMetrics(m: MetricRegistry): ServicesApi = {
    jmxRegistry.stop()
    metrics = m
    jmxRegistry = JmxReporter.forRegistry(metrics).inDomain(ServiceDirectory.systemName).build()
    jmxRegistry.start()
    this
  }

  override def actors(): ActorSystem = system

  def stateAsString(): Future[String] = {
    asyncAllServices().map { services =>
      var arr = Json.arr()
      for (service <- services) {
        arr = arr.append(Json.obj(
          "uid" -> service.uid,
          "name" -> service.name,
          "url" -> service.url,
          "version" -> JsString(service.version.getOrElse("*")),
          "roles" -> JsArray(service.roles.map(JsString))
        ))
      }
      val size = arr.value.size
      s"[$name] State is ($size services registered) : ${Json.prettyPrint(arr)}"
    }
  }


  override def joinSelf(): ServicesApi = join(Seq(s"$address:$port"))

  override def join(seedNode: String): ServicesApi = join(Seq(seedNode))

  override def join(seedNodes: Seq[String]): ServicesApi = {
    val addresses = scala.collection.immutable.Seq().++(seedNodes.:+(s"$address:$port").map { message =>
      message.split("\\:").toList match {
        case addr :: prt :: Nil => akka.actor.Address("akka.tcp", ServiceDirectory.systemName, addr, prt.toInt)
        case _ => throw new RuntimeException(s"Bad akka address : $message")
      }
    }.toSeq)
    cluster.joinSeedNodes(addresses)
    this
  }

  override def stop(): Services = {
    jmxRegistry.stop()
    replicatedCache ! PoisonPill
    cluster.leave(cluster.selfAddress)
    system.shutdown()
    Services(name, configuration)
  }

  override def asyncRegisterService(service: Service): Future[ServiceRegistration] = {
    logger.trace(s"[$name] Register service : $service")
    replicatedCache ! PutInCache(service.name, service)
    system.eventStream.publish(ServiceRegistered(DateTime.now(), service))
    Future.successful(new ServiceRegistration(this, service))
  }

  override def client(name: String, roles: Seq[String] = Seq(), version: Option[String] = None, retry: Int = 5): Client = new LoadBalancedClient(name, retry, this)

  override def asyncServices(name: String, roles: Seq[String] = Seq(), version: Option[String] = None): Future[Set[Service]] = {
    replicatedCache.ask(GetFromCache(name)).mapTo[Cached].map {
      case Cached(key, Some(list)) => {
        list.asInstanceOf[Set[Service]].filter { service =>
          (roles, version) match {
            case (seq, Some(v)) if seq.nonEmpty && seq.forall(service.roles.contains(_)) && version == service.version => true
            case (seq, Some(v)) if seq.isEmpty && version == service.version => true
            case (seq, None) if seq.nonEmpty && seq.forall(service.roles.contains(_)) => true
            case (seq, None) if seq.isEmpty => true
            case _ => false
          }
        }
      }
      case _ => Set[Service]()
    }
  }

  override def asyncService(name: String, roles: Seq[String] = Seq(), version: Option[String] = None): Future[Option[Service]] = asyncServices(name, roles, version).map(_.headOption)

  override def asyncAllServices(roles: Seq[String] = Seq(), version: Option[String] = None): Future[Set[Service]] = {
    replicatedCache.ask(GetKeys()).mapTo[Keys].flatMap { keys =>
      Future.sequence(keys.keys.map(key => replicatedCache.ask(GetFromCache(key)).mapTo[Cached])).map { s =>
        s.filter {
          case Cached(key, Some(list)) => true
          case Cached(key, None) => false
        }.map(_.value.get.asInstanceOf[Set[Service]]).flatten.filter { service =>
          (roles, version) match {
            case (seq, Some(v)) if seq.nonEmpty && seq.forall(service.roles.contains(_)) && version == service.version => true
            case (seq, Some(v)) if seq.isEmpty && version == service.version => true
            case (seq, None) if seq.nonEmpty && seq.forall(service.roles.contains(_)) => true
            case (seq, None) if seq.isEmpty => true
            case _ => false
          }
        }
      }
    }
  }

  override def printState(): Unit = stateAsString().map(println)

  override def registerServiceListener(listener: ActorRef): Registration = {
    system.eventStream.unsubscribe(listener, classOf[LifecycleEvent])
    def unregister(): Unit = {
      system.eventStream.unsubscribe(listener, classOf[LifecycleEvent])
    }
    new ListenerRegistration(unregister)
  }

  override def exposeFromConfig() = {
    import scala.collection.JavaConversions._
    val servicesToExpose = configuration.getObjectList("services.autoexpose").getOrElse(new java.util.ArrayList[ConfigObject]()).toList.map { obj =>
      val config = new Configuration(obj.toConfig)
      val name = config.getString("name").get // mandatory
      val url = config.getString("url").get   // mandatory
      val uid = config.getString("uid").getOrElse(IdGenerator.uuid)
      val version = config.getString("version")
      val roles = Option(obj.get("roles")).map(_.unwrapped().asInstanceOf[java.util.List[String]].toSeq).getOrElse(Seq[String]())
      Service(name = name, version = version, url = url, uid = uid, roles = roles)
    }
    servicesToExpose.map(service => Await.result(asyncRegisterService(service), askTimeout.duration))
  }
}

object ReplicatedCache {

  def props: Props = Props[ReplicatedCache]

  private final case class Request(key: String, replyTo: ActorRef)

  final case class FetchKeys()
  final case class GetKeys()
  final case class Keys(keys: Set[String])
  final case class PutInCache(key: String, value: Any)
  final case class GetFromCache(key: String)
  final case class Cached(key: String, value: Option[Any])
  final case class Evict(service: Service)
}

class ReplicatedCache() extends Actor {

  import akka.contrib.datareplication.Replicator._

  implicit val askTimeout = Timeout(10, TimeUnit.SECONDS)
  implicit val ec = context.system.dispatcher
  implicit val cluster = Cluster(context.system)

  val replicator = DataReplication(context.system).replicator
  val KeysDataKey = "service-keys"

  var keys = Set.empty[String]

  def dataKey(entryKey: String): String = "service-" + entryKey

  override def preStart(): Unit = {
    replicator ! Subscribe(KeysDataKey, self)
    self ! FetchKeys()
  }

  def receive = {
    case _: FetchKeys =>
      replicator ! Get(KeysDataKey, ReadLocal, Some(Request(KeysDataKey, self)))
    case GetSuccess(KeysDataKey, data: ORSet[String]@unchecked, Some(Request(key, replyTo))) =>
      keys = data.elements
    case _: GetKeys => sender() ! Keys(keys)
    case Changed(KeysDataKey, data: ORSet[String] @unchecked) =>
      keys = data.elements
    case PutInCache(key, value) =>
      if (!keys(key)) keys = keys + key
      replicator ! Update(KeysDataKey, ORSet(), WriteLocal)(_ + key)
      replicator ! Update(dataKey(key), ORSet(), WriteLocal)(_ + value)
    case Evict(service) =>
      replicator ! Update(dataKey(service.name), ORSet(), WriteLocal)(_ - service)
      replicator ! Update(KeysDataKey, ORSet(), WriteLocal)(_ - service.name)
      keys = keys.filterNot(_ == service.name)
    case GetFromCache(key) =>
      replicator ! Get(dataKey(key), ReadLocal, Some(Request(key, sender())))
    case GetSuccess(_, data: ORSet[Service]@unchecked, Some(Request(key, replyTo))) =>
      replyTo ! Cached(key, Some(data.elements))
    case NotFound(_, Some(Request(key, replyTo))) =>
      replyTo ! Cached(key, None)
    case ur: UpdateResponse => // ok
  }
}