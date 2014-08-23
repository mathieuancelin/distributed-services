package com.distributedstuff.services.clients

import akka.actor.ActorSystem
import akka.util.Timeout
import com.distributedstuff.services.api.Client

// TODO : implements HTTP client
class HttpClient(name: String, system: ActorSystem, timeout: Timeout, client: Client) {

}
