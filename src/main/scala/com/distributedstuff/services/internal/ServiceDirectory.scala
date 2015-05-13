package com.distributedstuff.services.internal

import java.util.concurrent.TimeUnit

import akka.actor._
import akka.cluster.Cluster
import akka.contrib.datareplication.{DataReplication, LWWMap, ORSet}
import akka.pattern.ask
import akka.util.Timeout
import com.codahale.metrics._
import com.distributedstuff.services.api._
import com.distributedstuff.services.common.{Configuration, IdGenerator, Logger}
import com.distributedstuff.services.internal.ReplicatedCache._
import com.typesafe.config.{ConfigFactory, ConfigObject}
import org.joda.time.DateTime
import play.api.libs.json._

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

  if (configuration.getString("services.http.host").isDefined) {
    val host = configuration.getString("services.http.host").getOrElse("127.0.0.1")
    val port = configuration.getInt("services.http.port").getOrElse(9999)
    system.actorOf(HttpApi.props(host, port, this), "HttpApi")
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
    })
    cluster.joinSeedNodes(addresses)
    this
  }

  override def stop(): Services = {
    jmxRegistry.stop()
    replicatedCache ! PoisonPill
    system.actorSelection("/user/HttpApi") ! PoisonPill
    cluster.leave(cluster.selfAddress)
    system.shutdown()
    Services(name, configuration)
  }

  override def asyncRegisterService(service: Service): Future[ServiceRegistration] = {
    logger.trace(s"[$name] Register service : $service")
    replicatedCache ! StoreServiceDescriptor(service)
    system.eventStream.publish(ServiceRegistered(DateTime.now(), service))
    Future.successful(new ServiceRegistration(this, service))
  }

  override def client(name: String, roles: Seq[String] = Seq(), version: Option[String] = None, retry: Int = 5): Client = new LoadBalancedClient(name, roles, version, retry, this)

  override def asyncServices(name: String, roles: Seq[String] = Seq(), version: Option[String] = None): Future[Set[Service]] = {
    replicatedCache.ask(GetServiceDescriptors(name)).mapTo[ServiceDescriptors].map {
      case ServiceDescriptors(key, Some(list)) => {
        list.filter { service =>
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
      Future.sequence(keys.keys.map(key => replicatedCache.ask(GetServiceDescriptors(key)).mapTo[ServiceDescriptors])).map { s =>
        s.filter {
          case ServiceDescriptors(key, Some(list)) => true
          case ServiceDescriptors(key, None) => false
        }.map(_.value.get).flatten.filter { service =>
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

  private[this] val metricWriter = new Writes[Metric] {
    override def writes(o: Metric): JsValue = o match {
      case t: Timer => Json.obj(
        "count" -> t.getCount,
        "getFifteenMinuteRate" -> t.getFifteenMinuteRate,
        "getFiveMinuteRate" -> t.getFiveMinuteRate,
        "getMeanRate" -> t.getMeanRate,
        "getOneMinuteRate" -> t.getOneMinuteRate,
        "get75thPercentile" -> t.getSnapshot.get75thPercentile(),
        "get95thPercentile" -> t.getSnapshot.get95thPercentile(),
        "get98thPercentile" -> t.getSnapshot.get98thPercentile(),
        "get99thPercentile" -> t.getSnapshot.get99thPercentile(),
        "get999thPercentile" -> t.getSnapshot.get999thPercentile(),
        "getMax" -> t.getSnapshot.getMax,
        "getMean" -> t.getSnapshot.getMean,
        "getMedian" -> t.getSnapshot.getMedian,
        "getMin" -> t.getSnapshot.getMin,
        "getStdDev" -> t.getSnapshot.getStdDev
      )
      case m: Meter => Json.obj(
        "count" -> m.getCount,
        "getFifteenMinuteRate" -> m.getFifteenMinuteRate,
        "getFiveMinuteRate" -> m.getFiveMinuteRate,
        "getMeanRate" -> m.getMeanRate,
        "getOneMinuteRate" -> m.getOneMinuteRate
      )
      case _ => Json.obj()
    }
  }

  private[this] def toMetricsJson(): JsArray = {
    import collection.JavaConversions._
    Json.arr(metrics.getMetrics.map {
      case (key, value) => metricWriter.writes(value).as[JsObject] ++ Json.obj("name" -> key)
    })
  }

  override def getMetrics: JsArray = toMetricsJson()

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

  private final case class FetchKeys()  // to trigger fetching of all the known services keys
  final case class GetKeys()                            // public API to get all known keys
  final case class Keys(keys: Set[String])

  private final case class ServiceDescriptorRequest(key: String, replyTo: ActorRef)
  final case class StoreServiceDescriptor(value: Service)
  final case class GetServiceDescriptors(name: String)
  final case class ServiceDescriptors(key: String, value: Option[Set[Service]])
  final case class RemoveServiceDescriptor(service: Service)

  private final case class ServicesRegistrationRequest(replyTo: ActorRef)
  private final case class FetchRegistrations()
  final case class GetServiceRegistrations()
  final case class UpdateServiceDescriptorExpiration(regId: String, reg: HttpRegistration)
  final case class RegisterServiceDescriptorExpiration(regId: String, reg: HttpRegistration)
  final case class RemoveServiceRegistration(regId: String)
  final case class ServiceRegistrations(registrations: Map[String, HttpRegistration])
  final case class HttpRegistration(uuid: String, name: String, expiration: Long)
  // fetch regs
}

class ReplicatedCache() extends Actor {

  import akka.contrib.datareplication.Replicator._

  implicit val askTimeout = Timeout(10, TimeUnit.SECONDS)
  implicit val ec = context.system.dispatcher
  implicit val cluster = Cluster(context.system)

  val replicator = DataReplication(context.system).replicator
  val KeysDataKey = "service-keys"
  val ExpirationDataKey = "service-expirations"
  var keys = Set.empty[String]
  var registrations = Map.empty[String, HttpRegistration]

  def serviceKey(service: Service): String = "service-descriptors-" + service.name
  def serviceKey(serviceName: String): String = "service-descriptors-" + serviceName

  override def preStart(): Unit = {
    replicator ! Subscribe(KeysDataKey, self)
    replicator ! Subscribe(ExpirationDataKey, self)
    self ! FetchKeys()
    self ! FetchRegistrations()
  }

  def receive = {

    // Services names handling
    case _: FetchKeys =>
      replicator ! Get(KeysDataKey, ReadLocal, Some(ServiceDescriptorRequest(KeysDataKey, self)))
    case GetSuccess(KeysDataKey, data: ORSet[String]@unchecked, Some(ServiceDescriptorRequest(key, replyTo))) =>
      keys = data.elements
    case _: GetKeys =>
      sender() ! Keys(keys)
    case Changed(KeysDataKey, data: ORSet[String] @unchecked) =>
      keys = data.elements

    // Services expiration handling
    case RegisterServiceDescriptorExpiration(regId, reg) =>
      replicator ! Update(ExpirationDataKey, LWWMap(), WriteLocal)(_ + (ServiceRegistration.serviceRegistrationKey(regId), reg))
    case UpdateServiceDescriptorExpiration(regId, reg) =>
      replicator ! Update(ExpirationDataKey, LWWMap(), WriteLocal) { map =>
        val key = ServiceRegistration.serviceRegistrationKey(regId)
        if (map.get(key).isDefined) {
          map + (key, reg)
        } else {
          map
        }
      }
    case RemoveServiceRegistration(regId) =>
      replicator ! Update(ExpirationDataKey, LWWMap(), WriteLocal)(_ - ServiceRegistration.serviceRegistrationKey(regId))
    case GetSuccess(ExpirationDataKey, data: LWWMap[HttpRegistration]@unchecked, Some(ServicesRegistrationRequest(replyTo))) =>
      registrations = data.entries
      replyTo ! ServiceRegistrations(registrations)
    case Changed(ExpirationDataKey, data: LWWMap[HttpRegistration] @unchecked) =>
      registrations = data.entries
    case _: FetchRegistrations =>
      replicator ! Get(ExpirationDataKey, ReadLocal, Some(ServicesRegistrationRequest(self)))
    case _: GetServiceRegistrations =>
      sender() ! ServiceRegistrations(registrations)

    // Services descriptors handling
    case StoreServiceDescriptor(service) =>
      val key = service.name
      if (!keys(key)) keys = keys + key
      replicator ! Update(KeysDataKey, ORSet(), WriteLocal)(_ + key)
      replicator ! Update(serviceKey(service), ORSet(), WriteLocal)(_ + service)
    case RemoveServiceDescriptor(service) =>
      replicator ! Update(serviceKey(service), ORSet(), WriteLocal)(_ - service)
      replicator ! Update(KeysDataKey, ORSet(), WriteLocal)(_ - service.name)
      if (keys(service.name)) keys = keys - service.name
    case GetServiceDescriptors(key) =>
      replicator ! Get(serviceKey(key), ReadLocal, Some(ServiceDescriptorRequest(key, sender())))
    case GetSuccess(_, data: ORSet[Service]@unchecked, Some(ServiceDescriptorRequest(key, replyTo))) =>
      replyTo ! ServiceDescriptors(key, Some(data.elements))
    case NotFound(_, Some(ServiceDescriptorRequest(key, replyTo))) =>
      replyTo ! ServiceDescriptors(key, None)
    case ur: UpdateResponse => // ok
  }
}