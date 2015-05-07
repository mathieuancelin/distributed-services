package com.distributedstuff.services.internal

import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.util.concurrent.{Executors, TimeUnit}

import akka.actor.{Actor, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.distributedstuff.services.api.{Registration, Service}
import com.distributedstuff.services.common.{IdGenerator, Logger}
import com.google.common.io.CharStreams
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import play.api.libs.json.{JsSuccess, JsValue, Json, Writes}

sealed trait HttpCommand
case class SearchRequest(name: Option[String], role: Option[String], version: Option[String]) extends HttpCommand
case class SearchRequestOne(name: Option[String], role: Option[String], version: Option[String]) extends HttpCommand
case class RegisterRequest(service: Service) extends HttpCommand
case class UnregisterRequest(uuid: String) extends HttpCommand
case class Response(code: Int, body: JsValue)

object HttpApi {
  def props(host: String, port: Int, sd: ServiceDirectory) = Props(classOf[HttpApi], host, port, sd)
}

class HttpApi(host: String, port: Int, sd: ServiceDirectory) extends Actor {

  implicit val ec = context.system.dispatcher
  implicit val askTimeout = Timeout(10, TimeUnit.SECONDS)
  val server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0)
  val UTF8 = Charset.forName("UTF-8")
  val logger = Logger("HttpApi")
  var registrations = Map.empty[String, Registration]

  override def preStart(): Unit = {
    server.setExecutor(Executors.newFixedThreadPool(4))
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
            Json.parse(CharStreams.toString(inr)).transform(Service.format) match {
              case JsSuccess(service, _) => self.ask(RegisterRequest(service)).mapTo[Response].map(res => writeResponse(p1, res))
              case _ => writeResponse(p1, Response(412, Json.obj()))
            }
          }
          case ("DELETE", "/services") => {
            val params = p1.getRequestURI.getQuery.split("&").map(v => (v.split("=")(0), v.split("=")(1))).toMap
            val regId = params.get("regId")
            if (regId.isDefined) {
              self.ask(UnregisterRequest(regId.get)).mapTo[Response].map(res => writeResponse(p1, res))
            } else {
              writeResponse(p1, Response(412, Json.obj()))
            }
          }
          case _ => writeResponse(p1, Response(404, Json.obj()))
        }
      }
    })
    logger.info(s"Starting Http server at http://$host:$port/services ...")
    server.start()
  }

  override def postStop(): Unit = {
    server.stop(0)
  }

  override def receive: Receive = {
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
      val uuid = IdGenerator.uuid
      val reg = sd.registerService(service)
      registrations = registrations + ((uuid, reg))
      sender() ! Response(200, Json.obj("regId" -> uuid))
    }
    case UnregisterRequest(uuid) => {
      registrations.get(uuid).foreach(_.unregister())
      registrations = registrations - uuid
      sender() ! Response(200, Json.obj("done" -> true))
    }
    case _ =>
  }
}
