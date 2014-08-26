package com.distributedstuff.services.api

case class Service(
                    uid: String,
                    name: String,
                    url: String,
                    metadata: Map[String, String] = Map[String, String](),
                    roles: Seq[String] = Seq(),
                    version: Option[String] = None) {
  override def equals(p1: scala.Any): Boolean = {
    p1 match {
      case s: Service => s.uid != null && s.uid == this.uid
      case _ => false
    }
  }
  override def hashCode(): Int = uid.hashCode
}
