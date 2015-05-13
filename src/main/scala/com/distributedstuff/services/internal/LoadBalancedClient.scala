package com.distributedstuff.services.internal

import java.util.concurrent.atomic.AtomicLong

import com.distributedstuff.services.api.{Client, Service}
import com.distributedstuff.services.clients.command.Command
import com.distributedstuff.services.common.Backoff

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Try, Failure, Success}

private[services] class LoadBalancedClient(name: String, roles: Seq[String] = Seq(), version: Option[String] = None, times: Int, is: ServiceDirectory) extends Client {

  private[this] val counter = new AtomicLong(0L)
  private[this] val useCommands = is.configuration.getBoolean("services.commands").getOrElse(false)

  def bestService: Option[Service] = {
    val services = is.services(name, roles, version)
    if (services.isEmpty) None
    else {
      val size = services.size
      val idx = (counter.getAndIncrement % (if (size > 0) size else 1)).toInt
      Some(services.toList(idx))
    }
  }

  override def call[T](f: (Service) => T)(implicit ec: ExecutionContext): Future[T] = {
    implicit val sched = is.system.scheduler
    //if (useCommands) {
    //
    //} else {
      Backoff.retry(times) {
        is.metrics.meter(s"${is.name}.client.${name}.mark").mark()
        val ctx = is.metrics.timer(s"${is.name}.client.${name}.timer").time()
        // TODO : replace `f` call with a command based on service query
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
    //}
  }

  override def callM[T](f: (Service) => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    implicit val sched = is.system.scheduler
    //if (useCommands) {
    //
    //} else {
      Backoff.retry(times) {
        is.metrics.meter(s"${is.name}.client.${name}.mark").mark()
        val ctx = is.metrics.timer(s"${is.name}.client.${name}.timer").time()
        // TODO : replace `f` call with a command based on service query
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
    //}
  }
}

private class LoadBalancedClientCommand[T](backoff: Boolean, r: Int, f: (Service) => T) extends Command[T] {

  override def runAsync(implicit ec: ExecutionContext): Future[T] = {
    Future.failed(new RuntimeException)
  }

  override def retry: Int = r

  override def exponentialBackoff: Boolean = backoff
}
