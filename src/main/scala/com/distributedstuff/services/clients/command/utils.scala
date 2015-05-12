package com.distributedstuff.services.clients.command

import java.util
import java.util.concurrent.{ConcurrentHashMap, Executors, TimeUnit}

import com.google.common.cache.CacheBuilder

import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}

trait CommandCache {
  def get[T](key: String): Option[T]
  def put(key: String, value: AnyRef)
  def cleanUp()
}

object InMemoryCommandCache {
  def of(d: Duration): InMemoryCommandCache = new InMemoryCommandCache(d)
}

class InMemoryCommandCache(retained: Duration) extends CommandCache {

  private val cache = CacheBuilder.newBuilder.expireAfterWrite(retained.toMillis, TimeUnit.MILLISECONDS).build[String, AnyRef]

  def get[T](key: String): Option[T] = Option(cache.getIfPresent(key)).map(_.asInstanceOf[T])

  def put(key: String, value: AnyRef) {
    cache.put(key, value)
  }

  def cleanUp() {
    cache.cleanUp()
  }
}

private class ConflaterExecutionContext[T](val command: Command[T], val promise: Promise[T], val future: Future[T], val ctx: CommandContext, val start: Long) {

  def collapseKey: Option[String] = command.conflateKey

  def execute {
    ctx.executeRequest(command, promise, future, start)
  }
}

object CommandConflater {
  def of(d: Duration): CommandConflater = new CommandConflater(d).start()
}

class CommandConflater(every: Duration) {

  private val lock = new AnyRef
  private val ec = Executors.newSingleThreadScheduledExecutor
  private val queue = new ConcurrentHashMap[String, ConflaterExecutionContext[_]]

  private[command] def add[T](command: Command[T], promise: Promise[T], future: Future[T], ctx: CommandContext, start: Long): Future[T] = {
    lock synchronized {
      val keyOpt = command.conflateKey
      if (keyOpt.isEmpty) return Future.failed(new RuntimeException(s"Conflate key not defined for ${command.name}"))
      val key = keyOpt.get
      if (!queue.containsKey(key)) {
        val e = queue.putIfAbsent(key, new ConflaterExecutionContext[T](command, promise, future, ctx, start)).asInstanceOf[ConflaterExecutionContext[T]]
        if (e != null) e.future
        else future
      } else {
        queue.get(key).asInstanceOf[ConflaterExecutionContext[T]].future
      }
    }
  }

  private def executeWaitingRequests {
    import scala.collection.JavaConversions._
    lock synchronized {
      if (queue.isEmpty) return
      val remove = new util.ArrayList[String]()
      for (entry <- queue.entrySet) {
        remove.add(entry.getKey)
        entry.getValue.execute
      }
      for (s <- remove) {
        queue.remove(s)
      }
    }
  }

  private def schedule(v: Long, u: TimeUnit) {
    if (!ec.isShutdown) {
      ec.schedule(new Runnable {
        def run {
          try {
            executeWaitingRequests
          } catch {
            case t: Throwable => t.printStackTrace()
          }
          try {
            if (!ec.isShutdown) {
              schedule(every.toMillis, TimeUnit.MILLISECONDS)
            }
          } catch {
            case t: Throwable =>  t.printStackTrace()
          }
        }
      }, v, u)
    }
  }

  private def start(): CommandConflater = {
    schedule(0, TimeUnit.MILLISECONDS)
    this
  }

  def stop() {
    ec.shutdown()
  }
}