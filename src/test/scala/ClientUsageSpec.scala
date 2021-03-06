import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, TimeUnit}

import com.distributedstuff.services.api.{Service, Services, ServicesApi}
import com.distributedstuff.services.common.http.Http
import com.distributedstuff.services.common.http.support._
import com.distributedstuff.services.common.{Network, IdGenerator, Reference}
import com.ning.http.client.{AsyncCompletionHandler, AsyncHttpClient, AsyncHttpClientConfig, Response}
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import org.specs2.mutable.{Specification, Tags}
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

class ClientUsageSpec extends Specification with Tags {
  sequential

  implicit val ec = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  private[this] val config: AsyncHttpClientConfig = new AsyncHttpClientConfig.Builder()
    .setAllowPoolingConnections(true)
    .setCompressionEnforced(true)
    .setRequestTimeout(60000)
    .build()

  private[this] val httpClient: AsyncHttpClient = new AsyncHttpClient(config)

  def ningClientConversion(service: Service): Future[JsObject] = {
    val promise = Promise[JsObject]()
    httpClient.prepareGet(service.url).execute(new AsyncCompletionHandler[Unit] {
      override def onCompleted(response: Response): Unit = {
        promise.trySuccess(Json.parse(response.getResponseBody).as[JsObject])
      }
    })
    promise.future
  }

  def okHttpConversion(service: Service): Future[JsObject] = Http.url(service.url).get().map(_.json.as[JsObject])

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
      val client = api.client("SERVICE1")
      for (i <- 1 to 1000) {
        val json = Await.result(client.callM(okHttpConversion), Duration(10, TimeUnit.SECONDS))
        json
      }
    }
  }

  "Service API" should {

    val port = Network.freePort
    val port1 = Network.freePort
    val port2 = Network.freePort
    val port3 = Network.freePort

    val serviceNode1 = Services("node1").startAndJoin("127.0.0.1", port)
    val serviceNode2 = Services("node2").start().join(s"127.0.0.1:$port")
    val serviceNode3 = Services("node3").start().join(s"127.0.0.1:$port")

    val service1 = Service(name = "SERVICE1", url = s"http://localhost:$port1/service1")
    val service2 = Service(name = "SERVICE1", url = s"http://localhost:$port2/service1")
    val service3 = Service(name = "SERVICE1", url = s"http://localhost:$port3/service1")

    val counter1 = new AtomicInteger(0)
    val counter2 = new AtomicInteger(0)
    val counter3 = new AtomicInteger(0)

    val server1 = Reference[HttpServer](createWebserver(port1, counter1))
    val server2 = Reference[HttpServer](createWebserver(port2, counter2))
    val server3 = Reference[HttpServer](createWebserver(port3, counter3))


    "Register some services" in {
      Thread.sleep(2000)
      serviceNode1.registerService(service1)
      serviceNode1.registerService(service2)
      serviceNode1.registerService(service3)
      Thread.sleep(10000)
      success
    }

    "Run clients with OkHttp" in {
      Await.result(Future.sequence(Seq(user(serviceNode1), user(serviceNode2), user(serviceNode3))), Duration(100, TimeUnit.SECONDS))
      success
    }

    "Check if OkHttp client does loadbalancing" in {
      println("=====================================================================")
      println(s" Counter 1 : ${counter1.get()}")
      println(s" Counter 2 : ${counter2.get()}")
      println(s" Counter 3 : ${counter3.get()}")
      println("=====================================================================")
      counter1.get() should be_>(0)
      counter2.get() should be_>(0)
      counter3.get() should be_>(0)

      counter1.get() should be_>(700)
      counter2.get() should be_>(700)
      counter3.get() should be_>(700)

      counter1.get() should be_<(1300)
      counter2.get() should be_<(1300)
      counter3.get() should be_<(1300)
      success
    }

    "Reset" in {
      counter1.set(0)
      counter2.set(0)
      counter3.set(0)
      success
    }

    "Run clients with Ning" in {
      Await.result(Future.sequence(Seq(user(serviceNode1), user(serviceNode2), user(serviceNode3))), Duration(100, TimeUnit.SECONDS))
      success
    }

    "Check if Ning client does loadbalancing" in {
      println("=====================================================================")
      println(s" Counter 1 : ${counter1.get()}")
      println(s" Counter 2 : ${counter2.get()}")
      println(s" Counter 3 : ${counter3.get()}")
      println("=====================================================================")
      counter1.get() should be_>(0)
      counter2.get() should be_>(0)
      counter3.get() should be_>(0)

      counter1.get() should be_>(700)
      counter2.get() should be_>(700)
      counter3.get() should be_>(700)

      counter1.get() should be_<(1300)
      counter2.get() should be_<(1300)
      counter3.get() should be_<(1300)
      success
    }

    "Shutdown everything" in {
      serviceNode1.stop()
      serviceNode2.stop()
      serviceNode3.stop()
      server1().stop(0)
      server2().stop(0)
      server3().stop(0)
      httpClient.close()
      success
    }
  }
}