package com.simulator.simulation

import akka.Done
import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import com.simulator.common._
import com.simulator.simulation.actor._

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class SimulationServiceImpl(initialState: Snapshot)
                           (implicit system: ActorSystem, ec: ExecutionContext) extends SimulationService {

  private var timeSynchronizer: TimeSynchronizerRef = _
  private var junctions: Map[JunctionId, JunctionRef] = Map.empty
  private var roads: Map[RoadId, RoadRef] = Map.empty
  private var cars: Map[CarId, CarRef] = Map.empty

  private def createJunctionActor(junction: JunctionState): JunctionRef = {
    val junctionActor = system.actorOf(
      actor.Junction.props(junction.id),
      f"junction-${ junction.id.value }")

    timeSynchronizer ! actor.TimeSynchronizer.AddInfrastructure(junctionActor)

    junctionActor
  }

  private def createRoadActor(road: RoadState, reversed: Boolean): RoadRef = {
    val endActors = (junctions(road.start), junctions(road.end))

    val (startActor, endActor) = if (reversed) endActors.swap else endActors
    val suffix = if (reversed) "B" else "A"

    val roadActor = system.actorOf(actor.Road.props(road.id, startActor, endActor, 5.0),
      f"road-${ road.id.value }$suffix")

    startActor ! actor.Junction.AddRoad(roadActor, actor.Junction.OutDirection)
    endActor ! actor.Junction.AddRoad(roadActor, actor.Junction.InDirection)
    timeSynchronizer ! actor.TimeSynchronizer.AddInfrastructure(roadActor)

    roadActor
  }

  private def createCarActor(car: CarState): CarRef = {
    val roadActor = roads(car.road)

    val carActor = system.actorOf(
      actor.Car.props(car.id, roadActor, car.positionOnRoad),
      f"car-${ car.id.value }")

    roadActor ! actor.Road.AddCar(carActor, 0.0, car.positionOnRoad)
    timeSynchronizer ! actor.TimeSynchronizer.AddCar(carActor)

    carActor
  }

  override def initialize(): Future[Done] = {
    timeSynchronizer = system.actorOf(actor.TimeSynchronizer.props(), "timeSynchronizer")

    junctions = initialState.junctions
      .map { junction =>
        junction.id -> createJunctionActor(junction)
      }
      .toMap

    roads = initialState.roads
      .flatMap { road =>
        Seq(
          road.id -> createRoadActor(road, reversed = false),
          road.id -> createRoadActor(road, reversed = true)
        )
      }
      .toMap

    cars = initialState.cars
      .map { car =>
        car.id -> createCarActor(car)
      }
      .toMap

    Future { Done }
  }

  override def simulateTimeSlot(): Future[Snapshot] = {
    implicit val timeout: Timeout = 1 second

    for {
      _ <- ask(timeSynchronizer, TimeSynchronizer.NextTimeSlot).mapTo[TimeSynchronizer.TimeSlotComputed.type]

      junctions: Iterable[JunctionState] <- Future.traverse(junctions.values) { junctionActor =>
        ask(junctionActor, actor.Junction.GetState)
          .mapTo[actor.Junction.GetStateResult]
          .map { status =>
            initialState.junctions.find { _.id == status.junctionId }.get
          }
      }

      cars: Iterable[CarState] <- Future.traverse(cars.values) { carActor =>
        ask(carActor, actor.Car.GetState)
          .mapTo[actor.Car.GetStateResult]
          .map { status =>
            val roadId = roads.find { _._2 == status.roadRef }.get._1
            CarState(status.carId, roadId, status.positionOnRoad.toFloat, status.velocity.toFloat, status.breaking)
          }
      }
    } yield {
      Snapshot(junctions.to[Seq], initialState.roads, cars.to[Seq])
    }
  }

}
