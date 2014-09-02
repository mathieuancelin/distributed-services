distributed-services
===========================

The project provides a Scala API to easily create distributed service directory in your products.
 
This project is based on Akka and the akka-cluster extension that provides a fault-tolerant decentralized peer-to-peer based cluster membership service with 
no single point of failure or single point of bottleneck. It does this using gossip protocols and an automatic failure detector. 

Nodes exchange messages periodicaly or on demand to discover services hosted by other nodes.

This project does not aim at exposing distributed services. It just provides a way to let other nodes know that one particular node
is hosting one particular type of service.

This project also offers a pluggable client API based on the directory with monitoring and load balancing (in case you have multiple instances of the same service)


```scala

val serviceNode1 = Services("node1").startAndJoin("127.0.0.1", 7777)   // should be deployed on multiple JVMs
val serviceNode2 = Services("node2").start().join("127.0.0.1:7777")
val serviceNode3 = Services("node3").start().join("127.0.0.1:7777")

val service1 = Service(name = "SERVICE1", url = "http://127.0.0.1:9000/service1")
val service2 = Service(name = "SERVICE2", url = "akka.tcp://remotesystem@127.0.0.1:9001/user/service2")
val service3 = Service(name = "SERVICE1", url = "http://127.0.0.1:9002/service3")

val reg1 = serviceNode1.registerService(service1)
val reg2 = serviceNode2.registerService(service2)
val reg3 = serviceNode3.registerService(service3)

val services1: Set[Service] = serviceNode1.services("SERVICE1") // do whatever you want with those
...
val services2: Option[Service] = serviceNode1.service("SERVICE2") // do whatever you want with that one
...

// Classic client usage, protocol agnostic

val client = serviceNode1.client("SERVICE1")
val json: Future[JsValue] = client.callM(WS.url(_.url).get().map(_.json)) // the client will roundrobin calls between service1 and service3

// Built-in Http client

import com.distributedstuff.services.clients.httpsupport.HttpClientSupport
import com.distributedstuff.services.common.http.support._

val clientHttp = serviceNode3.httpClient("SERVICE1")
val json: Future[JsValue] = clientHttp.get().json

// Built-in Akka client

import com.distributedstuff.services.clients.akkasupport.AkkaClientSupport

case class DoSomething()
case class Req()
case class Resp()

val clientAkka = serviceNode3.httpClient("SERVICE2")
clientAkka ! DoSomething()
val resp: Future[Resp] = clientAkka.ask[Resp](Req())

...

reg1.unregister()
reg2.unregister()
reg3.unregister()

serviceNode1.stop()
serviceNode2.stop()
serviceNode3.stop()

```
