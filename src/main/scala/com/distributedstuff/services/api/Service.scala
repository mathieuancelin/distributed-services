package com.distributedstuff.services.api

import com.distributedstuff.services.common.IdGenerator
import com.google.common.base.Preconditions
import play.api.libs.json.{Format, Json}

/**
 * Description of a service
 *
 * @param uid id of a service instance. Should be unique across the cluster.
 * @param name name of the cluster. Can be used for multiple service instances.
 * @param url the location of the service. Protocol agnostic.
 * @param metadata metadata about the service instance. The library doesn't use those metadata. Can be empty
 * @param roles the possible roles for the service instance. Can be empty
 * @param version the version of the service instance. Can be empty
 */
case class Service(
                    uid: String = IdGenerator.uuid,
                    name: String,
                    url: String,
                    metadata: Map[String, String] = Map[String, String](),
                    roles: Seq[String] = Seq(),
                    version: Option[String] = None) {

  Preconditions.checkNotNull(uid)
  Preconditions.checkNotNull(name)
  Preconditions.checkNotNull(url)

  override def equals(p1: scala.Any): Boolean = {
    p1 match {
      case s: Service => s.uid != null && s.uid == this.uid
      case _ => false
    }
  }
  override def hashCode(): Int = uid.hashCode
}

object Service {
  implicit val format = Json.format[Service]
}