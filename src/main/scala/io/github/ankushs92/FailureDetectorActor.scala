package io.ankushs92.thesis.phi_accrual

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.io.{IO, Udp}
import com.typesafe.scalalogging.Logger

class FailureDetectorActor(phiAccrual : PhiAccrualFailureDetector)(implicit val system  : ActorSystem) extends Actor {
  private val logger = Logger(this.getClass)
  private val addr = phiAccrual.getAddr

  logger.info(s"FailureDetectorlaunched at socket $addr")

  IO(Udp) ! Udp.Bind(self, phiAccrual.getAddr)

  def receive = {
    case Udp.Bound(local) =>
      context.become(ready(sender()))
  }

  def ready(socket: ActorRef): Receive = {
    case Udp.Received(_, _) =>
      val now = System.currentTimeMillis
      phiAccrual.heartbeat(now)

    case Udp.Unbind  =>
      logger.info(s"Unsubscribing FailureDetectorActor from socket $addr")
      socket ! Udp.Unbind
    case Udp.Unbound =>
      logger.info(s"Releasing resources for Failure Detector at socket : $addr")
      context.stop(self)
  }
}


object  T extends App {
  implicit val system = ActorSystem("UDPTest")
  val addr = new InetSocketAddress("localhost", 8091)
  //These fields should come from System Env for now
//  println(System.getenv)
//  val threshold = System.getenv("failure_detector_threshold").toDouble
//  val maxSampleSize = System.getenv("failure_detector_max_sample_size").toInt
//  val minStdDev = System.getenv("failure_detector_min_std_deviation").toDouble
//  val minAcceptableHeartbeatPause = System.getenv("failure_detector_acceptable_heartbeat_pause").toInt
//  val firstHeartbeatInterval =  System.getenv("failure_detector_heartbeat_interval").toInt

  val phiAccrual = PhiAccrualFailureDetector(0.1, 100, 5, 10, 10)

//  val handler = system.actorOf(Props(classOf[FailureDetectorActor], phiAccrual, system))
}