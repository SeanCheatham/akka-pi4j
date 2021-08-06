package com.seancheatham.akkapi4j.scaladsl

import akka.actor.CoordinatedShutdown
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorSystem, Extension, ExtensionId}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import akka.{Done, NotUsed}
import com.pi4j.Pi4J
import com.pi4j.context.Context
import com.pi4j.event.ShutdownEvent
import com.pi4j.io.gpio.digital._

import scala.concurrent.duration.Duration
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

  /**
   * A Sink which accepts commands to change the state of an output pin
   */
  def out(config: DigitalOutputConfig): Sink[OutCommand, Future[Done]] =
    Sink
      .fromMaterializer { case (mat, _) =>
        val pi4j = AkkaPi4j(mat.system.toTyped).context

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
      .async("pi4j-dispatcher")
      .mapMaterializedValue(_.flatten)

  /**
   * A source that listens to changes in the state of an input pin
   */
  def in(config: DigitalInputConfig): Source[DigitalStateChangeEvent[DigitalInput], NotUsed] =
    Source
      .fromMaterializer { case (mat, _) =>
        implicit def materializer: Materializer = mat

        val pi4j = AkkaPi4j(mat.system.toTyped).context
        val input = pi4j.create(config)
        val (queue, source) =
          Source
            .queue[DigitalStateChangeEvent[DigitalInput]](512, OverflowStrategy.dropHead)
            .preMaterialize()
        val listener: DigitalStateChangeListener =
          event => Await.result(queue.offer(event.asInstanceOf[DigitalStateChangeEvent[DigitalInput]]), Duration.Inf)
        input.addListener(listener)
        Source
          .lazySingle(() => new DigitalStateChangeEvent[DigitalInput](input, input.state()))
          .concat(source)
          .alsoTo(Sink.onComplete(_ => input.shutdown(pi4j)))
      }
      .async("pi4j-dispatcher")
      .mapMaterializedValue(_ => NotUsed)

  def scriptSource(command: () => String): Source[Source[String, NotUsed], NotUsed] =
    Source
      .repeat(NotUsed)
      .map(_ => Source.fromIterator(() => sys.process.Process(command()).lazyLines.iterator))
      .async("pi4j-dispatcher")

  def scriptSink: Sink[String, Future[Done]] =
    Sink
      .foreach[String](sys.process.Process(_).run().exitValue())
      .async("pi4j-dispatcher")

  override def createExtension(system: ActorSystem[_]): AkkaPi4j = new AkkaPi4j()(system)
}

sealed trait OutCommand

case class SetState(state: DigitalState) extends OutCommand

case object Toggle extends OutCommand
