package com.distributedstuff.services.clients.command

import java.util.concurrent._
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import com.distributedstuff.services.clients.command.CircuitBreaker.Strategy
import com.distributedstuff.services.common.{Backoff, Futures}
import com.typesafe.config.{ConfigFactory, Config}
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

trait Command[T] {

  def runAsync(implicit ec: ExecutionContext): Future[T]

  def timeout: Duration = Duration("1second")

  def name: String = this.getClass.getName

  def fallback: Option[T] = None

  def cacheKey: Option[String] = None

  def conflateKey: Option[String] = cacheKey

  def retry: Int = 0

  def exponentialBackoff: Boolean = true
}

object CommandContext {
  def of(n: Int): CommandContext = new CommandContext(ActorSystem("AutoSystem", ConfigFactory.load().atKey("commands")), n, CircuitBreaker.Strategies.UNIQUE_PER_COMMAND, None, None)
}

class CommandContext(context: ActorSystem, allowedThreads: Int, strategy: CircuitBreaker.Strategy, cache: Option[CommandCache], collapser: Option[CommandConflater]) {

  implicit val ec = context.dispatcher

  private val DEFAULT_BREAKER = "__DEFAULT_BREAKER__"
  private val breakers = new ConcurrentHashMap[String, CircuitBreaker]
  private val counter = new AtomicInteger(0)

  def withAllowedThreads(n: Int): CommandContext = new CommandContext(this.context, n, this.strategy, this.cache, this.collapser)

  def withActorContext(context: ActorSystem): CommandContext = new CommandContext(context, this.allowedThreads, this.strategy, this.cache, this.collapser)

  def withCache(c: CommandCache): CommandContext = new CommandContext(this.context, this.allowedThreads, this.strategy, Option.apply(c), this.collapser)

  def withConflater(c: CommandConflater): CommandContext = new CommandContext(this.context, this.allowedThreads, this.strategy, this.cache, Option.apply(c))

  def withCircuitBreakerStrategy(c: Strategy): CommandContext = new CommandContext(this.context, this.allowedThreads, c, this.cache, this.collapser)

  def andReportToSystemOut(every: Duration): CommandContext = andReport(every, { input => println(Json.prettyPrint(Json.arr(input))) })

  def andReport(every: Duration, to: List[JsObject] => Unit): CommandContext = {
    def report: Unit = {
      var list = List.empty[JsObject]
      import scala.collection.JavaConversions._
      for (entry <- breakers.entrySet) {
        val name: String = entry.getKey
        val breaker = entry.getValue
        list = list :+ breaker.metrics.stats(name, "", breaker.allowRequest)
      }
      to(list)
      if (!context.isTerminated) context.scheduler.scheduleOnce(FiniteDuration(every.toMillis, TimeUnit.MILLISECONDS))(report)
    }
    context.scheduler.scheduleOnce(FiniteDuration(every.toMillis, TimeUnit.MILLISECONDS))(report)
    this
  }

  private def getBreaker(key: String): CircuitBreaker = {
    val theKey = if (strategy == CircuitBreaker.Strategies.UNIQUE_PER_CONTEXT) DEFAULT_BREAKER else key
    if (!breakers.containsKey(theKey)) {
      breakers.putIfAbsent(theKey, new CircuitBreaker)
    }
    breakers.get(theKey)
  }

  def execute[T](command: Command[T]): Future[T] = {
    val start = System.currentTimeMillis
    val promise = Promise[T]()
    val finalFuture = promise.future.andThen {
      case _ => counter.decrementAndGet()
    }
    val cacheKeyOpt = command.cacheKey
    if (cacheKeyOpt.isDefined && cache.isDefined) {
      val o = cache.get.get[Future[T]](cacheKeyOpt.get)
      if (o.isDefined) return o.get
    }
    val breaker = getBreaker(command.name)
    if (!breaker.allowRequest) {
      breaker.metrics.shortcircuit.mark()
      return Try(command.fallback.map(Future.successful).getOrElse(Future.failed(new CircuitOpenException("The circuit is open")))).recover { case e => Future.failed(e) }.get
    }
    if (allowedThreads <= counter.get) {
      breaker.metrics.rejected.mark()
      return Try(command.fallback.map(Future.successful).getOrElse(Future.failed(new TooManyConcurrentRequestsException(s"Max allowed request is $allowedThreads")))).recover { case e => Future.failed(e) }.get
    }
    if (cacheKeyOpt.isDefined && cache.isDefined) {
      cache.get.put(cacheKeyOpt.get, finalFuture)
    }
    if (collapser.isDefined) {
      val collapsed = collapser.get.add(command, promise, finalFuture, this, start)
      if (collapsed == null) {
        executeRequest(command, promise, finalFuture, start)
      } else {
        return collapsed
      }
    } else {
      executeRequest(command, promise, finalFuture, start)
    }
    finalFuture
  }

  private[command] def executeRequest[T](command: Command[T], promise: Promise[T], future: Future[T], start: Long) {
    val breaker = getBreaker(command.name)
    counter.incrementAndGet
    var retry = command.retry
    if (retry == 0) retry = 1
    val fu = if (command.exponentialBackoff) Backoff.retry(retry)(command.runAsync)(ec, context.scheduler) else Futures.retry(retry)(command.runAsync)
    fu.onComplete {
      case Failure(t) => {
        val duration = Duration(System.currentTimeMillis - start, TimeUnit.MILLISECONDS)
        breaker.metrics.failure.mark()
        breaker.markFailure(duration)
        Try(command.fallback.map(promise.trySuccess).getOrElse(promise.tryFailure(t))).recover { case e => promise.tryFailure(e) }
      }
      case Success(value) => {
        val duration = Duration(System.currentTimeMillis - start, TimeUnit.MILLISECONDS)
        breaker.markSuccess(duration)
        promise.trySuccess(value)
      }
    }
    Futures.timeout({
      breaker.metrics.timeout.mark()
      Try(command.fallback.map(promise.trySuccess).getOrElse(promise.tryFailure(new TimeoutException("Request timeout (" + command.timeout + ")")))).recover { case e => promise.tryFailure(e) }
    },command.timeout, context.scheduler)
  }

  def get[T](command: Command[T]): T = Await.result(execute(command), Duration.Inf)

  def getResult[T](command: Command[T]): T = Await.result(execute(command), Duration.Inf)

  def shutdown() {
    context.shutdown()
    collapser.foreach(_.stop())
    cache.foreach(_.cleanUp())
  }
}
