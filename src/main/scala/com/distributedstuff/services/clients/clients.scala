package com.distributedstuff.services

import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import akka.util.Timeout
import com.distributedstuff.services.api.ServicesApi
import com.distributedstuff.services.common.http.{Http, RequestHolder}
import com.distributedstuff.services.internal.{LoadBalancedClient, ServiceDirectory}

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

package object clients {

    object httpsupport {

        type HttpClient = RequestHolder

        implicit final class HttpClientSupport(services: ServicesApi) {
            def httpClient(name: String, roles: Seq[String] = Seq(), version: Option[String] = None, retry: Int = 5)(implicit ec: ExecutionContext): HttpClient = {
                val client = services.client(name, roles, version, retry)
                Http.empty().withApiClient(client.asInstanceOf[LoadBalancedClient]).withExecutionContext(ec)
            }
        }

    }

    object akkasupport {

        implicit final class AkkaClientSupport(services: ServicesApi) {
            def akkaClient(name: String, roles: Seq[String] = Seq(), version: Option[String] = None, retry: Int = 5, timeout: Timeout = Timeout(10, TimeUnit.SECONDS)): AkkaClient = {
                val client = services.client(name, roles, version, retry)
                new AkkaClient(name, roles, version, services, timeout, client.asInstanceOf[LoadBalancedClient])
            }
        }

        class AkkaClient(name: String, roles: Seq[String], version: Option[String], services: ServicesApi, timeout: Timeout, client: LoadBalancedClient) {

            val system = services.asInstanceOf[ServiceDirectory].system
            implicit val ec = system.dispatcher

            private[this] def target = client.call(s => system.actorSelection(s.url))

            def !(message: Any, sender: ActorRef = system.deadLetters): Future[Unit] = tell(message, sender)

            def tell(message: Any, sender: ActorRef = system.deadLetters): Future[Unit] = {
                target.map(t => t.tell(message, sender)).map(_ => ())
            }

            def ask[T](message: Any)(implicit timeout: Timeout = timeout, tag: ClassTag[T]): Future[Option[T]] = {
                target.flatMap { t => akka.pattern.ask(t, message)(timeout).mapTo[T](tag).map(Some(_)) }
            }

            def !!(message: Any, sender: ActorRef = system.deadLetters): Future[Unit] = broadcast(message, sender)

            def broadcast(message: Any, sender: ActorRef = system.deadLetters): Future[Unit] = {
                services.services(name, roles, version).foreach { service =>
                    system.actorSelection(service.url).tell(message, sender)
                }
                Future.successful(())
            }
        }

    }
}
