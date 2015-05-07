package com.distributedstuff.services.internal

import com.distributedstuff.services.api.{Registration, Service}
import com.distributedstuff.services.internal.ReplicatedCache.Evict
import org.joda.time.DateTime

class ServiceRegistration(is: ServiceDirectory, service: Service) extends Registration {
  def unregister() = {
    is.replicatedCache ! Evict(service)
    is.system.eventStream.publish(ServiceUnregistered(DateTime.now(), service))
  }
}

class ListenerRegistration(f: () => Unit) extends Registration {
  def unregister(): Unit = f()
}