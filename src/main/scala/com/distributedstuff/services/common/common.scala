package com.distributedstuff.services.common

import java.io.File
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.{AbstractExecutorService, TimeUnit}

import akka.actor.Scheduler
import com.typesafe.config._
import org.slf4j.LoggerFactory

import scala.concurrent.{Future, ExecutionContextExecutorService, Promise, ExecutionContext}
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Random, Try, Failure, Success}
import scala.util.control.NonFatal

object Network {
  def freePort: Int = {
    Try {
      val serverSocket = new ServerSocket(0)
      val port = serverSocket.getLocalPort
      serverSocket.close()
      port
    }.toOption.getOrElse(Random.nextInt(1000) + 7000)
  }
}

trait LoggerLike {

  val logger: String //org.slf4j.Logger

  def isTraceEnabled = false //logger.isTraceEnabled
  def isDebugEnabled = true //logger.isDebugEnabled
  def isInfoEnabled =  true //logger.isInfoEnabled
  def isWarnEnabled =  true //logger.isWarnEnabled
  def isErrorEnabled = true //logger.isErrorEnabled

  def trace(message: => String) {
    if (isTraceEnabled) {
      println(s"[$logger][TRACE] $message")
    }
  }

  def trace(message: => String, error: => Throwable) {
    if (isTraceEnabled) {
      println(s"[$logger][TRACE] $message")
      error.printStackTrace()
    }
  }

  def debug(message: => String) {
    if (isDebugEnabled) {
      println(s"[$logger][DEBUG] $message")
    }
  }

  def debug(message: => String, error: => Throwable) {
    if (isDebugEnabled) {
      println(s"[$logger][DEBUG] $message")
      error.printStackTrace()
    }
  }

  def info(message: => String) {
    if (isInfoEnabled) {
      println(s"[$logger][INFO] $message")
    }
  }

  def info(message: => String, error: => Throwable) {
    if (isInfoEnabled) {
      println(s"[$logger][INFO] $message")
      error.printStackTrace()
    }
  }

  def warn(message: => String) {
    if (isWarnEnabled) {
      println(s"[$logger][WARN] $message")
    }
  }

  def warn(message: => String, error: => Throwable) {
    if (isWarnEnabled) {
      println(s"[$logger][WARN] $message")
      error.printStackTrace()
    }
  }

  def error(message: => String) {
    if (isErrorEnabled) {
      println(s"[$logger][ERROR] $message")
    }
  }

  def error(message: => String, error: => Throwable) {
    if (isErrorEnabled) {
      println(s"[$logger][ERROR] $message")
      error.printStackTrace()
    }
  }

  def apply(name: String): LoggerLike
  def apply[T](clazz: Class[T]): LoggerLike
}

class Logger(val logger: String/*org.slf4j.Logger*/) extends LoggerLike  {
  def apply(name: String): LoggerLike = new Logger(name)//LoggerFactory.getLogger(name))
  def apply[T](clazz: Class[T]): LoggerLike = new Logger(clazz.getName)//LoggerFactory.getLogger(clazz))
}

object Logger extends LoggerLike {
  val logger = "Distributed-Map" //lazy val logger = LoggerFactory.getLogger("Distributed-Map")
  def apply(name: String): LoggerLike = new Logger(name)//LoggerFactory.getLogger(name))
  def apply[T](clazz: Class[T]): LoggerLike = new Logger(clazz.getName)//LoggerFactory.getLogger(clazz))

