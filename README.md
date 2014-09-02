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

// Create the cluster of apps. Ideally each node is started in a different JVM (even physical node)
val serviceNode1 = Services("node1").startAndJoin("127.0.0.1", 7777)
val serviceNode2 = Services("node2").start().join("127.0.0.1:7777")
val serviceNode3 = Services("node3").start().join("127.0.0.1:7777")

// Create some service description 
val service1 = Service(name = "SERVICE1", url = "http://127.0.0.1:9000/service1")
val service2 = Service(name = "SERVICE2", url = "akka.tcp://remotesystem@127.0.0.1:9001/user/service2", version = "2.0")
val service3 = Service(name = "SERVICE1", url = "http://127.0.0.1:9002/service3")

// Register services in any application
val reg1 = serviceNode1.registerService(service1)
val reg2 = serviceNode2.registerService(service2)
val reg3 = serviceNode3.registerService(service3)

// Now you can query services based on their names, versions and roles

val services1: Set[Service] = serviceNode1.services("SERVICE1") // do whatever you want with those
...
val services2: Option[Service] = 
    serviceNode1.service(name = "SERVICE2", version = "2.0") // do whatever you want with that one
...

// The library provide a notion of client to consume services. 
// The client will be able to split the load across matching instances of services

// Classic client usage, protocol agnostic

val client = serviceNode1.client("SERVICE1")
val json: Future[JsValue] = client.callM(WS.url(_.url).get().map(_.json))

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

val clientAkka = serviceNode3.akkaClient(name = "SERVICE2", version = "2.0")
clientAkka ! DoSomething()
val resp: Future[Resp] = clientAkka.ask[Resp](Req())

...

// Services are dynamic, you can unregister at any time
reg1.unregister()
reg2.unregister()
reg3.unregister()

// Stop the nodes
serviceNode1.stop()
serviceNode2.stop()
serviceNode3.stop()

```
