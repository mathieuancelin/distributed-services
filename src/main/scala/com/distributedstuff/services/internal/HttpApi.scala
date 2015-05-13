package com.distributedstuff.services.internal

import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.util.concurrent.{Executors, TimeUnit}

import akka.actor.{Cancellable, Actor, Props}
import akka.cluster.ClusterEvent.LeaderChanged
import akka.cluster.{Cluster, ClusterEvent}
import akka.pattern.ask
import akka.util.Timeout
import com.distributedstuff.services.api.Service
import com.distributedstuff.services.common.Logger
import com.distributedstuff.services.internal.HttpApi.UnregisterNonResponsiveServices
import com.distributedstuff.services.internal.ReplicatedCache._
import com.google.common.io.CharStreams
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import play.api.libs.json._

import scala.concurrent.duration.Duration

sealed trait HttpCommand
case class SearchRequest(name: Option[String], role: Option[String], version: Option[String]) extends HttpCommand
case class SearchRequestOne(name: Option[String], role: Option[String], version: Option[String]) extends HttpCommand
case class RegisterRequest(service: Service) extends HttpCommand
case class UnregisterRequest(uuid: String) extends HttpCommand
case class Heartbeat(uuid: String) extends HttpCommand
case class Response(code: Int, body: JsValue)

object HttpApi {
  def props(host: String, port: Int, sd: ServiceDirectory) = Props(classOf[HttpApi], host, port, sd)
  final case class UnregisterNonResponsiveServices()
}

class HttpApi(host: String, port: Int, sd: ServiceDirectory) extends Actor {

  implicit val ec = context.system.dispatcher
  implicit val askTimeout = Timeout(10, TimeUnit.SECONDS)
  implicit val cluster = Cluster(context.system)
  val UTF8 = Charset.forName("UTF-8")
  val logger = Logger("HttpApi")
  val httpExecutor = Executors.newFixedThreadPool(4)

  val reaperDuration = Duration(sd.configuration.getInt("services.heartbeat.reaper.every").getOrElse(120000), TimeUnit.MILLISECONDS)
  val heartbeatDuration = Duration(sd.configuration.getInt("services.heartbeat.every").getOrElse(1800000), TimeUnit.MILLISECONDS)

  var server: HttpServer = _
  var leader = false
  var cancel: Cancellable = _

  override def preStart(): Unit = {
    logger.info("Actually starting Http Api")
    cluster.subscribe(self, ClusterEvent.InitialStateAsEvents, classOf[ClusterEvent.LeaderChanged])
    server = HttpServer.create(new InetSocketAddress(host, port), 0)
    server.setExecutor(httpExecutor)
    server.createContext("/", new HttpHandler {

      def writeResponse(p1: HttpExchange, response: Response) = {
        val data = Json.stringify(response.body).getBytes(UTF8)
        p1.getResponseHeaders.add("Content-Type", "application/json")
        p1.getResponseHeaders.add("Content-Length", data.length + "")
        p1.getResponseHeaders.add("Access-Control-Allow-Origin", "*")
        p1.sendResponseHeaders(response.code, data.length)
        p1.getResponseBody.write(data)
        p1.close()
      }

      override def handle(p1: HttpExchange): Unit = {
        (p1.getRequestMethod, p1.getRequestURI.getPath) match {
          case ("GET", "/services") => {
            val params = p1.getRequestURI.getQuery.split("&").map(v => (v.split("=")(0), v.split("=")(1))).toMap
            val role = params.get("role")
            val version = params.get("version")
            val name = params.get("name")
            val req = SearchRequest(name, role, version)
            self.ask(req).mapTo[Response].map(res => writeResponse(p1, res))
          }
          case ("GET", "/services/first") => {
            val params = p1.getRequestURI.getQuery.split("&").map(v => (v.split("=")(0), v.split("=")(1))).toMap
            val role = params.get("role")
            val version = params.get("version")
            val name = params.get("name")
            val req = SearchRequestOne(name, role, version)
            self.ask(req).mapTo[Response].map(res => writeResponse(p1, res))
          }
          case ("POST", "/services") => {
            val inr = new InputStreamReader(p1.getRequestBody)
            Json.parse(CharStreams.toString(inr)).validate(Service.format) match {
              case JsSuccess(service, _) => self.ask(RegisterRequest(service)).mapTo[Response].map(res => writeResponse(p1, res))
              case e: JsError => writeResponse(p1, Response(412, Json.obj("message" -> "Bad service structure", "errors" -> JsError.toFlatJson(e))))
            }
          }
          case ("PUT" , "/services") => {
            val params = p1.getRequestURI.getQuery.split("&").map(v => (v.split("=")(0), v.split("=")(1))).toMap
            val regId = params.get("regId")
            if (regId.isDefined) {
              self.ask(Heartbeat(regId.get)).mapTo[Response].map(res => writeResponse(p1, res))
            } else {
              writeResponse(p1, Response(412, Json.obj("message" -> "No regId defined")))
            }
          }
          case ("DELETE", "/services") => {
            val params = p1.getRequestURI.getQuery.split("&").map(v => (v.split("=")(0), v.split("=")(1))).toMap
            val regId = params.get("regId")
            if (regId.isDefined) {
              self.ask(UnregisterRequest(regId.get)).mapTo[Response].map(res => writeResponse(p1, res))
            } else {
              writeResponse(p1, Response(412, Json.obj("message" -> "No regId defined")))
            }
          }
          case _ => writeResponse(p1, Response(400, Json.obj("message" -> "Bad request")))
        }
      }
    })
    logger.info(s"Starting Http server at http://$host:$port/services ...")
    server.start()
    cancel = context.system.scheduler.schedule(Duration(0, TimeUnit.MILLISECONDS), reaperDuration, self, UnregisterNonResponsiveServices())
  }

