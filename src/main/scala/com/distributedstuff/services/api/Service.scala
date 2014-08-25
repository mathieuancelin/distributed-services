package com.distributedstuff.services.api

case class Service(uid: String, name: String, url: String, metadata: Map[String, String] = Map[String, String](), roles: Seq[String] = Seq(), version: Option[String] = None)
