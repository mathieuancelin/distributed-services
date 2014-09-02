import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, TimeUnit}

import com.distributedstuff.services.api.{Service, Services, ServicesApi}
import com.distributedstuff.services.common.{IdGenerator, Reference}
import com.distributedstuff.services.clients.httpsupport.HttpClientSupport
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import org.specs2.mutable.{Specification, Tags}
import play.api.libs.json.Json

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

import com.distributedstuff.services.common.http.support._

class HttpClientUsageSpec extends Specification with Tags {
  sequential

  implicit val ec = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  def createWebserver(port: Int, counter: AtomicInteger) = {
    val server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0)
    server.setExecutor(Executors.newFixedThreadPool(4))
    server.createContext("/service1", new HttpHandler {
      override def handle(p1: HttpExchange): Unit = {
        val data = Json.stringify(Json.obj("payload" -> IdGenerator.extendedToken(1024))).getBytes(Charset.forName("UTF-8"))
        p1.getResponseHeaders.add("Content-Type", "application/json")
        p1.getResponseHeaders.add("Content-Length", data.length + "")
        p1.getResponseHeaders.add("Access-Control-Allow-Origin", "*")
        p1.sendResponseHeaders(200, data.length)
        p1.getResponseBody.write(data)
        p1.close()
        counter.incrementAndGet()
      }
    })
    server.start()
    server
  }


  def user(api: ServicesApi) = {
    Future {
      val client = api.httpClient("SERVICE1")
      for (i <- 1 to 1000) {
        try {
          val json = Await.result(client.get().map(_.json), Duration(10, TimeUnit.SECONDS))
          json
        } catch {
          case e => println("fuuuuuuu")
        }
      }
    }
  }

  "Service API with Http support" should {

    val serviceNode1 = Services("node1").startAndJoin("127.0.0.1", 7777)
    val serviceNode2 = Services("node2").start().join("127.0.0.1:7777")
    val serviceNode3 = Services("node3").start().join("127.0.0.1:7777")

    val service1 = Service(name = "SERVICE1", url = "http://localhost:9000/service1")
    val service2 = Service(name = "SERVICE1", url = "http://localhost:9001/service1")
    val service3 = Service(name = "SERVICE1", url = "http://localhost:9002/service1")

    val counter1 = new AtomicInteger(0)
    val counter2 = new AtomicInteger(0)
    val counter3 = new AtomicInteger(0)

    val server1 = Reference[HttpServer](createWebserver(9000, counter1))
    val server2 = Reference[HttpServer](createWebserver(9001, counter2))
    val server3 = Reference[HttpServer](createWebserver(9002, counter3))


    "Register some services" in {
      Thread.sleep(10000)
      serviceNode1.registerService(service1)
      serviceNode2.registerService(service2)
      serviceNode3.registerService(service3)
      Thread.sleep(2000)
      success
    }

    "Run clients support" in {
      Await.result(Future.sequence(Seq(user(serviceNode1), user(serviceNode2), user(serviceNode3))), Duration(100, TimeUnit.SECONDS))
      success
    }

    "Check client is balanced" in {
      println("=====================================================================")
      println(s" Counter 1 : ${counter1.get()}")
      println(s" Counter 2 : ${counter2.get()}")
      println(s" Counter 3 : ${counter3.get()}")
      println("=====================================================================")
      counter1.get() should be_>(0)
      counter2.get() should be_>(0)
      counter3.get() should be_>(0)

      counter1.get() should be_>(900)
      counter2.get() should be_>(900)
      counter3.get() should be_>(900)

      counter1.get() should be_<(1100)
      counter2.get() should be_<(1100)
      counter3.get() should be_<(1100)
      success
    }

    "Shutdown everything" in {
      serviceNode1.stop()
      serviceNode2.stop()
      serviceNode3.stop()
      server1().stop(0)
      server2().stop(0)
      server3().stop(0)
      success
    }
  }
}