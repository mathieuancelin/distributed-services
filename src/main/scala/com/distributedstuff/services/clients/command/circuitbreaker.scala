package com.distributedstuff.services.clients.command

import java.lang.Math._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import java.util.concurrent.{ConcurrentSkipListMap, TimeUnit}

import play.api.libs.json.{JsObject, Json}

import scala.concurrent.duration.Duration

object CircuitBreaker {

  sealed trait Strategy

  object Strategies {
    case object UNIQUE_PER_CONTEXT extends Strategy
    case object UNIQUE_PER_COMMAND extends Strategy
  }

  private val circuitBreakerRequestVolumeThreshold = 1
  private val circuitBreakerErrorThresholdPercentage = 50.0
  private val circuitBreakerMetricsWindow = "10 sec"
}

class CircuitBreaker {
  val metrics = new CircuitBreakerHealth(Duration(CircuitBreaker.circuitBreakerMetricsWindow))
  val circuitOpen = new AtomicBoolean(false)

  def markSuccess(duration: Duration) {
    if (circuitOpen.get) {
      metrics.reset()
      circuitOpen.set(false)
    } else {
      metrics.incrementTotalRequests()
      metrics.incrementSuccesses()
      metrics.incrementTotalTime(duration)
    }
  }

  def markFailure(duration: Duration) {
    metrics.incrementTotalRequests()
    metrics.incrementErrors()
    metrics.incrementTotalTime(duration)
  }

  def isOpen: Boolean = {
    if (circuitOpen.get) true
    else if (metrics.getTotalRequests < CircuitBreaker.circuitBreakerRequestVolumeThreshold) false
    else if (metrics.getErrorPercentage < CircuitBreaker.circuitBreakerErrorThresholdPercentage) false
    else circuitOpen.compareAndSet(false, true)
  }

  def allowRequest: Boolean = !isOpen
}

object CircuitBreakerHealth {

  object Meter {
    private val TICK_INTERVAL: Long = TimeUnit.SECONDS.toNanos(5)
    def getTick(): Long = System.nanoTime
  }

  class Meter {
    private val m1window = new CircuitBreakerHealth.SlidingWindow[Long](Duration("1min"))
    private val m1Rate = EWMA.oneMinuteEWMA
    private val count = new AtomicLong(0)
    private val startTime = Meter.getTick()
    private val lastTick = new AtomicLong(startTime)

    def update(nanos: Long) {
      m1window.update(nanos)
    }

    def update(duration: Duration) {
      m1window.update(duration.toNanos)
    }

    def getSnapshot(): CircuitBreakerHealth.Snapshot = new CircuitBreakerHealth.Snapshot(m1window.getValues)

    def mark() {
      mark(1)
    }

    def mark(n: Long) {
      tickIfNecessary()
      count.addAndGet(n)
      m1Rate.update(n)
    }

    private def tickIfNecessary() {
      val oldTick = lastTick.get
      val newTick = Meter.getTick()
      val age = newTick - oldTick
      if (age > Meter.TICK_INTERVAL) {
        val newIntervalStartTick = newTick - age % Meter.TICK_INTERVAL
        if (lastTick.compareAndSet(oldTick, newIntervalStartTick)) {
          val requiredTicks = age / Meter.TICK_INTERVAL
          for (i <- 1L to requiredTicks) {
            m1Rate.tick()
          }
        }
      }
    }

    def getCount: Long = count.get

    def getMeanRate: Double = {
      if (getCount == 0) 0.0
      else {
        val elapsed = Meter.getTick - startTime
        getCount / elapsed * TimeUnit.SECONDS.toNanos(1)
      }
    }

    def getOneMinuteRate: Double = {
      tickIfNecessary()
      m1Rate.getRate(TimeUnit.SECONDS)
    }
  }

