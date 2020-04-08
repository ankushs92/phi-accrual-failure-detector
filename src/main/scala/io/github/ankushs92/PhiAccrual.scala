package io.ankushs92.thesis.phi_accrual

import java.lang.Math._
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

import akka.actor.{ActorSystem, Props}
import com.typesafe.scalalogging.Logger
import io.ankushs92.thesis.phi_accrual.PhiAccrual.NO_HEARTBEAT_TIMESTAMP

import scala.collection.mutable


object PhiAccrual {
  val NO_HEARTBEAT_TIMESTAMP : Long = -1L
}

// Port of Akka Phi Accrual Failure Detector
case class PhiAccrualFailureDetector(
                       threshold : Double,
                       maxWindowSize : Int,
                       minStdDeviationMillis : Double,
                       acceptableHeartbeatPauseMillis : Int,
                       firstHeartbeatEstimateMillis : Int,
                       port : Int = 8090) {

  private val logger = Logger(this.getClass)

  final def getAddr = new InetSocketAddress("localhost", port)

  require(threshold > 0.0, "failure-detector.threshold must be > 0")
  require(maxWindowSize > 0, "failure-detector.max-sample-size must be > 0")
  require(minStdDeviationMillis > 0, "failure-detector.min-std-deviation must be > 0")
  require(acceptableHeartbeatPauseMillis >= 0.0, "failure-detector.acceptable-heartbeat-pause must be >= 0")
  require(firstHeartbeatEstimateMillis > 0, "failure-detector.heartbeat-interval must be > 0")

  private val window = HeartbeatIntervals(maxWindowSize)
  private val bootstrappedStdDev = firstHeartbeatEstimateMillis / 4

  this.synchronized {
    window.add(Some(firstHeartbeatEstimateMillis - bootstrappedStdDev), NO_HEARTBEAT_TIMESTAMP)
    window.add(Some(firstHeartbeatEstimateMillis + bootstrappedStdDev), NO_HEARTBEAT_TIMESTAMP)
    //Launch actor
    val actorSystem = ActorSystem("Failure-Detector-Actor-System")
    actorSystem.actorOf(Props(classOf[FailureDetectorActor], this, actorSystem))
  }

  //Add to the window
  def heartbeat(ms : Long) = {
    window.synchronized {
      logger.info(s"Heartbeat received : $ms")
      logger.trace(s"Heartbeats : $window")
      val lastTimestamp = window.lastTimestamp
      if(NO_HEARTBEAT_TIMESTAMP != lastTimestamp) {
        val diff = (ms - lastTimestamp).toInt
        if(isAlive(ms)) {
          window.add(Some(diff), ms)
        }
      }
      else {
          window.add(None, ms)
      }
    }
  }

  def suspicion(ms : Long) = phi(ms)

  def isAlive(ms : Long): Boolean = phi(ms) < threshold


  //This is to adapt to the underlying network conditions. If the intially passed stdDev is high, this means that the network is quite slow.
  //Since we are maintaing std dev with the samples, this means that the std dev is dynamically changing
  private def ensureValidStdDeviation(stdDeviationMillis : Double)=  max(stdDeviationMillis, minStdDeviationMillis)

  private def phi(ms : Long): Double = {
    val lastTimestamp = window.lastTimestamp
    var result = 0.0
    // If we have no connections from processes under monitoring, return 0.0. Otherwise compute phi
    if(NO_HEARTBEAT_TIMESTAMP != lastTimestamp) {
      val timeDiffMs = ms - lastTimestamp
      val meanMillis = window.mean
      val stdDevMillis = ensureValidStdDeviation(window.stdDev)
      result = phi(timeDiffMs, meanMillis + acceptableHeartbeatPauseMillis.toDouble, stdDevMillis)
    }
    result
  }

  /**
    * Calculation of phi, derived from the Cumulative distribution function for
    * N(mean, stdDeviation) normal distribution, given by
    * 1.0 / (1.0 + math.exp(-y * (1.5976 + 0.070566 * y * y)))
    * where y = (x - mean) / standard_deviation
    * This is an approximation defined in β Mathematics Handbook (Logistic approximation).
    * Error is 0.00014 at +- 3.16
    * The calculated value is equivalent to -log10(1 - CDF(y))
    */
   private def phi(timesDiff : Long, meanMillis : Double, stdDeviationMillis : Double) : Double = {
     require(stdDeviationMillis > 0, "stdDev cannot be less than 0")
     val y = (timesDiff.toDouble - meanMillis) / stdDeviationMillis
     val e = Math.exp(-y * (1.5976 + 0.070566 * y * y))
     if (timesDiff > meanMillis)  {
       -Math.log10(e / (1.0 + e))
     }
     else  {
       -Math.log10(1.0 - 1.0 / (1.0 + e))
     }
  }

  def debug =  window.toString

}

//Later to be implemented as a CountBasedWindow
case class HeartbeatIntervals(windowSize : Int) {

  require(windowSize > 0, "windowSize should be greater than 0 always")

  private val queue = new mutable.Queue[Int]()
  // TODO : Should the foll 2 variables be AtomicReference? Think on race conditions or if it even makes sense
  private var intervalSum = 0.0 // for mean.
  private var intervalSumSq = 0.0 // for variance
  private val lastTimestampAtomic = new AtomicLong(NO_HEARTBEAT_TIMESTAMP)

  final def lastTimestamp = lastTimestampAtomic.get

  final def add(optInterval : Option[Int], ms : Long) = {
    if(optInterval.isEmpty) {
      lastTimestampAtomic.set(ms)
    }
    else {
      val interval = optInterval.get
      lastTimestampAtomic.set(ms)
      //If size exceeds the max window size, start popping the queue to maintain the size
      if(queue.size >= windowSize) {
        val dequeued = queue.dequeue()
        intervalSum -= dequeued
        intervalSumSq -= pow(dequeued, 2)
      }
      queue.enqueue(interval)
      intervalSum += interval
      intervalSumSq += pow(interval, 2)
    }
  }


  final def mean = intervalSum / queue.size
  private def variance = ( intervalSumSq / queue.size ) - pow(mean, 2)
  final def stdDev = sqrt(variance)

  override def toString: String =  queue.toString

}


object PhiAccrualTest extends App {

  val threshold = 0.01
  val maxSampleSize = 10000

  //Assume a high std Deviation in the beginning, but not so high. It should be an educated guess as to how bad the network would be
  //It should be low enough so that the failure detector can ensure that if the
  val minStdDeviationMillis = 5.0

  val acceptableHeartbeatPauseMillis = 10

  val firstHeartbeatEstimateMillis = 10

  val failureDetector = PhiAccrualFailureDetector(threshold, maxSampleSize, minStdDeviationMillis, acceptableHeartbeatPauseMillis, firstHeartbeatEstimateMillis)
////  var initial = 1586286461860L
//  for(i <- Range(0, 1000)) {
//    failureDetector.heartbeat(System.currentTimeMillis())
//  }


}