package com.distributedstuff.services.api

case class Service(uid: String, name: String, url: String, meta: Map[String, String] = Map[String, String](), contract: String = "", roles: Seq[String] = Seq())
