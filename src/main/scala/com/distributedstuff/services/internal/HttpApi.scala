package com.distributedstuff.services.internal

import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.util.concurrent.Executors

import akka.actor.Actor
import akka.pattern.ask
import com.distributedstuff.services.api.Service
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import play.api.libs.json.{JsValue, Json}

sealed trait HttpCommand
case class SearchRequest(name: Option[String], roles: Seq[String], version: Option[String]) extends HttpCommand
case class SearchRequestOne(name: Option[String], roles: Seq[String], version: Option[String]) extends HttpCommand
case class RegisterRequest(service: Service) extends HttpCommand
case class UnregisterRequest(uuid: String) extends HttpCommand
case class Response(code: Int, body: JsValue)

class HttpApi(host: String, port: Int, sd: ServiceDirectory) extends Actor {

    val server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0)

    override def preStart(): Unit = {
        server.setExecutor(Executors.newFixedThreadPool(4))
        server.createContext("/", new HttpHandler {

            def writeResponse(p1: HttpExchange, response: Response) = {
                val data = Json.stringify(response.body).getBytes(Charset.forName("UTF-8"))
                p1.getResponseHeaders.add("Content-Type", "application/json")
                p1.getResponseHeaders.add("Content-Length", data.length + "")
                p1.getResponseHeaders.add("Access-Control-Allow-Origin", "*")
                p1.sendResponseHeaders(response.code, data.length)
                p1.getResponseBody.write(data)
                p1.close()
            }

            override def handle(p1: HttpExchange): Unit = {
                (p1.getRequestMethod, p1.getRequestURI.getPath) match {
                    //case ("GET", "/services") => {
                    //    p1.se
                    //    writeResponse(self.ask(SearchRequest()).mapTo[Response].map(res => writeResponse(p1, res)))
                    //}
                    //case ("GET", "/services/first") => {
                    //    writeResponse(self.ask(SearchRequestOne()).mapTo[Response].map(res => writeResponse(p1, res)))
                    //}
                    //case ("POST", "/services") => {
                    //    val body = Json.parse(p1.getRequestBody)
                    //    writeResponse(self.ask(RegisterRequest()).mapTo[Response].map(res => writeResponse(p1, res)))
                    //}
                    //case ("DELETE", "/services") => {
                    //    writeResponse(self.ask(UnregisterRequest()).mapTo[Response].map(res => writeResponse(p1, res)))
                    //}
                    case _ => writeResponse(p1, Response(404, Json.obj()))
                }
            }
        })
        server.start()
    }

    override def postStop(): Unit = {
        server.stop(0)
    }

    override def receive: Receive = {
        case SearchRequest(name, roles, version) =>
        case SearchRequestOne(name, roles, version) =>
        case RegisterRequest(service) =>

        case UnregisterRequest(uuid) =>

        case _ =>
    }
}
