import java.util.concurrent.Executors

import com.distributedstuff.services.api.{Registration, Service, Services}
import com.distributedstuff.services.common.IdGenerator
import org.specs2.mutable.{Specification, Tags}

import scala.concurrent.ExecutionContext

class BasicUsageSpec extends Specification with Tags {
  sequential

  implicit val ec = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  "Service API" should {

    val serviceNode1 = Services("node1").startAndJoin("127.0.0.1", 7778)
    val serviceNode2 = Services("node2").start().join("127.0.0.1:7778")
    val serviceNode3 = Services("node3").start().join("127.0.0.1:7778")
    var reg1: Registration = null
    var reg2: Registration = null
    var reg3: Registration = null
    val service1 = Service(name = "SERVICE1", url = "http://monservice:9000/service1")
    val service2 = Service(name = "SERVICE2", url = "http://monservice:9000/service2")
    val service3 = Service(name = "SERVICE3", url = "http://monservice:9000/service3")


    "Register some services" in {
      println("Register some services")
      Thread.sleep(5000) // Waiting for proper cluster joining
      reg1 = serviceNode1.registerService(service1)
      reg2 = serviceNode2.registerService(service2)
      reg3 = serviceNode3.registerService(service3)
      Thread.sleep(5000) // Waiting for proper cluster joining
      success
    }

    "Check if services are registered" in {
      println("Check if services are registered")
      Thread.sleep(5000) // waiting for node sync
      serviceNode2.allServices().size shouldEqual 3
      serviceNode3.allServices().size shouldEqual 3
      serviceNode1.allServices().size shouldEqual 3
      success
    }

    "Check if filtered services are registered" in {
      println("Check if filtered services are registered")
      Thread.sleep(2000)

      serviceNode1.service("SERVICE4") should beNone
      serviceNode2.service("SERVICE4") should beNone
      serviceNode3.service("SERVICE4") should beNone

      serviceNode1.services("SERVICE1").size shouldEqual 1
      serviceNode1.services("SERVICE2").size shouldEqual 1
      serviceNode1.services("SERVICE3").size shouldEqual 1
      serviceNode2.services("SERVICE1").size shouldEqual 1
      serviceNode2.services("SERVICE2").size shouldEqual 1
      serviceNode2.services("SERVICE3").size shouldEqual 1
      serviceNode3.services("SERVICE1").size shouldEqual 1
      serviceNode3.services("SERVICE2").size shouldEqual 1
      serviceNode3.services("SERVICE3").size shouldEqual 1

      serviceNode1.service("SERVICE1").map(_.name) shouldEqual Some(service1.name)
      serviceNode1.service("SERVICE1").map(_.uid) shouldEqual Some(service1.uid)
      serviceNode1.service("SERVICE1").map(_.url) shouldEqual Some(service1.url)
      serviceNode2.service("SERVICE1").map(_.name) shouldEqual Some(service1.name)
      serviceNode2.service("SERVICE1").map(_.uid) shouldEqual Some(service1.uid)
      serviceNode2.service("SERVICE1").map(_.url) shouldEqual Some(service1.url)
      serviceNode3.service("SERVICE1").map(_.name) shouldEqual Some(service1.name)
      serviceNode3.service("SERVICE1").map(_.uid) shouldEqual Some(service1.uid)
      serviceNode3.service("SERVICE1").map(_.url) shouldEqual Some(service1.url)

      serviceNode1.service("SERVICE2").map(_.name) shouldEqual Some(service2.name)
      serviceNode1.service("SERVICE2").map(_.uid) shouldEqual Some(service2.uid)
      serviceNode1.service("SERVICE2").map(_.url) shouldEqual Some(service2.url)
      serviceNode2.service("SERVICE2").map(_.name) shouldEqual Some(service2.name)
      serviceNode2.service("SERVICE2").map(_.uid) shouldEqual Some(service2.uid)
      serviceNode2.service("SERVICE2").map(_.url) shouldEqual Some(service2.url)
      serviceNode3.service("SERVICE2").map(_.name) shouldEqual Some(service2.name)
      serviceNode3.service("SERVICE2").map(_.uid) shouldEqual Some(service2.uid)
      serviceNode3.service("SERVICE2").map(_.url) shouldEqual Some(service2.url)

      serviceNode1.service("SERVICE3").map(_.name) shouldEqual Some(service3.name)
      serviceNode1.service("SERVICE3").map(_.uid) shouldEqual Some(service3.uid)
      serviceNode1.service("SERVICE3").map(_.url) shouldEqual Some(service3.url)
      serviceNode2.service("SERVICE3").map(_.name) shouldEqual Some(service3.name)
      serviceNode2.service("SERVICE3").map(_.uid) shouldEqual Some(service3.uid)
      serviceNode2.service("SERVICE3").map(_.url) shouldEqual Some(service3.url)
      serviceNode3.service("SERVICE3").map(_.name) shouldEqual Some(service3.name)
      serviceNode3.service("SERVICE3").map(_.uid) shouldEqual Some(service3.uid)
      serviceNode3.service("SERVICE3").map(_.url) shouldEqual Some(service3.url)

      success
    }

    "Unregister services" in {
      println("Unregister services")
      reg1.unregister()
      reg2.unregister()
      reg3.unregister()
      Thread.sleep(10000)
      success
    }

    "Check if services unregistered" in {
      println("Check if services unregistered")
      serviceNode1.allServices().size shouldEqual 0
      serviceNode2.allServices().size shouldEqual 0
      serviceNode3.allServices().size shouldEqual 0
      success
    }

    "Shutdown everything" in {
      serviceNode1.stop()
      serviceNode2.stop()
      serviceNode3.stop()
      success
    }
  }
}