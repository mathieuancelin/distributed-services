package com.distributedstuff.services.internal

import java.util

import akka.actor.Actor
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import com.distributedstuff.services.api.Service
import com.distributedstuff.services.common.Logger
import org.joda.time.DateTime

import scala.util.{Failure, Success}

private[services] class StateManagerActor(is: ServiceDirectory) extends Actor {
  implicit val ec = context.system.dispatcher
  override def receive: Actor.Receive = {
    case WhatIsYourState() => {
      // Response after an ask
      val from = sender()
      Logger("StateManagerActor").trace(s"$from is asking my state")
      from ! NodeState(is.cluster.selfAddress, Option(is.globalState.get(is.cluster.selfAddress)).getOrElse(new util.HashSet[Service]()))
    }
    case AskMeMyState(from) => {
      // ask the state of from
      Logger("StateManagerActor").trace(s"asking $from its state ...")
      is.askState(from).andThen {
        case Success(state) => {
          is.globalState.put(from, new util.HashSet[Service]())
          is.globalState.get(from).addAll(state)
        }
        case Failure(e) =>
      }
    }
    case _ =>
  }
}

private[services] class ClusterListener(is: ServiceDirectory) extends Actor {

  import collection.JavaConversions._

  val cluster = Cluster(context.system)
  implicit val ec = context.system.dispatcher

  override def preStart(): Unit = {
    cluster.subscribe(self, initialStateMode = InitialStateAsEvents,
      classOf[MemberEvent], classOf[UnreachableMember])
  }

  override def postStop(): Unit = cluster.unsubscribe(self)

  def receive = {
    case MemberUp(member) => is.askEveryoneButMe()
    case UnreachableMember(member) => {
      Option(is.globalState.get(member.address)).map { services =>
        services.foreach { service =>
          is.system.eventStream.publish(ServiceUnregistered(DateTime.now(), service))
        }
      }
      is.globalState.remove(member.address)
      is.askEveryoneButMe()
    }
    case MemberRemoved(member, previousStatus) => {
      Option(is.globalState.get(member.address)).map { services =>
        services.foreach { service =>
          is.system.eventStream.publish(ServiceUnregistered(DateTime.now(), service))
        }
      }
      is.globalState.remove(member.address)
      is.askEveryoneButMe()
    }
    case _: MemberEvent =>
  }
}