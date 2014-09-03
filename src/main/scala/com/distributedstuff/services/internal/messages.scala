package com.distributedstuff.services.internal

import akka.actor.Address
import com.distributedstuff.services.api.Service
import org.joda.time.DateTime

case class NodeState(from: Address, state: java.util.Set[Service])
case class WhatIsYourState()
case class AskMeMyState(from: Address)

trait LifecycleEvent
case class ServiceRegistered(at: DateTime, service: Service) extends LifecycleEvent
case class ServiceUnregistered(at: DateTime, service: Service) extends LifecycleEvent
