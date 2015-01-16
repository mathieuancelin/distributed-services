import java.util.UUID
import java.util.concurrent.Executors

import com.distributedstuff.services.api.{Service, Services}
import com.distributedstuff.services.clients.Retry
import com.distributedstuff.services.common.Configuration
import com.distributedstuff.services.clients.akkasupport.AkkaClientSupport
import com.distributedstuff.services.clients.httpsupport.HttpClientSupport
import play.api.libs.json.Json

import scala.concurrent.{Future, ExecutionContext}

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
  val APPNAME = configuration.getString("app.name").getOrElse("IDENTITY")
  val MACHINENAME = configuration.getString("services.boot.host").getOrElse("127.0.0.1")
  val AKKA_PORT = configuration.getString("services.boot.port").getOrElse(2551)
  val HTTP_PORT = configuration.getString("app.http.port").getOrElse(8000)

  val registry = Services(s"$MACHINENAME-$APPNAME").startFromConfig()

  val service1 = Service(name = "USERINFO", url = s"http://$MACHINENAME:$HTTP_PORT/user", version = Some("1.2.0"))
  val service2 = Service(name = "USERCHECK", url = s"akka.tcp://mysystem@$MACHINENAME:$AKKA_PORT/user/service2")

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

  val client = Env.registry.httpClient(name = "USERINFO", version = Some("1.2.0")).combineWith(Retry.withExpBackoff(5))

  def findUser(email: String): Future[Option[User]] = {
    client.withParams("email" -> email).get().map(r => Json.parse(r.body().string()).asOpt(User.userFormat))
  }
}

object Service2Client  {
  import Env.ec

  case class UserValidRequest(email: String, password: String)
  case class UserValidResponse(valid: Boolean, user: Option[User])

  val client = Env.registry.akkaClient(name = "USERCHECK").combineWith(Retry.withExpBackoff(5))

  def isValidUser(email: String, password: String): Future[Option[(Boolean, User)]] = {
    client.ask[UserValidResponse](UserValidRequest(email, password)).map {
      case UserValidResponse(_, None) => None
      case UserValidResponse(valid, Some(user)) => Some((valid, user))
    }
  }
}