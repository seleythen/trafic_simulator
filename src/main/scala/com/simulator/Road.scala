package com.simulator

import akka.actor.{Actor, ActorRef, Props}
import com.simulator.TimeSynchronizer.{ComputeTimeSlot, InfrastructureComputed}

object Road {
  def props(startJunction: ActorRef, endJunction: ActorRef, length: Double): Props =
    Props(new Road(startJunction, endJunction, length))

  final case class GetNthCar(n: Int)
  final case class AddCar(car: ActorRef)
  final case class RemoveCar(car: ActorRef)
  final case class NthCar(car: Option[ActorRef])
  case object GetEndJunction
  case object GetLength
  final case class GetLengthResult(length: Double)
  final case class GetEndJunctionResult(endJunction: ActorRef)
  final case class Movement(from: Double, to: Double)
}

class Road(val startJunction: ActorRef,
           val endJunction: ActorRef,
           val length: Double) extends Actor {

  import Road._
  import Car._

  var cars = List.empty[ActorRef]
  var synchronization: Int = -1

  var movementsInTurn = List.empty[(ActorRef, Double, Double)]

  def receive = {
    case GetNthCar(n) =>
      sender() ! NthCar(cars.lift(n - 1))
    case AddCar(ref) =>
      cars :+= ref
    case RemoveCar(ref) =>
      cars = cars.filter(_ != ref)
    case GetEndJunction =>
      sender() ! GetEndJunctionResult(endJunction)
    case GetLength =>
      sender() ! GetLengthResult(length)
    case Movement(from, to) =>
      movementsInTurn :+= (sender(), from, to)
    case ComputeTimeSlot(s) =>
      synchronization = s
      movementsInTurn.sortBy(_._2)
      for(i <- 1 to (movementsInTurn.size-1)){
        val carA = movementsInTurn(i-1)
        val carB = movementsInTurn(i)

        if(carA._3 > carB._3) {
          carA._1 ! Crash
          carB._1 ! Crash
        }
      }
  }
}
