import java.util.concurrent.{TimeoutException, TimeUnit, Executors}
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import com.distributedstuff.services.clients.command.{Command, CommandConflater, CommandContext, InMemoryCommandCache}
import com.distributedstuff.services.common.Futures
import com.typesafe.config.ConfigFactory
import org.junit.Assert
import org.specs2.mutable.{Specification, Tags}

import scala.concurrent.duration.{FiniteDuration, Duration}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

class CommandSpec extends Specification with Tags {
  sequential

  val system = ActorSystem("TestSystem", ConfigFactory.empty())

  "Command API" should {
    val await = Duration("2min")

    "simpleTest" in {
      val context = CommandContext.of(5)
      val result = Await.result(context.execute(new PassingCommand), await)
      result shouldEqual "Hello"
      val result1 = context.get(new PassingCommand)
      result1 shouldEqual "Hello"
      success
    }

    "testCache" in {
      val context: CommandContext = CommandContext.of(5).withCache(InMemoryCommandCache.of(Duration("10min")))
      val result1: String = Await.result(context.execute(new PassingCacheCommand), await)
      val result2: String = Await.result(context.execute(new TimedCommand(Duration("10sec"))), Duration("2sec"))
      Assert.assertEquals("Hello Cache", result1)
      Assert.assertEquals("Hello Cache", result2)
      success
    }

    "testConflater" in  {
      val context: CommandContext = CommandContext.of(5).withConflater(CommandConflater.of(Duration("10millis")))
      val counter: AtomicInteger = new AtomicInteger(0)
      val result1: Future[String] = context.execute(new PassingCounterCommand(counter))
      val result2: Future[String] = context.execute(new PassingCounterCommand(counter))
      Assert.assertEquals("Hello", Await.result(result1, await))
      Assert.assertEquals("Hello", Await.result(result2, await))
      Assert.assertEquals(1, counter.get)
      context.shutdown()
      success
    }

    "testConflaterWithLotOfCalls" in  {
      val context: CommandContext = CommandContext.of(12).withConflater(CommandConflater.of(Duration("10millis")))
      val counter: AtomicInteger = new AtomicInteger(0)
      val result1: Future[String] = context.execute(new PassingCounterCommand(counter))
      val result2: Future[String] = context.execute(new PassingCounterCommand(counter))
      val result3: Future[String] = context.execute(new PassingCounterCommand(counter))
      val result4: Future[String] = context.execute(new PassingCounterCommand(counter))
      val result5: Future[String] = context.execute(new PassingCounterCommand(counter))
      val result6: Future[String] = context.execute(new PassingCounterCommand(counter))
      val result7: Future[String] = context.execute(new PassingCounterCommand(counter))
      Thread.sleep(100)
      val result8: Future[String] = context.execute(new PassingCounterCommand(counter))
      val result9: Future[String] = context.execute(new PassingCounterCommand(counter))
      val result10: Future[String] = context.execute(new PassingCounterCommand(counter))
      Assert.assertEquals("Hello", Await.result(result1, await))
      Assert.assertEquals("Hello", Await.result(result2, await))
      Assert.assertEquals("Hello", Await.result(result3, await))
      Assert.assertEquals("Hello", Await.result(result4, await))
      Assert.assertEquals("Hello", Await.result(result5, await))
      Assert.assertEquals("Hello", Await.result(result6, await))
      Assert.assertEquals("Hello", Await.result(result7, await))
      Assert.assertEquals("Hello", Await.result(result8, await))
      Assert.assertEquals("Hello", Await.result(result9, await))
      Assert.assertEquals("Hello", Await.result(result10, await))
      Assert.assertTrue(1 < counter.get)
      Assert.assertTrue(10 > counter.get)
      Assert.assertTrue(4 > counter.get)
      context.shutdown()
      success
    }

    "testConflaterWithLotOfCallsAndCache" in  {
      val context: CommandContext = CommandContext.of(12).withConflater(CommandConflater.of(Duration("10millis"))).withCache(InMemoryCommandCache.of(Duration("10min")))
      val counter: AtomicInteger = new AtomicInteger(0)
      val result1: Future[String] = context.execute(new PassingCounterCommand(counter))
      val result2: Future[String] = context.execute(new PassingCounterCommand(counter))
      val result3: Future[String] = context.execute(new PassingCounterCommand(counter))
      val result4: Future[String] = context.execute(new PassingCounterCommand(counter))
      val result5: Future[String] = context.execute(new PassingCounterCommand(counter))
      val result6: Future[String] = context.execute(new PassingCounterCommand(counter))
      val result7: Future[String] = context.execute(new PassingCounterCommand(counter))
      val result8: Future[String] = context.execute(new PassingCounterCommand(counter))
      val result9: Future[String] = context.execute(new PassingCounterCommand(counter))
      val result10: Future[String] = context.execute(new PassingCounterCommand(counter))
      Thread.sleep(100)
      val result11: Future[String] = context.execute(new PassingCounterCommand(counter))
      val result12: Future[String] = context.execute(new PassingCounterCommand(counter))
      val result13: Future[String] = context.execute(new PassingCounterCommand(counter))
      val result14: Future[String] = context.execute(new PassingCounterCommand(counter))
      Assert.assertEquals("Hello", Await.result(result1, await))
      Assert.assertEquals("Hello", Await.result(result2, await))
      Assert.assertEquals("Hello", Await.result(result3, await))
      Assert.assertEquals("Hello", Await.result(result4, await))
      Assert.assertEquals("Hello", Await.result(result5, await))
      Assert.assertEquals("Hello", Await.result(result6, await))
      Assert.assertEquals("Hello", Await.result(result7, await))
      Assert.assertEquals("Hello", Await.result(result8, await))
      Assert.assertEquals("Hello", Await.result(result9, await))
      Assert.assertEquals("Hello", Await.result(result10, await))
      Assert.assertEquals("Hello", Await.result(result11, await))
      Assert.assertEquals("Hello", Await.result(result12, await))
      Assert.assertEquals("Hello", Await.result(result13, await))
      Assert.assertEquals("Hello", Await.result(result14, await))
      Assert.assertEquals(1, counter.get)
      context.shutdown()
      success
    }

    "testTimeout" in  {
      val context: CommandContext = CommandContext.of(5)
      val result: String = Await.result(context.execute(new TimedCommand(Duration("4sec"), Duration("3sec"))), await)
      Assert.assertEquals("Goodbye", result)
      success
    }

    "testTooMuchThread" in {
      val context: CommandContext = CommandContext.of(5)
      val result1: Future[String] = context.execute(new TimedCommand(Duration("4sec")))
      val result2: Future[String] = context.execute(new TimedCommand(Duration("4sec")))
      val result3: Future[String] = context.execute(new TimedCommand(Duration("4sec")))
      val result4: Future[String] = context.execute(new TimedCommand(Duration("4sec")))
      val result5: Future[String] = context.execute(new TimedCommand(Duration("4sec")))
      val result6: Future[String] = context.execute(new TimedCommand(Duration("4sec")))
      val result7: Future[String] = context.execute(new TimedCommand(Duration("4sec")))
      Assert.assertEquals("Goodbye", Await.result(result6, Duration("1sec")))
      Assert.assertEquals("Goodbye", Await.result(result7, Duration("1sec")))
      Assert.assertEquals("Hello", Await.result(result1, await))
      Assert.assertEquals("Hello", Await.result(result2, await))
      Assert.assertEquals("Hello", Await.result(result3, await))
      Assert.assertEquals("Hello", Await.result(result4, await))
      Assert.assertEquals("Hello", Await.result(result5, await))
      success
    }

    "testTooMuchFailures" in  {
      val context: CommandContext = CommandContext.of(5)
      val result1: String = context.get(new PassingFailingTimedCommand(true))
      val result2: String = context.get(new PassingFailingTimedCommand(false))
      val result3: String = context.get(new PassingFailingTimedCommand(false))
      val result4: String = context.get(new PassingFailingTimedCommand(false))
      val result5: String = context.get(new PassingFailingTimedCommand(false))
      val result6: String = context.get(new PassingFailingTimedCommand(true))
      val result7: String = context.get(new PassingFailingTimedCommand(true, Some(Duration("2sec"))))
      Assert.assertEquals("Hello", result1)
      Assert.assertEquals("Goodbye", result2)
      Assert.assertEquals("Goodbye", result3)
      Assert.assertEquals("Goodbye", result4)
      Assert.assertEquals("Goodbye", result5)
      Assert.assertEquals("Hello", result6)
      Assert.assertEquals("Hello", result7)
      success
    }


    "testNoFallback" in  {
      val context: CommandContext = CommandContext.of(5)
      var exception: Boolean = false
      try {
        context.get(new FailingCommandWithNoFallback)
      }
      catch {
        case e: Exception => {
          if (e.isInstanceOf[WeirdException]) {
            exception = true
          }
        }
      }
      Assert.assertTrue(exception)
      success
    }

    "testTimeoutNoFallback" in  {
      val context: CommandContext = CommandContext.of(5)
      try {
        context.get(new FailingTimedCommandWithNoFallback(Duration("5sec"), Duration("1sec")))
      } catch {
        case _: TimeoutException => Assert.assertTrue(true)
        case _: Throwable => Assert.assertTrue(false)
      }
      success
    }

    "testFailingFallback" in  {
      val context: CommandContext = CommandContext.of(5)
      var exception: Boolean = false
      try {
        context.get(new FailingCommandWithFailingFallback)
      }
      catch {
        case e: Exception => {
          if (e.isInstanceOf[FallbackException]) {
            exception = true
          }
        }
      }
      Assert.assertTrue(exception)
      success
    }

    "testRetryCommand" in  {
      val context: CommandContext = CommandContext.of(5)
      val counter: AtomicInteger = new AtomicInteger(0)
      var exception: Boolean = false
      try {
        context.get(new FailingCommandWithRetry(counter))
      }
      catch {
        case e: Exception => {
          exception = true
        }
      }
      Assert.assertEquals(10, counter.get)
      Assert.assertTrue(exception)
      success
    }

    "testRetryCommandExpo" in {
      val context: CommandContext = CommandContext.of(5)
      val counter: AtomicInteger = new AtomicInteger(0)
      var exception: Boolean = false
      try {
        context.get(new FailingCommandWithRetryNoExpo(counter))
      }
      catch {
        case e: Exception => {
          exception = true
        }
      }
      Assert.assertEquals(100, counter.get)
      Assert.assertTrue(exception)
      success
    }
    

    "shutdow" in {

      success
    }

    class WeirdException extends RuntimeException
    class FallbackException extends RuntimeException

    class FailingCommandWithNoFallback extends Command[String] {
      def runAsync(implicit ec: ExecutionContext): Future[String] = Future.failed(new WeirdException)
    }

    class FailingCommandWithRetry(counter: AtomicInteger) extends Command[String] {

      def runAsync(implicit ec: ExecutionContext): Future[String] = {
        counter.incrementAndGet
        Future.failed(new WeirdException)
      }
      override def retry: Int = 10
    }

    class FailingCommandWithRetryNoExpo(counter: AtomicInteger) extends Command[String] {

      def runAsync(implicit ec: ExecutionContext): Future[String] = {
        counter.incrementAndGet
        Future.failed(new WeirdException)
      }

      override def retry: Int = 100
      override def exponentialBackoff: Boolean = false
    }

    class FailingCommandWithFailingFallback extends Command[String] {
      def runAsync(implicit ec: ExecutionContext): Future[String] = Future.failed(new WeirdException)
      override def fallback: Option[String] = throw new FallbackException
    }

    class FailingTimedCommandWithNoFallback(duration: Duration, t: Duration = Duration("60sec")) extends Command[String] {
      def runAsync(implicit ec: ExecutionContext): Future[String] = Futures.timeout("Hello", duration, system.scheduler)(ec)
      override def timeout: Duration = t
    }

    class PassingFailingTimedCommand(passing: Boolean, duration: Option[Duration] = None) extends Command[String] {

      def runAsync(implicit ec: ExecutionContext): Future[String] = {
        if (duration.isDefined) {
          val promise = Promise[String]()
          system.scheduler.scheduleOnce(FiniteDuration(duration.get.toMillis, TimeUnit.MILLISECONDS)) {
            if (passing) {
              promise.trySuccess("Hello")
            } else {
              promise.tryFailure(new RuntimeException("I failed1"))
            }
          }(ec)
          promise.future
        } else {
          if (passing) {
            Future.successful("Hello")
          } else {
            Future.failed(new RuntimeException("I failed2"))
          }
        }
      }
      override def timeout: Duration = Duration("4second")
      override def fallback: Option[String] = Some("Goodbye")
    }

    class PassingCommand extends Command[String] {
      def runAsync(implicit ec: ExecutionContext): Future[String] = Future.successful("Hello")
      override def fallback: Option[String] = Some("Goodbye")
    }

    class PassingCounterCommand(counter: AtomicInteger) extends Command[String] {
      def count: Integer = counter.get
      def runAsync(implicit ec: ExecutionContext): Future[String] = {
        counter.incrementAndGet
        Future.successful("Hello")
      }
      override def fallback: Option[String] = Some("Goodbye")
      override def cacheKey: Option[String] = Some("key")
    }

    class PassingCacheCommand extends Command[String] {
      def runAsync(implicit ec: ExecutionContext): Future[String] = Future.successful("Hello Cache")
      override def fallback: Option[String] = Some("Goodbye")
      override def cacheKey: Option[String] = Some("key")
    }

    class TimedCommand(duration: Duration, t: Duration = Duration("60sec")) extends Command[String] {
      def runAsync(implicit ec: ExecutionContext): Future[String] = Futures.timeout("Hello", duration, system.scheduler)(ec)
      override def fallback: Option[String] = Some("Goodbye")
      override def timeout: Duration = t
      override def cacheKey: Option[String] = Some("key")
    }

    class FailingCommand extends Command[String] {
      def runAsync(implicit ec: ExecutionContext): Future[String] = Future.failed(new RuntimeException("I failed"))
      override def fallback: Option[String] = Some("Goodbye")
    }
  }
}