  /**
   * From metrics : https://github.com/dropwizard/metrics/blob/master/metrics-core/src/main/java/com/codahale/metrics/EWMA.java
   */
  object EWMA {
    private val INTERVAL = 5
    private val SECONDS_PER_MINUTE = 60.0
    private val ONE_MINUTE = 1
    private val FIVE_MINUTES = 5
    private val FIFTEEN_MINUTES = 15
    private val M1_ALPHA = 1 - exp(-INTERVAL / SECONDS_PER_MINUTE / ONE_MINUTE)
    private val M5_ALPHA = 1 - exp(-INTERVAL / SECONDS_PER_MINUTE / FIVE_MINUTES)
    private val M15_ALPHA = 1 - exp(-INTERVAL / SECONDS_PER_MINUTE / FIFTEEN_MINUTES)

    def oneMinuteEWMA: CircuitBreakerHealth.EWMA = new CircuitBreakerHealth.EWMA(M1_ALPHA, INTERVAL, TimeUnit.SECONDS)

    def fiveMinuteEWMA: CircuitBreakerHealth.EWMA = new CircuitBreakerHealth.EWMA(M5_ALPHA, INTERVAL, TimeUnit.SECONDS)

    def fifteenMinuteEWMA: CircuitBreakerHealth.EWMA = new CircuitBreakerHealth.EWMA(M15_ALPHA, INTERVAL, TimeUnit.SECONDS)
  }

  class EWMA(alpha: Double, i: Long, intervalUnit: TimeUnit) {
    @volatile
    private var initialized = false
    @volatile
    private var rate = 0.0
    private val uncounted = new AtomicLong(0)
    private val interval = intervalUnit.toNanos(i)

    def update(n: Long) {
      uncounted.addAndGet(n)
    }

    def tick() {
      val count = uncounted.getAndSet(0)
      val instantRate = count / interval
      if (initialized) {
        rate += (alpha * (instantRate - rate))
      } else {
        rate = instantRate
        initialized = true
      }
    }

    def getRate(rateUnit: TimeUnit): Double =  rate * rateUnit.toNanos(1).toDouble
  }

  object SlidingWindow {
    private val COLLISION_BUFFER = 256
    private val TRIM_THRESHOLD = 256
  }

  class SlidingWindow[T](w: Duration) {
    private val measurements = new ConcurrentSkipListMap[Long, T]()
    private val window = w.toNanos * SlidingWindow.COLLISION_BUFFER
    private val lastTick = new AtomicLong(Meter.getTick * SlidingWindow.COLLISION_BUFFER)
    private val count = new AtomicLong(0)

    def size: Int = {
      trim()
      measurements.size
    }

    def update(value: T) {
      if (count.incrementAndGet % SlidingWindow.TRIM_THRESHOLD == 0) trim()
      measurements.put(getTick, value)
    }

    def getValues: List[T] = {
      import collection.JavaConversions._
      trim()
      measurements.values.toList
    }

    private def getTick: Long = {
      while (true) {
        val oldTick = lastTick.get
        val tick = Meter.getTick * SlidingWindow.COLLISION_BUFFER
        val newTick = if (tick - oldTick > 0) tick else oldTick + 1
        if (lastTick.compareAndSet(oldTick, newTick)) {
          return newTick
        }
      }
      -1
    }

    private def trim() {
      measurements.headMap(getTick - window).clear()
    }
  }

  class Snapshot(v: List[Long]) {

    val values = v.sortBy[Long](v => v)

    def getValue(quantile: Double): Double = {
      if (quantile < 0.0 || quantile > 1.0) {
        throw new IllegalArgumentException(quantile + " is not in [0..1]")
      }
      if (values.length == 0) {
        return 0.0
      }
      val pos = quantile * (values.length + 1)
      if (pos < 1) {
        return values.head
      }
      if (pos >= values.length) {
        return values(values.length - 1)
      }
      val lower = values(pos.toInt - 1)
      val upper = values(pos.toInt)
      lower + (pos - floor(pos)) * (upper - lower)
    }

    def size: Int = values.length

    def getMax: Long = {
      if (values.length == 0) 0
      else values(values.length - 1)
    }

    def getMin: Long = {
      if (values.length == 0) 0
      else values.head
    }

    def getMean: Double = {
      if (values.length == 0) {
        return 0
      }
      var sum: Double = 0
      for (value <- values) {
        sum += value
      }
      sum / values.length
    }