  def configure(): LoggerLike = {
    //{
    //  import java.util.logging._
    //  Option(java.util.logging.Logger.getLogger("")).map { root =>
    //    root.setLevel(java.util.logging.Level.FINEST)
    //    root.getHandlers.foreach(root.removeHandler(_))
    //  }
    //}
    //{
    //  import org.slf4j._
    //  import ch.qos.logback.classic.joran._
    //  import ch.qos.logback.core.util._
    //  import ch.qos.logback.classic._
    //  try {
    //    val ctx = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    //    val configurator = new JoranConfigurator
    //    configurator.setContext(ctx)
    //    ctx.reset()
    //    try {
    //      val configResource =
    //        Option(System.getProperty("logger.resource"))
    //          .map(s => if (s.startsWith("/")) s.drop(1) else s)
    //          .map(r => Option(this.getClass.getClassLoader.getResource(r))
    //          .getOrElse(new java.net.URL("file:///" + System.getProperty("logger.resource")))
    //          ).orElse {
    //          Option(System.getProperty("logger.file")).map(new java.io.File(_).toURI.toURL)
    //        }.orElse {
    //          Option(System.getProperty("logger.url")).map(new java.net.URL(_))
    //        }.orElse {
    //          Option(this.getClass.getClassLoader.getResource("application-logger.xml"))
    //            .orElse(Option(this.getClass.getClassLoader.getResource("logger.xml")))
    //        }
    //      configResource.foreach { url => configurator.doConfigure(url) }
    //    } catch {
    //      case NonFatal(e) => e.printStackTrace()
    //    }
    //    StatusPrinter.printIfErrorsOccured(ctx)
    //  } catch {
    //    case NonFatal(_) =>
    //  }
    //  this
    //}
    this
  }
}

object Configuration {
  def empty() = new Configuration(ConfigFactory.empty())
  def parse(v: String) = new Configuration(ConfigFactory.parseString(v))
  def parse(file: File) = new Configuration(ConfigFactory.parseFile(file))
  def load() = new Configuration(ConfigFactory.load())
}

class Configuration(val underlying: Config) {

  private def readValue[T](path: String, v: => T): Option[T] = {
    try {
      Option(v)
    } catch {
      case e: ConfigException.Missing => None
      case NonFatal(e) => throw reportError(path, e.getMessage, Some(e))
    }
  }

  def getString(path: String, validValues: Option[Set[String]] = None): Option[String] = readValue(path, underlying.getString(path)).map { value =>
    validValues match {
      case Some(values) if values.contains(value) => value
      case Some(values) if values.isEmpty => value
      case Some(values) => throw reportError(path, "Incorrect value, one of " + (values.reduceLeft(_ + ", " + _)) + " was expected.")
      case None => value
    }
  }

  def withValue(key: String, value: AnyRef) = new Configuration(underlying.withValue(key, ConfigValueFactory.fromAnyRef(value)))

  def getInt(path: String): Option[Int] = readValue(path, underlying.getInt(path))

  def getBoolean(path: String): Option[Boolean] = readValue(path, underlying.getBoolean(path))

  def getDouble(path: String): Option[Double] = readValue(path, underlying.getDouble(path))

  def getLong(path: String): Option[Long] = readValue(path, underlying.getLong(path))

  def getDoubleList(path: String): Option[java.util.List[java.lang.Double]] = readValue(path, underlying.getDoubleList(path))

  def getIntList(path: String): Option[java.util.List[java.lang.Integer]] = readValue(path, underlying.getIntList(path))

  def getList(path: String): Option[ConfigList] = readValue(path, underlying.getList(path))

  def getLongList(path: String): Option[java.util.List[java.lang.Long]] = readValue(path, underlying.getLongList(path))

  def getObjectList(path: String): Option[java.util.List[_ <: ConfigObject]] = readValue[java.util.List[_ <: ConfigObject]](path, underlying.getObjectList(path))

  def getStringList(path: String): Option[java.util.List[java.lang.String]] = readValue(path, underlying.getStringList(path))

  def getObject(path: String): Option[ConfigObject] = readValue(path, underlying.getObject(path))

  def reportError(path: String, message: String, e: Option[Throwable] = None): RuntimeException = {
    new RuntimeException(message, e.getOrElse(new RuntimeException))
  }
}

object Backoff {

