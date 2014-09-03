package com.distributedstuff.services.internal

import akka.actor.ActorRef
import akka.agent.Agent
import com.distributedstuff.services.api.{Registration, Service}
import org.joda.time.DateTime

class ServiceRegistration(is: ServiceDirectory, service: Service) extends Registration {
  def unregister() = {
    import collection.JavaConversions._
    for (e <- is.globalState.entrySet()) {
      if (e.getValue.contains(service)) {
        e.getValue.remove(service)
      }
    }
    is.askEveryoneButMe()
    is.tellEveryoneToAskMe()
    is.system.eventStream.publish(ServiceUnregistered(DateTime.now(), service))
  }
}

class ListenerRegistration(f: () => Unit) extends Registration {
  def unregister(): Unit = f()
}