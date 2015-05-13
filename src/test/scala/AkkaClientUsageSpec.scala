import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, TimeUnit}

import akka.actor.{Actor, ActorSystem, Props}
import akka.util.Timeout
import com.distributedstuff.services.api.{Service, Services, ServicesApi}
import com.distributedstuff.services.clients.akkasupport.AkkaClientSupport
import com.distributedstuff.services.common.Reference
import com.google.common.collect.Lists
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.specs2.mutable.{Specification, Tags}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

case class Req()
case class Resp()

class ServerActor(counter: AtomicInteger) extends Actor {
  override def receive: Receive = {
    case Req() => {
      counter.incrementAndGet()
      sender() ! Resp()
    }
    case _ =>
  }
}

class AkkaClientUsageSpec extends Specification with Tags {
  sequential

  implicit val ec = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  def createAkkaserver(name: String, port: Int, counter: AtomicInteger): ActorSystem = {
    val system = ActorSystem.apply(name, ConfigFactory
      .empty()
      .withValue("akka.remote.netty.tcp.port", ConfigValueFactory.fromAnyRef(port))
      .withValue("akka.remote.netty.tcp.hostname", ConfigValueFactory.fromAnyRef("127.0.0.1"))
      .withValue("akka.remote.enabled-transports", ConfigValueFactory.fromAnyRef(Lists.newArrayList("akka.remote.netty.tcp")))
      .withValue("akka.actor.provider",  ConfigValueFactory.fromAnyRef("akka.remote.RemoteActorRefProvider"))
    )
    system.actorOf(Props.create(classOf[ServerActor], counter), "service1")
    system
  }


  def user(api: ServicesApi) = {
    implicit val to = Timeout(10, TimeUnit.SECONDS)
    Future {
      val client = api.akkaClient("SERVICE1")
      for (i <- 1 to 1000) {
        val resp = Await.result(client.ask[Resp](Req()), Duration(10, TimeUnit.SECONDS))
        resp
      }
    }
  }

  "Service API with Http support" should {

    val serviceNode1 = Services("node1").startAndJoin("127.0.0.1", 7777)
    val serviceNode2 = Services("node2").start().join("127.0.0.1:7777")
    val serviceNode3 = Services("node3").start().join("127.0.0.1:7777")

    val service1 = Service(name = "SERVICE1", url = "akka.tcp://mysystem@127.0.0.1:8001/user/service1")
    val service2 = Service(name = "SERVICE1", url = "akka.tcp://mysystem@127.0.0.1:8002/user/service1")
    val service3 = Service(name = "SERVICE1", url = "akka.tcp://mysystem@127.0.0.1:8003/user/service1")

    val counter1 = new AtomicInteger(0)
    val counter2 = new AtomicInteger(0)
    val counter3 = new AtomicInteger(0)

    val server1 = Reference[ActorSystem](createAkkaserver("mysystem", 8001, counter1))
    val server2 = Reference[ActorSystem](createAkkaserver("mysystem", 8002, counter2))
    val server3 = Reference[ActorSystem](createAkkaserver("mysystem", 8003, counter3))


    "Register some services" in {
      Thread.sleep(2000)
      serviceNode1.registerService(service1)
      serviceNode1.registerService(service2)
      serviceNode1.registerService(service3)
      Thread.sleep(10000)
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

      counter1.get() should be_>(800)
      counter2.get() should be_>(800)
      counter3.get() should be_>(800)

      counter1.get() should be_<(1200)
      counter2.get() should be_<(1200)
      counter3.get() should be_<(1200)
      success
    }

    "Shutdown everything" in {
      serviceNode1.stop()
      serviceNode2.stop()
      serviceNode3.stop()
      server1().shutdown()
      server2().shutdown()
      server3().shutdown()
      success
    }
  }
}