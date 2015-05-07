package com.distributedstuff.services.internal

import java.util.concurrent.atomic.AtomicLong

import com.distributedstuff.services.api.{Client, Service}
import com.distributedstuff.services.common.Backoff

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

private[services] class LoadBalancedClient(name: String, times: Int, is: ServiceDirectory) extends Client {

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
    implicit val sched = is.system.scheduler
    Backoff.retry(times) {
      is.metrics.meter(s"${is.name}.client.${name}.mark").mark()
      val ctx = is.metrics.timer(s"${is.name}.client.${name}.timer").time()
      bestService.map(s => Future.successful(f(s))).getOrElse(Future.failed(new NoSuchElementException)).andThen {
        case Success(_) => {
          ctx.close()
          is.metrics.meter(s"${is.name}.client.${name}.success").mark()
        }
        case Failure(_) => {
          ctx.close()
          is.metrics.meter(s"${is.name}.client.${name}.failure").mark()
        }
      }
    }
  }

  override def callM[T](f: (Service) => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    implicit val sched = is.system.scheduler
    Backoff.retry(times) {
      is.metrics.meter(s"${is.name}.client.${name}.mark").mark()
      val ctx = is.metrics.timer(s"${is.name}.client.${name}.timer").time()
      bestService.map(s => f(s)).getOrElse(Future.failed(new NoSuchElementException)).andThen {
        case Success(_) => {
          ctx.close()
          is.metrics.meter(s"${is.name}.client.${name}.success").mark()
        }
        case Failure(_) => {
          ctx.close()
          is.metrics.meter(s"${is.name}.client.${name}.failure").mark()
        }
      }
    }
  }
}
