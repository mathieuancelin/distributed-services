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
      val config = Configuration.load().withValue("services.http.port", 9999).withValue("services.http.host", "localhost")
      val (api, regs) = Services("AutoNode").bootFromConfig(config)
      Thread.sleep(30000)
      api.stop()
      success
    }
  }
}