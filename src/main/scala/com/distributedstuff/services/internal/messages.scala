package com.distributedstuff.services.internal

import akka.actor.Address
import com.distributedstuff.services.api.Service

case class NodeState(from: Address, state: java.util.Set[Service])
case class WhatIsYourState()
case class AskMeMyState(from: Address)