  override def postStop(): Unit = {
    logger.info("Stopping Http Api now !!!")
    cancel.cancel()
    server.stop(0)
    httpExecutor.shutdownNow()
  }

  override def receive: Receive = {
    case LeaderChanged(node) => {
      val wasLeader = leader
      leader = node.contains(cluster.selfAddress)
    }
    case SearchRequest(name, role, version) => {
      val s = sender()
      if (name.isDefined) {
        sd.asyncServices(name.get, role.map(Seq(_)).getOrElse(Seq()), version).map { services =>
          val json = Json.toJson(services)(Writes.set(Service.format))
          s ! Response(200, json)
        }
      } else {
        sd.asyncAllServices(role.map(Seq(_)).getOrElse(Seq()), version).map { services =>
          val json = Json.toJson(services)(Writes.set(Service.format))
          s ! Response(200, json)
        }
      }
    }
    case SearchRequestOne(name, role, version) => {
      val s = sender()
      sd.asyncService(name.get, role.map(Seq(_)).getOrElse(Seq()), version).map { opt =>
        val json = opt.map(service => Json.toJson(service)(Service.format)).getOrElse(Json.obj())
        s ! Response(200, json)
      }
    }
    case RegisterRequest(service) => {
      val uuid = service.uid
      sd.registerService(service)
      sd.replicatedCache ! RegisterServiceDescriptorExpiration(uuid, HttpRegistration(uuid, service.name, System.currentTimeMillis() + heartbeatDuration.toMillis))
      sender() ! Response(200, Json.obj("regId" -> uuid))
    }
    case Heartbeat(uuid) => {
      val time = System.currentTimeMillis() + heartbeatDuration.toMillis
      sd.replicatedCache ! UpdateServiceDescriptorExpiration(uuid, HttpRegistration(uuid, "fuuuuu", time))
      sender() ! Response(200, Json.obj("regId" -> uuid, "until" -> time))
    }
    case UnregisterRequest(uuid) => {
      val s = sender()
      sd.replicatedCache ! RemoveServiceRegistration(uuid)
      sd.replicatedCache.ask(GetServiceRegistrations()).mapTo[ServiceRegistrations].map { regs =>
        regs.registrations.get(ServiceRegistration.serviceRegistrationKey(uuid)).map { reg =>
          sd.asyncAllServices().map { services =>
            services.find(_.uid == uuid).foreach { service =>
              logger.info(s"Unregister $service")
              sd.replicatedCache ! RemoveServiceDescriptor(service)
            }
          }
        }
      }.map { _ =>
        s ! Response(200, Json.obj("done" -> true))
      }
    }
    case _: UnregisterNonResponsiveServices => {
      if (leader) {
        sd.replicatedCache.ask(GetServiceRegistrations()).mapTo[ServiceRegistrations].map { regs =>
          val time = System.currentTimeMillis()
          regs.registrations.foreach {
            case (key, reg) if reg.expiration < time => {
              sd.replicatedCache ! RemoveServiceRegistration(reg.uuid)
              sd.asyncAllServices().map { services =>
                services.find(_.uid == reg.uuid).foreach { service =>
                  logger.info(s"Reaping off $service")
                  sd.replicatedCache ! RemoveServiceDescriptor(service)
                }
              }
            }
            case _ =>
          }
        }
      }
    }
    case _ =>
  }
}
