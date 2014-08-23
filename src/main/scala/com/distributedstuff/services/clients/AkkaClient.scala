package com.distributedstuff.services.clients

import akka.actor.{Address, ActorRef, ActorSystem, RootActorPath}
import akka.util.Timeout
import com.distributedstuff.services.api.{ServicesApi, Client}

import scala.concurrent.Future
import scala.reflect.ClassTag

// TODO : implements Akka client

class AkkaClient(name: String, system: ActorSystem, timeout: Timeout, client: Client) {

//  def !(message: Any, sender: ActorRef = system.deadLetters): Future[Unit] = tell(message, sender)
//
//  def tell(message: Any, sender: ActorRef = system.deadLetters): Future[Unit] = {
//    system.actorSelection(RootActorPath(member.address) / "user" / name).tell(message, sender)
//    Future.successful(())
//  }
//
//  def ask[T](message: Any)(implicit timeout: Timeout = timeout, tag: ClassTag[T]): Future[Option[T]] = {
//    akka.pattern.ask(system.actorSelection(RootActorPath(member.address) / "user" / name), message)(timeout).mapTo[T](tag).map(Some(_))(system.dispatcher)
//  }
//
//  def !!(message: Any, sender: ActorRef = system.deadLetters): Future[Unit] = broadcast(message, sender)
//
//  def broadcast(message: Any, sender: ActorRef = system.deadLetters): Future[Unit] = {
//    system.actorSelection(RootActorPath(member.address) / name).tell(message, sender)
//    Future.successful(())
//  }
//
//  def !!!(message: Any, sender: ActorRef = system.deadLetters): Future[Unit] = broadcastAll(message, sender)
//
//  def broadcastAll(message: Any, sender: ActorRef = system.deadLetters): Future[Unit] = {
//    system.actorSelection(RootActorPath(member.address) / name).tell(message, sender)
//    Future.successful(())
//  }
}