package com.distributedstuff.services.internal

import akka.actor.ActorRef
import akka.agent.Agent
import com.distributedstuff.services.api.{Registration, Service}

class ServiceRegistration(is: ServiceDirectory, service: Service) extends Registration {
  def unregister() = {
    import collection.JavaConversions._
    for (e <- is.globalState.entrySet()) {
      if (e.getValue.contains(service)) e.getValue.remove(service)
    }
    is.tellEveryoneToAskMe()
    //// TODO : tell listeners that service is gone
  }
}

class ListenerRegistration(ref: ActorRef, agent: Agent[Set[ActorRef]]) extends Registration {
  def unregister(): Unit = agent.alter(_.filterNot(_ == ref))
}