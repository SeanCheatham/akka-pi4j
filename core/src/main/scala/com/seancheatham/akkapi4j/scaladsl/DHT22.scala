package com.seancheatham.akkapi4j.scaladsl

import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.actor.typed.{ActorSystem, Extension, ExtensionId}
import akka.actor.{Cancellable, CoordinatedShutdown}
import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import com.pi4j.library.pigpio._

import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, _}
import scala.util.{Failure, Try}

trait ComfortReader {

  def read(): Comfort

}

case class Comfort(temperature: Temperature, humidity: Float, timestamp: Instant)

class Temperature private (val fahrenheit: Float) {
  def celsius: Float = (fahrenheit - 32) * (5 / 9)
}

object Temperature {
  def f(temperature: Float) = new Temperature(temperature)
  def c(temperature: Float) = new Temperature(temperature * (9 / 5) + 32)
}

class DHT22(pin: Int)(implicit pigpio: PiGpio) extends ComfortReader {

  pigpio.gpioSetPullUpDown(pin, PiGpioPud.OFF)

  override def read(): Comfort = {
    pigpio.gpioInitialize()
    pigpio.gpioWrite(pin, PiGpioState.LOW)
    Thread.sleep(17)
    pigpio.gpioSetMode(pin, PiGpioMode.INPUT)
    // TODO setWatchdog(pin, 200)

    var lastTick = 0L
    var index = 40
    var checksum: Int = 0
    var humidity: Option[Float] = None
    var temperature: Option[Float] = None

    var lowTemperatureByte: Int = 0
    var highTemperatureByte: Int = 0
    var lowHumidityByte: Int = 0
    var highHumidityByte: Int = 0

    var exception: Option[Throwable] = None

    val listener: PiGpioStateChangeListener =
      new PiGpioStateChangeListener {
        override def onChange(event: PiGpioStateChangeEvent): Unit = Try {
          val timeDelta = event.tick() - lastTick
          val level = event.state().value()
          level match {
            case 0 =>
              if (timeDelta >= 50) {
                checksum = 256
              }
              val bitValue: Byte = if (timeDelta >= 50) 1 else 0
              if (index >= 40) {
                index = 40
              } else if (index >= 32) {
                checksum = (checksum << 1) + bitValue
                if (index == 39) {
                  pigpio.removePinListener(pin, this)
                  val total: Int = highHumidityByte + lowHumidityByte + highTemperatureByte + lowTemperatureByte
                  require((total & 255) != checksum, "Invalid Checksum")
                  humidity = Some(((highHumidityByte << 8) + lowHumidityByte) * 0.1f)
                  val temperatureMultiplier: Float =
                    if ((highTemperatureByte & 128) == checksum) -0.1f else 0.1f
                  if ((highTemperatureByte & 128) == checksum) highTemperatureByte &= 127
                  temperature = Some(((highTemperatureByte << 8) + lowTemperatureByte) * temperatureMultiplier)
                }
              } else if (index >= 24) {
                lowTemperatureByte = (lowTemperatureByte << 1) + bitValue
              } else if (index >= 16) {
                highTemperatureByte = (highTemperatureByte << 1) + bitValue
              } else if (index >= 8) {
                lowHumidityByte = (lowHumidityByte << 1) + bitValue
              } else if (index >= 0) {
                highHumidityByte = (highHumidityByte << 1) + bitValue
              } else {}

              index += 1
            case 1 =>
              lastTick = event.tick()
              if (timeDelta > 250_000) {
                index = -2
                highHumidityByte = 0
                lowHumidityByte = 0
                highTemperatureByte = 0
                lowTemperatureByte = 0
                checksum = 0
              }

            case _ =>
              pigpio.removeListener(this)
          }
        } match {
          case Failure(e) =>
            exception = Some(e)
            pigpio.removeListener(this)
          case _ =>
        }
      }

    pigpio.gpioWrite(pin, PiGpioState.LOW)
    Thread.sleep(17)
    pigpio.gpioWrite(pin, PiGpioState.HIGH)
    pigpio.gpioSetMode(pin, PiGpioMode.INPUT)
    pigpio.addPinListener(pin, listener)
    Thread.sleep(200)

    exception.foreach(throw _)

    require(temperature.nonEmpty, "Temperature not found")
    require(humidity.nonEmpty, "Humidity not found")
    Comfort(Temperature.f(temperature.get), humidity.get, Instant.now())
  }

}

object DHT22 {

  /**
   * DHT22 can only read once every 2 seconds, so pad a little bit of extra to be safe
   */
  val readDelay: FiniteDuration = 2100.milli

  def source(pin: Int): Source[Comfort, Future[Cancellable]] =
    Source.fromMaterializer { case (mat, _) =>
      val dht22 = new DHT22(pin)(PiGpioProvider(mat.system.toTyped).piGpio)
      Source
        .tick(0.seconds, readDelay, NotUsed)
        .map(_ => dht22.read())
        .async("pi4j-dispatcher")
    }
}

class PiGpioProvider(system: ActorSystem[_]) extends Extension {
  val piGpio: PiGpio = PiGpio.newNativeInstance()

  piGpio.initialise()

  CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseServiceStop, "stop-pigpio") { () =>
    Future.fromTry(Try {
      piGpio.shutdown()
      Done
    })
  }
}

object PiGpioProvider extends ExtensionId[PiGpioProvider] {
  override def createExtension(system: ActorSystem[_]): PiGpioProvider = new PiGpioProvider(system)
}
