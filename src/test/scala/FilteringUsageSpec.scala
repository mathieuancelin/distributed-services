import java.util.concurrent.Executors

import com.distributedstuff.services.api.{Service, Registration, Services}
import com.distributedstuff.services.common.IdGenerator
import org.specs2.mutable.{Specification, Tags}

import scala.concurrent.ExecutionContext

class FilteringUsageSpec extends Specification with Tags {
  sequential

  implicit val ec = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  "Service API" should {

    val serviceNode1 = Services("node1").start("127.0.0.1", 7777).joinSelf()

    val service100     = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev1.0.0")
    val service100r4   = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev1.0.0", roles = Seq("ROLE4"))
    val service100ver  = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev1.0.0", version = Some("1.0.0"))
    val service100vr5  = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev1.0.0", version = Some("1.0.0"), roles = Seq("ROLE5"))
    val service100vr1  = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev1.0.0", version = Some("1.0.0"), roles = Seq("ROLE1"))
    val service100vr2  = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev1.0.0", version = Some("1.0.0"), roles = Seq("ROLE1", "ROLE2"))
    val service100vr22 = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev1.0.0", version = Some("1.0.0"), roles = Seq("ROLE1", "ROLE3"))
    val service100vr3  = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev1.0.0", version = Some("1.0.0"), roles = Seq("ROLE1", "ROLE2", "ROLE3"))

    val service120     = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev1.2.0")
    val service120r4   = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev1.2.0", roles = Seq("ROLE4"))
    val service120ver  = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev1.2.0", version = Some("1.2.0"))
    val service120vr1  = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev1.2.0", version = Some("1.2.0"), roles = Seq("ROLE1"))
    val service120vr5  = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev1.2.0", version = Some("1.2.0"), roles = Seq("ROLE5"))
    val service120vr2  = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev1.2.0", version = Some("1.2.0"), roles = Seq("ROLE1", "ROLE2"))
    val service120vr22 = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev1.2.0", version = Some("1.2.0"), roles = Seq("ROLE1", "ROLE3"))
    val service120vr3  = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev1.2.0", version = Some("1.2.0"), roles = Seq("ROLE1", "ROLE2", "ROLE3"))

    val service200     = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev2.0.0")
    val service200r4   = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev2.0.0", roles = Seq("ROLE4"))
    val service200ver  = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev2.0.0", version = Some("2.0.0"))
    val service200vr1  = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev2.0.0", version = Some("2.0.0"), roles = Seq("ROLE1"))
    val service200vr5  = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev2.0.0", version = Some("2.0.0"), roles = Seq("ROLE5"))
    val service200vr2  = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev2.0.0", version = Some("2.0.0"), roles = Seq("ROLE1", "ROLE2"))
    val service200vr22 = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev2.0.0", version = Some("2.0.0"), roles = Seq("ROLE1", "ROLE3"))
    val service200vr3  = Service(IdGenerator.uuid, "SERVICE1", "http://monservice:9000/servicev2.0.0", version = Some("2.0.0"), roles = Seq("ROLE1", "ROLE2", "ROLE3"))

    val service2200vr3  = Service(IdGenerator.uuid, "SERVICE2", "http://monservice:9000/servicev2.0.0", version = Some("2.0.0"), roles = Seq("ROLE1", "ROLE2", "ROLE3"))


    "Register some services" in {
      //Thread.sleep(10000) // Waiting for proper cluster joining

      serviceNode1.registerService(service100    )
      serviceNode1.registerService(service100r4  )
      serviceNode1.registerService(service100ver )
      serviceNode1.registerService(service100vr1 )
      serviceNode1.registerService(service100vr5 )
      serviceNode1.registerService(service100vr2 )
      serviceNode1.registerService(service100vr22)
      serviceNode1.registerService(service100vr3 )
      serviceNode1.registerService(service120    )
      serviceNode1.registerService(service120r4  )
      serviceNode1.registerService(service120ver )
      serviceNode1.registerService(service120vr1 )
      serviceNode1.registerService(service120vr5 )
      serviceNode1.registerService(service120vr2 )
      serviceNode1.registerService(service120vr22)
      serviceNode1.registerService(service120vr3 )
      serviceNode1.registerService(service200    )
      serviceNode1.registerService(service200r4  )
      serviceNode1.registerService(service200ver )
      serviceNode1.registerService(service200vr1 )
      serviceNode1.registerService(service200vr5 )
      serviceNode1.registerService(service200vr2 )
      serviceNode1.registerService(service200vr22)
      serviceNode1.registerService(service200vr3 )
      serviceNode1.registerService(service2200vr3)
      //Thread.sleep(30000) // waiting for node sync
      //serviceNode1.printState()
      success
    }

    "Check if services are registered" in {
      serviceNode1.allServices().size shouldEqual 25
      serviceNode1.allServices(roles = Seq("ROLE1", "ROLE2", "ROLE3")).size shouldEqual 4
      success
    }

    "Check if filtered services are registered" in {

      serviceNode1.services("SERVICE1").size shouldEqual 24
      serviceNode1.services("SERVICE1", roles = Seq("ROLE4")).size shouldEqual 3
      serviceNode1.services("SERVICE1", roles = Seq("ROLE1")).size shouldEqual 12
      serviceNode1.services("SERVICE1", roles = Seq("ROLE2")).size shouldEqual 6
      serviceNode1.services("SERVICE1", roles = Seq("ROLE3")).size shouldEqual 6
      serviceNode1.services("SERVICE1", roles = Seq("ROLE2", "ROLE3")).size shouldEqual 3
      serviceNode1.services("SERVICE1", roles = Seq("ROLE1", "ROLE2")).size shouldEqual 6
      serviceNode1.services("SERVICE1", roles = Seq("ROLE1", "ROLE3")).size shouldEqual 6
      serviceNode1.services("SERVICE1", roles = Seq("ROLE1", "ROLE2", "ROLE3")).size shouldEqual 3

      serviceNode1.services("SERVICE1", version = Some("1.0.0"), roles = Seq("ROLE5")).size shouldEqual 1
      serviceNode1.services("SERVICE1", version = Some("1.2.0"), roles = Seq("ROLE5")).size shouldEqual 1
      serviceNode1.services("SERVICE1", version = Some("2.0.0"), roles = Seq("ROLE5")).size shouldEqual 1
      serviceNode1.services("SERVICE1", version = Some("2.0.0"), roles = Seq("ROLE1", "ROLE2", "ROLE3")).size shouldEqual 1
      serviceNode1.services("SERVICE1", version = Some("2.0.0"), roles = Seq("ROLE1", "ROLE3")).size shouldEqual 2

      success
    }

    "Shutdown everything" in {
      serviceNode1.stop()
      success
    }
  }
}