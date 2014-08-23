package com.distributedstuff.services.internal

import java.util.concurrent.atomic.AtomicLong

import com.distributedstuff.services.api.{Service, Client}

import scala.concurrent.{ExecutionContext, Future}

private[services] class LoadBalancedClient(name: String, is: ServiceDirectory) extends Client {

  private[this] val counter = new AtomicLong(0L)

  override def call[T](f: (Service) => T)(implicit ec: ExecutionContext): Future[T] = {
    val services = is.services(name)
    if (services.isEmpty) {
      Future.failed(new NoSuchElementException)
    } else {
      val size = services.size
      val idx = (counter.getAndIncrement % (if (size > 0) size else 1)).toInt
      Future.successful(f(services.toList(idx)))
    }
  }

  override def callM[T](f: (Service) => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    val services = is.services(name)
    if (services.isEmpty) {
      Future.failed(new NoSuchElementException)
    } else {
      val size = services.size
      val idx = (counter.incrementAndGet() % (if (size > 0) size else 1)).toInt
      f(services.toList(idx))
    }
  }
}
