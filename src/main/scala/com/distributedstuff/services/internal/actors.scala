package com.distributedstuff.services.internal

import java.util
import java.util.Collections

import akka.actor.Actor
import akka.cluster.{Member, Cluster}
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

  def members(): Seq[Member] = cluster.state.members.toSeq.filterNot(unreachable.contains)
  val unreachable = Collections.synchronizedSet(new util.HashSet[Member]())

  private def displayState() = {
    Logger("CLIENTS_WATCHER").debug(s"----------------------------------------------------------------------------")
    Logger("CLIENTS_WATCHER").debug(s"Cluster members are : ")
    members().foreach { member =>
      Logger("CLIENTS_WATCHER").debug(s"==> ${member.address} :: ${member.getRoles} => ${member.status}")
    }
    Logger("CLIENTS_WATCHER").debug(s"----------------------------------------------------------------------------")
  }

  def receive = {
    case MemberUp(member) => {
      displayState()
      is.askEveryoneButMe()
    }
    case UnreachableMember(member) => {
      Logger("CLIENTS_WATCHER").debug(s"$member is unreachable")
      unreachable.add(member)
      Logger("CLIENTS_WATCHER").debug(s"blacklist : ${unreachable}")
      Option(is.globalState.get(member.address)).map { services =>
        services.foreach { service =>
          is.system.eventStream.publish(ServiceUnregistered(DateTime.now(), service))
        }
      }
      is.globalState.remove(member.address)
      is.askEveryoneButMe()
      displayState()
    }
    case ReachableMember(member) => {
      Logger("CLIENTS_WATCHER").debug(s"$member is reachable again")
      unreachable.remove(member)
      Logger("CLIENTS_WATCHER").debug(s"blacklist : ${unreachable}")
      displayState()
    }
    case MemberRemoved(member, previousStatus) => {
      Option(is.globalState.get(member.address)).map { services =>
        services.foreach { service =>
          is.system.eventStream.publish(ServiceUnregistered(DateTime.now(), service))
        }
      }
      is.globalState.remove(member.address)
      is.askEveryoneButMe()
      displayState()
    }
    case _: MemberEvent =>
  }
}