    def getStdDev: Double = {
      if (values.length <= 1) {
        return 0
      }
      val mean: Double = getMean
      var sum: Double = 0.0
      for (value <- values) {
        val diff = value - mean
        sum += diff * diff
      }
      val variance = sum / (values.length - 1)
      Math.sqrt(variance)
    }

    def getMedian: Double = getValue(0.5)

    def get75thPercentile: Double = getValue(0.75)

    def get90thPercentile: Double = getValue(0.90)

    def get95thPercentile: Double = getValue(0.95)

    def get98thPercentile: Double = getValue(0.98)

    def get99thPercentile: Double = getValue(0.99)

    def get995thPercentile: Double = getValue(0.995)

    def get999thPercentile: Double = getValue(0.999)
  }

}

class CircuitBreakerHealth(windowDuration: Duration) {

  val totalTime = new AtomicLong(0)
  val totalRequests = new MeasuredRate(windowDuration.toMillis)
  val totalErrorRequests = new MeasuredRate(windowDuration.toMillis)
  val totalSuccessRequests = new MeasuredRate(windowDuration.toMillis)
  val request = new CircuitBreakerHealth.Meter()
  val success = new CircuitBreakerHealth.Meter()
  val shortcircuit = new CircuitBreakerHealth.Meter()
  val timeout = new CircuitBreakerHealth.Meter()
  val rejected = new CircuitBreakerHealth.Meter()
  val failure = new CircuitBreakerHealth.Meter()

  def incrementTotalRequests() {
    totalRequests.increment()
    request.mark()
  }

  def incrementErrors() {
    totalErrorRequests.increment()
  }

  def incrementTotalTime(duration: Duration) {
    totalTime.addAndGet(duration.toMillis)
    request.update(duration)
  }

  def incrementSuccesses() {
    totalSuccessRequests.increment()
    success.mark()
  }

  def getTotalRequests(): Long = totalRequests.getCount

  def getErrorPercentage(): Double = (totalErrorRequests.getCount * 100.0) / totalRequests.getCount

  def reset() {
    totalTime.set(0)
    totalRequests.reset()
    totalErrorRequests.reset()
    totalSuccessRequests.reset()
  }

  def stats(commandName: String, host: String, closed: Boolean): JsObject = {
    val snapshot = request.getSnapshot()
    Json.obj(
      "command" -> commandName,
      "host" -> host,
      "circuitclosed" -> closed,
      "successes" -> success.getCount,
      "shortcircuited" -> shortcircuit.getCount,
      "timeouts" -> timeout.getCount,
      "rejected" -> rejected.getCount,
      "failures" -> failure.getCount,
      "rate" -> request.getOneMinuteRate,
      "median" -> snapshot.getMedian,
      "mean" -> snapshot.getMean,
      "90th" -> snapshot.get90thPercentile,
      "99th" -> snapshot.get99thPercentile,
      "99_9th" -> snapshot.get995thPercentile
    )
  }
}

class MeasuredRate(si: Long) {
  private val lastBucket = new AtomicLong(0)
  private val currentBucket = new AtomicLong(0)
  private val sampleInterval = si

  @volatile
  private var threshold = System.currentTimeMillis + sampleInterval

  /**
   * Returns the count in the last sample interval
   */
  def getCount: Long = {
    checkAndResetWindow()
    lastBucket.get
  }

  /**
   * Returns the count in the current sample interval which will be incomplete.
   */
  def getCurrentCount: Long = {
    checkAndResetWindow()
    currentBucket.get
  }

  def increment() {
    checkAndResetWindow()
    currentBucket.incrementAndGet
  }

  def mark() {
    increment()
  }

  def increment(of: Long) {
    checkAndResetWindow()
    currentBucket.addAndGet(of)
  }

  def mark(of: Long) {
    increment(of)
  }

  private def checkAndResetWindow() {
    val now = System.currentTimeMillis()
    if (threshold < now) {
      lastBucket.set(currentBucket.get)
      currentBucket.set(0)
      threshold = now + sampleInterval
    }
  }

  def reset() {
    lastBucket.set(0)
    currentBucket.set(0)
  }

  override def toString: String = "count:" + getCount + "currentCount:" + getCurrentCount

  def toJson: JsObject = Json.obj("count" -> getCount, "currentCount" -> getCurrentCount)
}



