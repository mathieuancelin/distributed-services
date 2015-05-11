import java.util.concurrent.Executors

import com.distributedstuff.services.api.{Registration, Service, Services}
import com.distributedstuff.services.common.Configuration
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.{Specification, Tags}

import scala.concurrent.ExecutionContext

class HttpApiUsageSpec extends Specification with Tags {
  sequential

  implicit val ec = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  "Service API" should {
    "Register some services" in {
      val (api, regs) = Services("AutoNode").bootFromConfig()
      Thread.sleep(3000)
      api.stop()
      success
    }
  }
}