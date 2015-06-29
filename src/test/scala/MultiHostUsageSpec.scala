import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, TimeUnit}

import com.distributedstuff.services.api.{Service, Services, ServicesApi}
import com.distributedstuff.services.clients.httpsupport.HttpClientSupport
import com.distributedstuff.services.common.http.support._
import com.distributedstuff.services.common.{IdGenerator, Network, Reference}
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import org.specs2.mutable.{Specification, Tags}
import play.api.libs.json.Json

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

class MultiHostUsageSpec extends Specification with Tags {
  sequential

  class Host(name: String, portToJoin: Int) {

    val httpThreads = Executors.newFixedThreadPool(4)
    val httpPort = Network.freePort
    val server = Reference.empty[HttpServer]()
    val serviceApi = Reference.empty[ServicesApi]()

    val counter1 = new AtomicInteger(0)
    val counter2 = new AtomicInteger(0)
    val counter3 = new AtomicInteger(0)
    val counter4 = new AtomicInteger(0)
    val counter5 = new AtomicInteger(0)

    def start(): Host = {
      server <== createWebserver()
      exposeService()
      this
    }

    def stop(): Host = {
      server().stop(0)
      serviceApi().stop()
      this
    }

    def exposeService() = {
      serviceApi <== Services("node2").start().join(s"127.0.0.1:$portToJoin")
      serviceApi().registerService(Service(name = "SERVICE1", url = s"http://localhost:$httpPort/service1"))
      serviceApi().registerService(Service(name = "SERVICE2", url = s"http://localhost:$httpPort/service2"))
      serviceApi().registerService(Service(name = "SERVICE3", url = s"http://localhost:$httpPort/service3"))
      serviceApi().registerService(Service(name = "SERVICE4", url = s"http://localhost:$httpPort/service4"))
      serviceApi().registerService(Service(name = "SERVICE5", url = s"http://localhost:$httpPort/service5"))
    }

    def fail() = {
      stop()
      Thread.sleep(10000)
      start()
      Thread.sleep(10000)
    }

    def displayStats() = {
      println("====================================")
      println(s" Stats of $name\n\n")
      println(s"counter 1 : ${counter1.get()}")
      println(s"counter 2 : ${counter2.get()}")
      println(s"counter 3 : ${counter3.get()}")
      println(s"counter 4 : ${counter4.get()}")
      println(s"counter 5 : ${counter5.get()}")
    }

    def total() = {
      counter1.get() + counter2.get() +counter3.get() +counter4.get() +counter5.get()
    }

    def createWebserver() = {
      val server = HttpServer.create(new InetSocketAddress("0.0.0.0", httpPort), 0)
      server.setExecutor(httpThreads)
      server.createContext("/service", new HttpHandler {
        override def handle(p1: HttpExchange): Unit = {
          val data = Json.stringify(Json.obj("payload" -> IdGenerator.extendedToken(1024))).getBytes(Charset.forName("UTF-8"))
          p1.getResponseHeaders.add("Content-Type", "application/json")
          p1.getResponseHeaders.add("Content-Length", data.length + "")
          p1.getResponseHeaders.add("Access-Control-Allow-Origin", "*")
          p1.sendResponseHeaders(200, data.length)
          p1.getResponseBody.write(data)
          p1.close()

          if (p1.getRequestURI.getPath.endsWith("1")) {
            counter1.incrementAndGet()
          }
          if (p1.getRequestURI.getPath.endsWith("2")) {
            counter2.incrementAndGet()
          }
          if (p1.getRequestURI.getPath.endsWith("3")) {
            counter3.incrementAndGet()
          }
          if (p1.getRequestURI.getPath.endsWith("4")) {
            counter4.incrementAndGet()
          }
          if (p1.getRequestURI.getPath.endsWith("5")) {
            counter5.incrementAndGet()
          }
        }
      })
      server.start()
      server
    }
  }

  def user(api: ServicesApi, service: String, nbrRequests: Int): Future[Unit] = {
    Future {
      val client = api.httpClient(name = service, retry = 10)
      for (i <- 1 to nbrRequests) {
        try {
          val json = Await.result(client.get().json, Duration(30, TimeUnit.SECONDS))
          Thread.sleep(200)
          json
        } catch {
          case _ =>
        }
      }
    }
  }

  def chaosMonkey(hosts: List[Host]): Future[Unit] = {
    Future {
      hosts.foreach(h => h.fail())
    }
  }

  "Service API" should {

    val wait = Duration(500, TimeUnit.SECONDS)
    val masterPort = Network.freePort
    val master = Services("master").startAndJoin(s"127.0.0.1", masterPort)
    var hosts = List.empty[Host]
    var users = List.empty[Future[Unit]]
    var monkey : Future[Unit] = Future.successful(())
    val nbrHosts = 50
    val nbrClients = 5
    val nbrRequests = 100

    "Run on several hosts" in {
      hosts = (1 to nbrHosts).map(i => new Host(s"Host $i", masterPort)).map(host => host.start()).toList
      Thread.sleep(10000)
      success
    }

    "Support concurrent clients" in {
      println("Running clients ...")
      users = users ++ (1 to nbrClients).map(i => user(master, "SERVICE1", nbrRequests)).toList
      users = users ++ (1 to nbrClients).map(i => user(master, "SERVICE2", nbrRequests)).toList
      users = users ++ (1 to nbrClients).map(i => user(master, "SERVICE3", nbrRequests)).toList
      users = users ++ (1 to nbrClients).map(i => user(master, "SERVICE4", nbrRequests)).toList
      users = users ++ (1 to nbrClients).map(i => user(master, "SERVICE5", nbrRequests)).toList
      Thread.sleep(2000)
      success
    }

    "Support failures" in {
      println("Running chaos monkey ...")
      monkey = chaosMonkey(hosts)
      success
    }

    "Shutdown everything" in {
      println("Waiting for the end ...")
      Await.result(monkey, wait)
      users.foreach(u => Await.result(u, wait))
      hosts.foreach(h => h.stop())
      master.stop()
      hosts.foreach(h => h.displayStats())
      println(s"Total should be : ${(nbrClients * 5) * nbrRequests}")
      val total = hosts.foldLeft(0)(_ + _.total())
      println(s"Total is : $total")
      success
    }
  }
}