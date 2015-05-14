package com.distributedstuff.services.internal

import java.util.concurrent.atomic.AtomicLong

import com.distributedstuff.services.api.{Client, Service}
import com.distributedstuff.services.clients.command.{CommandContext, Command}
import com.distributedstuff.services.common.Backoff

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Try, Failure, Success}

private[services] class LoadBalancedClient(name: String, roles: Seq[String] = Seq(), version: Option[String] = None, times: Int, is: ServiceDirectory) extends Client {

  private[this] val counter = new AtomicLong(0L)
  private[this] val useCommands = is.configuration.getBoolean("services.commands").getOrElse(false)
  private[this] val commandContext = CommandContext.of(Int.MaxValue)

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
    if (useCommands) {
      commandContext.execute(LoadBalancedClientCommand.toAsync(name, roles, version, times, counter, is, f))
    } else {
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
  }

  override def callM[T](f: (Service) => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    implicit val sched = is.system.scheduler
    if (useCommands) {
      commandContext.execute(LoadBalancedClientCommand(name, roles, version, times, counter, is, f))
    } else {
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
}

private object LoadBalancedClientCommand {
  def toAsync[T](name: String, roles: Seq[String] = Seq(), version: Option[String] = None, times: Int, counter: AtomicLong, is: ServiceDirectory, f: (Service) => T) =
    new LoadBalancedClientCommand[T](name, roles, version, times, counter, is, (service: Service) => Future.fromTry(Try(f(service))))
  def apply[T](name: String, roles: Seq[String] = Seq(), version: Option[String] = None, times: Int, counter: AtomicLong, is: ServiceDirectory, f: (Service) => Future[T]) =
    new LoadBalancedClientCommand[T](name, roles, version, times, counter, is, f)
}

private class LoadBalancedClientCommand[T](name: String, roles: Seq[String] = Seq(), version: Option[String] = None, times: Int, counter: AtomicLong, is: ServiceDirectory, f: (Service) => Future[T]) extends Command[T] {

  def bestService: Option[Service] = {
    val services = is.services(name, roles, version)
    if (services.isEmpty) None
    else {
      val size = services.size
      val idx = (counter.getAndIncrement % (if (size > 0) size else 1)).toInt
      Some(services.toList(idx))
    }
  }

  override def runAsync(implicit ec: ExecutionContext): Future[T] = {
    is.metrics.meter(s"${is.name}.client.${name}.mark").mark()
    val ctx = is.metrics.timer(s"${is.name}.client.${name}.timer").time()
    bestService.map { s =>
      f(s)
    }.getOrElse(Future.failed(new NoSuchElementException)).andThen {
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
  override def retry: Int = times
  override def exponentialBackoff: Boolean = true
}
