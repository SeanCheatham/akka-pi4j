package com.seancheatham.akkapi4j.scaladsl

import akka.actor.CoordinatedShutdown
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorSystem, Extension, ExtensionId}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{ActorAttributes, Materializer, OverflowStrategy}
import akka.{Done, NotUsed}
import com.pi4j.Pi4J
import com.pi4j.context.Context
import com.pi4j.event.ShutdownEvent
import com.pi4j.io.gpio.digital._

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, Future, Promise}

class AkkaPi4j()(implicit system: ActorSystem[_]) extends Extension {
  val context: Context = Pi4J.newAutoContext()

  CoordinatedShutdown(system.toClassic)
    .addTask(CoordinatedShutdown.PhaseServiceStop, "pi4j-context-shutdown") { () =>
      val promise = Promise[Done]()
      context.addListener { (_: ShutdownEvent) =>
        promise.success(Done)
        ()
      }
      context.shutdown()
      promise.future
    }
}

object AkkaPi4j extends ExtensionId[AkkaPi4j] {

  def out(id: String, pin: Int, initialState: DigitalState, shutdownState: DigitalState): Sink[OutCommand, Future[Done]] =
    Sink.fromMaterializer {
      case (mat, _) =>
        val pi4j = AkkaPi4j(mat.system.toTyped).context
        val config =
          DigitalOutput.newConfigBuilder(pi4j)
            .id(id)
            .address(pin)
            .initial(initialState)
            .shutdown(shutdownState)
            .provider("pigpio-digital-output")

        val out = pi4j.create(config)

        Flow[OutCommand]
          .alsoTo(Sink.onComplete(_ => out.shutdown(pi4j)))
          .toMat(
            Sink.foreach[OutCommand] {
              case SetState(state) =>
                out.state(state)
              case Toggle =>
                out.toggle()
            }
          )(Keep.right)

    }
      .addAttributes(ActorAttributes.dispatcher("pi4j-dispatcher"))
      .mapMaterializedValue(_.flatten)

  def in(id: String, pin: Int, pull: PullResistance, debounce: FiniteDuration): Source[DigitalState, NotUsed] =
    Source.fromMaterializer { case (mat, _) =>
      implicit def materializer: Materializer = mat

      val pi4j = AkkaPi4j(mat.system.toTyped).context
      val config =
        DigitalInput.newConfigBuilder(pi4j)
          .id(id)
          .address(pin)
          .pull(pull)
          .debounce(debounce.toMicros)
          .provider("pigpio-digital-output")
      val input = pi4j.create(config)
      val (queue, source) =
        Source.queue[DigitalState](512, OverflowStrategy.dropHead)
          .preMaterialize()
      val listener: DigitalStateChangeListener = event => Await.result(queue.offer(event.state()), Duration.Inf)
      input.addListener(listener)
      Source.lazySingle(() => input.state()).concat(source).alsoTo(Sink.onComplete(_ => input.shutdown(pi4j)))
    }
      .addAttributes(ActorAttributes.dispatcher("pi4j-dispatcher"))
      .mapMaterializedValue(_ => NotUsed)

  override def createExtension(system: ActorSystem[_]): AkkaPi4j = new AkkaPi4j()(system)
}

sealed trait OutCommand

case class SetState(state: DigitalState) extends OutCommand

case object Toggle extends OutCommand
