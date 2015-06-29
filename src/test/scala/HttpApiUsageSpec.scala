import java.util.concurrent.{TimeUnit, Executors}

import com.distributedstuff.services.api.{Registration, Service, Services}
import com.distributedstuff.services.common.{Network, IdGenerator, Configuration}
import com.distributedstuff.services.common.http.Http
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.{Specification, Tags}
import play.api.libs.json.{JsArray, Json}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}

class HttpApiUsageSpec extends Specification with Tags {
  sequential

  implicit val ec = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  val port = Network.freePort
  val config = Configuration.load().withValue("services.http.port", port).withValue("services.http.host", "0.0.0.0")

  "Http Service API" should {

    val infinity = Duration(30, TimeUnit.SECONDS)
    val (api, regs) = Services("AutoNode", config).bootFromConfig()
    var uuid: String = "-"

    "Register some services" in {
      println("Register some services")
      val response = Await.result(Http.url(s"http://localhost:$port/services").withBody(
        Json.obj(
          "uid" -> IdGenerator.uuid,
          "name" -> "HttpService1",
          "url" -> "http://localhost:666/hellyeah",
          "roles" -> Json.arr(),
          "metadata" -> Json.obj()
        )
      ).post(), infinity)
      uuid = (Json.parse(response.body().string()) \ "regId").as[String]
      Thread.sleep(3000)
      success
    }

    "Get 1 service" in {
      println("Get 1 service")
      val response = Await.result(Http.url(s"http://localhost:$port/services?name=HttpService1").get(), infinity)
      val json = Json.parse(response.body().string())
      json.as[JsArray].value.length shouldEqual 1
      Thread.sleep(10000)
      success
    }

    "Get no service" in {
      println("Get no service")
      val response = Await.result(Http.url(s"http://localhost:$port/services?name=HttpService1").get(), infinity)
      val json = Json.parse(response.body().string())
      json.as[JsArray].value.length shouldEqual 0
      success
    }

    "Register some services again" in {
      println("Register some services again")
      val response = Await.result(Http.url(s"http://localhost:$port/services").withBody(
        Json.obj(
          "uid" -> IdGenerator.uuid,
          "name" -> "HttpService1",
          "url" -> "http://localhost:666/hellyeah",
          "roles" -> Json.arr(),
          "metadata" -> Json.obj()
        )
      ).post(), infinity)
      uuid = (Json.parse(response.body().string()) \ "regId").as[String]
      Thread.sleep(3000)
      success
    }

    "Get 1 service" in {
      println("Get 1 service")
      val response = Await.result(Http.url(s"http://localhost:$port/services?name=HttpService1").get(), infinity)
      val json = Json.parse(response.body().string())
      json.as[JsArray].value.length shouldEqual 1
      Thread.sleep(3000)
      success
    }

    "Heartbeat" in {
      println("Heartbeat 1")
      val response = Await.result(Http.url(s"http://localhost:$port/services?regId=$uuid").put(), infinity)
      val json = Json.parse(response.body().string())
      success
    }

    "Get 1 service again" in {
      //try {
        println("Get 1 service again")
        Thread.sleep(3000)
        var response = Await.result(Http.url(s"http://localhost:$port/services?name=HttpService1").get(), infinity)
        var json = Json.parse(response.body().string())
        json.as[JsArray].value.length shouldEqual 1
        println("Heartbeat 2")
        response = Await.result(Http.url(s"http://localhost:$port/services?regId=$uuid").put(), infinity)
        json = Json.parse(response.body().string())
      //} catch {
      //  case e => e.printStackTrace()
      //}
      success
    }

    "Remove the service" in {
      println("Remove the service")
      val response = Await.result(Http.url(s"http://localhost:$port/services?regId=$uuid").delete(), infinity)
      val json = Json.parse(response.body().string())
      Thread.sleep(3000)
      success
    }

    "Get no service anymore" in {
      println("Get no service anymore")
      val response = Await.result(Http.url(s"http://localhost:$port/services?name=HttpService1").get(), infinity)
      val json = Json.parse(response.body().string())
      json.as[JsArray].value.length shouldEqual 0
      success
    }

    "shutdown everything" in {
      println("shutdown everything")
      api.stop()
      success
    }
  }
}