  private[this] def retryPromise[T](times: Int, wait: Int, promise: Promise[T], failure: Option[Throwable], f: => Future[T], ec: ExecutionContext, scheduler: Scheduler): Unit = {
    (times, failure) match {
      case (0, Some(e)) => promise.tryFailure(e)
      case (0, None) => promise.tryFailure(new RuntimeException("Failure, but lost track of exception :-("))
      case (i, _) => f.onComplete {
        case Success(t) => promise.trySuccess(t)
        case Failure(e) => {
          val newWait = if (wait == 0) 1 else wait + wait
          Futures.timeout(retryPromise[T](times - 1, newWait, promise, Some(e), f, ec, scheduler),
            Duration(newWait, TimeUnit.MILLISECONDS), scheduler)(ec)
        }
      }(ec)
    }
  }

  def retry[T](times: Int)(f: => Future[T])(implicit ec: ExecutionContext, scheduler: Scheduler): Future[T] = {
    val promise = Promise[T]()
    retryPromise[T](times, 0, promise, None, f, ec, scheduler)
    promise.future
  }
}

object Futures {

  private[this] def retryPromise[T](times: Int, promise: Promise[T], failure: Option[Throwable], f: => Future[T], ec: ExecutionContext): Unit = {
    (times, failure) match {
      case (0, Some(e)) => promise.tryFailure(e)
      case (0, None) => promise.tryFailure(new RuntimeException("Failure, but lost track of exception :-("))
      case (i, _) => f.onComplete {
        case Success(t) => promise.trySuccess(t)
        case Failure(e) => retryPromise[T](times - 1, promise, Some(e), f, ec)
      }(ec)
    }
  }

  def retry[T](times: Int)(f: => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    val promise = Promise[T]()
    retryPromise[T](times, promise, None, f, ec)
    promise.future
  }

  private[this] def retryPromiseWithPredicate[T](predicate: T => Boolean, times: Int, promise: Promise[T], failure: Option[Throwable], f: => Future[T], ec: ExecutionContext): Unit = {
    (times, failure) match {
      case (0, Some(e)) => promise.tryFailure(e)
      case (0, None) => promise.tryFailure(new RuntimeException("Predicate did not match"))
      case (i, _) => f.onComplete {
        case Success(t) if predicate(t) => promise.trySuccess(t)
        case Success(t) if !predicate(t) => retryPromiseWithPredicate[T](predicate, times - 1, promise, None, f, ec)
        case Failure(e) => retryPromiseWithPredicate[T](predicate, times - 1, promise, Some(e), f, ec)
      }(ec)
    }
  }

  def retryWithPredicate[T](times: Int, predicate: T => Boolean)(f: => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    val promise = Promise[T]()
    retryPromiseWithPredicate[T](predicate, times, promise, None, f, ec)
    promise.future
  }

  def timeout[A](message: => A, duration: scala.concurrent.duration.Duration, scheduler: Scheduler)(implicit ec: ExecutionContext): Future[A] = {
    timeout(message, duration.toMillis, TimeUnit.MILLISECONDS, scheduler)
  }

  def timeout[A](message: => A, duration: Long, unit: TimeUnit = TimeUnit.MILLISECONDS, scheduler: Scheduler)(implicit ec: ExecutionContext): Future[A] = {
    val p = Promise[A]()
    scheduler.scheduleOnce(FiniteDuration(duration, unit)) {
      p.success(message)
    }
    p.future
  }
}

object ExecutionContextExecutorServiceBridge {
  def apply(ec: ExecutionContext): ExecutionContextExecutorService = ec match {
    case null => throw new Throwable("ExecutionContext to ExecutorService conversion failed !!!")
    case eces: ExecutionContextExecutorService => eces
    case other => new AbstractExecutorService with ExecutionContextExecutorService {
      override def prepare(): ExecutionContext = other
      override def isShutdown = false
      override def isTerminated = false
      override def shutdown() = ()
      override def shutdownNow() = Collections.emptyList[Runnable]
      override def execute(runnable: Runnable): Unit = other execute runnable
      override def reportFailure(t: Throwable): Unit = other reportFailure t
      override def awaitTermination(length: Long, unit: TimeUnit): Boolean = false
    }
  }
}