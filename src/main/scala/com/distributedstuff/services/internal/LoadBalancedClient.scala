package com.distributedstuff.services.internal

import java.util.concurrent.atomic.AtomicLong

import com.distributedstuff.services.api.{Service, Client}

import scala.concurrent.{ExecutionContext, Future}

private[services] class LoadBalancedClient(name: String, is: ServiceDirectory) extends Client {

  private[this] val counter = new AtomicLong(0L)

  def bestService: Option[Service] = {
    val services = is.services(name)
    if (services.isEmpty) None
    else {
      val size = services.size
      val idx = (counter.getAndIncrement % (if (size > 0) size else 1)).toInt
      Some(services.toList(idx))
    }
  }

  override def call[T](f: (Service) => T)(implicit ec: ExecutionContext): Future[T] = {
    bestService.map(s => Future.successful(f(s))).getOrElse(Future.failed(new NoSuchElementException))
  }

  override def callM[T](f: (Service) => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    bestService.map(s => f(s)).getOrElse(Future.failed(new NoSuchElementException))
  }
}
