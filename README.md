distributed-services
===========================

The project provides a Scala API to easily create distributed service directory in your products.
 
This project is based on Akka and the akka-cluster extension that provides a fault-tolerant decentralized peer-to-peer based cluster membership service with 
no single point of failure or single point of bottleneck. It does this using gossip protocols and an automatic failure detector. 

Services descriptors are stored in memry in CRDTs structures using `Akka Data Replication` over `akka-cluster` 
to ensure high availability, scalability, low latency and strong eventual consistency. 

This project does not aim at exposing distributed services. It just provides a way to let other nodes know that one particular node
is hosting one particular type of service.

A service is just the description of an actual service and can be materialized like :

```scala
case class Service(
    uid: String,    // the ID of the service. Should be unique for each instance of services
    name: String,   // the name of a service. Can be shared among service instances
    url: String,    // the location of the service instance
    metadata: Map[String, String], // Metadata about the services (can be empty). For user purpose only
    roles: Seq[String],  // The possible roles of a service instance (can be empty)
    version: Option[String] // The version of the service instance  (can be empty)
)
```

This project also offers a pluggable client API with monitoring and load balancing (in case you have multiple instances of the same service)

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

You can also bootstrap the API only from configuration 

```javascript

services {
    nodename = "node1"
    boot {
        host = "127.0.0.1"
        port = 9876
        seeds = ["192.168.1.34:7896", "192.168.1.35:7896"]
    }
    autoexpose = [ // service description registered at startup
        {
            name = "SERVICE1"
            url = "http://192.168.1.23:9000/service1"
            version = "2.34"
            roles = ["worker"]
        },
        {
            name = "SERVICE2"
            url = "akka.tcp://thesystem@192.168.1.23:7678/user/service2"
            version = "1.0.1"
        }
    ]
}
```

```scala
// just bootstrap the library and publish services from config file descriptors
val (services, registrations) = Services.bootFromConfig() 
```

Http API
---------

You can also use the library as an HTTP service to avoid embedding it in your code.

You need to provide some config 

```javascript
services {
  http {
    port = 9999
    host = 0.0.0.0
  }
}
```

then you can bootstrap it programmatically 

```scala
val (api, _) = Services("AutoNode", config).bootFromConfig()
```

or you can just run a Jar file 

```
scala -cp MyJar.jar com.distributedstuff.services.Main 
```

Then you will access to the following API

```
POST   /services                          register a service descriptor (json body). Returns a registration ID
PUT    /services?regId=xxxxx              heartbeat for a registration
DELETE /services?regId=xxxxx              unregister a service descriptor
GET    /services?name=x&version=x&role=x  search for services matching the query. Each argument is optional
```

A descriptor is written like 

```javascript
{
  "uid": "ebb86bb52-2592-44c0-8c9d-5059909d3108",
  "name": "service1",
  "url": "http://localhost:9000/index",
  "version": "1.2.3",
  "metadata": {
    "type": "WEB APP"
  },
  "roles": ["APP"]
}
```

Each service instance will have to ping the registry to provide some kind of heartbeat. You can configure the reaping interval in
`services.heartbeat.reaper.every`. The value is in milliseconds.