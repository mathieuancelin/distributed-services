import java.util.UUID
import java.util.concurrent.Executors

import com.distributedstuff.services.api.{Service, Services}
import com.distributedstuff.services.clients.akkasupport.AkkaClientSupport
import com.distributedstuff.services.clients.httpsupport.HttpClientSupport
import com.distributedstuff.services.common.Configuration
import play.api.libs.json.Json

import scala.concurrent.{ExecutionContext, Future}

case class User(id: UUID, email: String, name: String)

object User {
  val userFormat = Json.format[User]
}

trait Service1 {
  def findUser(email: String): Future[Option[User]]
}

trait Service2 {
  def isValidUser(email: String, password: String): Future[Option[(Boolean, User)]]
}

object Env {

  implicit val ec = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())

  val configuration = Configuration.load()
  val APP_NAME = configuration.getString("app.name").getOrElse("IDENTITY")
  val MACHINE_NAME = configuration.getString("services.boot.host").getOrElse("127.0.0.1")
  val AKKA_PORT = configuration.getString("services.boot.port").getOrElse(2551)
  val HTTP_PORT = configuration.getString("app.http.port").getOrElse(8000)

  val registry = Services(s"$MACHINE_NAME-$APP_NAME").startFromConfig()

  val service1 = Service(name = "USERINFO", url = s"http://$MACHINE_NAME:$HTTP_PORT/user", version = Some("1.2.0"))
  val service2 = Service(name = "USERCHECK", url = s"akka.tcp://mysystem@$MACHINE_NAME:$AKKA_PORT/user/service2")

  val registration1 = registry.registerService(service1)
  val registration2 = registry.registerService(service2)

  def shutdown() = {
    registration1.unregister()
    registration2.unregister()
    registry.stop()
  }
}

object Service1Client {

  import Env.ec

  val client = Env.registry.httpClient(name = "USERINFO", version = Some("1.2.0"), retry = 5)

  def findUser(email: String): Future[Option[User]] = {
    client.withParams("email" -> email).get().map(r => Json.parse(r.body().string()).asOpt(User.userFormat))
  }
}

object Service2Client  {
  import Env.ec

  case class UserValidRequest(email: String, password: String)
  case class UserValidResponse(valid: Boolean, user: Option[User])

  val client = Env.registry.akkaClient(name = "USERCHECK", retry = 5)

  def isValidUser(email: String, password: String): Future[Option[(Boolean, User)]] = {
    client.ask[UserValidResponse](UserValidRequest(email, password)).map {
      case UserValidResponse(_, None) => None
      case UserValidResponse(valid, Some(user)) => Some((valid, user))
    }
  }
}