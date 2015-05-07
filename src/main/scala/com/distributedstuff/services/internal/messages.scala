package com.distributedstuff.services.internal

import com.distributedstuff.services.api.Service
import org.joda.time.DateTime

sealed trait LifecycleEvent
case class ServiceRegistered(at: DateTime, service: Service) extends LifecycleEvent
case class ServiceUnregistered(at: DateTime, service: Service) extends LifecycleEvent
