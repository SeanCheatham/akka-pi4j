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
import scala.util.Try

trait TemperatureReader {

  def read(): TemperatureHumidity

}

case class TemperatureHumidity(temperatureF: Float, humidity: Float, timestamp: Instant)

class DHT22(pin: Int)(implicit pigpio: PiGpio) extends TemperatureReader {

  pigpio.gpioSetPullUpDown(pin, PiGpioPud.OFF)

  private val LongestZero = 50_000

  override def read(): TemperatureHumidity = {
    pigpio.gpioInitialize()
    pigpio.gpioWrite(pin, PiGpioState.LOW)
    pigpio.gpioDelayMilliseconds(17)
    pigpio.gpioSetMode(pin, PiGpioMode.INPUT)
    // TODO setWatchdog(pin, 200)

    var lastTick = 0L
    var index = 40
    var CS: Int = 0
    var humidity: Option[Float] = None
    var temperature: Option[Float] = None

    var lowTemperatureByte: Int = 0
    var highTemperatureByte: Int = 0
    var lowHumidityByte: Int = 0
    var highHumidityByte: Int = 0

    val listener: PiGpioStateChangeListener = {
      new PiGpioStateChangeListener {
        override def onChange(event: PiGpioStateChangeEvent): Unit = {
          val diff = event.tick() - lastTick
          val level = event.state().value()
          level match {
            case 0 =>
              require(diff < 200)
              val bitValue: Byte = if (diff >= 50) 1 else 0
              if (index >= 40) {
                index = 40
              } else if (index >= 32) {
                CS = (CS << 1) + bitValue
                if (index == 39) {
                  pigpio.removePinListener(pin, this)
                  val total: Int = highHumidityByte + lowHumidityByte + highTemperatureByte + lowTemperatureByte
                  require((total & 255) == CS)
                  humidity = Some(((highHumidityByte << 8) + lowHumidityByte) * 0.1f)
                  val temperatureMultiplier: Float =
                    if ((highTemperatureByte & 128) == CS) -0.1f else 0.1f
                  if ((highTemperatureByte & 128) == CS) highTemperatureByte &= 127
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
              if (diff > 250000) {
                index = -2
                highHumidityByte = 0
                lowHumidityByte = 0
                highTemperatureByte = 0
                lowTemperatureByte = 0
                CS = 0
              }

            case _ =>
              pigpio.removeListener(this)
          }
        }
      }
    }

    pigpio.gpioWrite(pin, PiGpioState.LOW)
    pigpio.gpioDelayMilliseconds(17)
    pigpio.gpioWrite(pin, PiGpioState.HIGH)
    pigpio.gpioSetMode(pin, PiGpioMode.INPUT)
    pigpio.addPinListener(pin, listener)
    pigpio.gpioDelayMilliseconds(200)

    require(temperature.nonEmpty, "Temperature not found")
    require(humidity.nonEmpty, "Humidity not found")
    TemperatureHumidity(temperature.get, humidity.get, Instant.now())
  }

}

object DHT22 {

  /**
   * DHT22 can only read once every 2 seconds, so pad a little bit of extra to be safe
   */
  val readDelay: FiniteDuration = 2100.milli

  def source(pin: Int): Source[TemperatureHumidity, Future[Cancellable]] =
    Source.fromMaterializer {
      case (mat, _) =>
        val dht22 = new DHT22(pin)(PiGpioProvider(mat.system.toTyped).piGpio)
        Source.tick(0.seconds, readDelay, NotUsed)
          .map(_ => dht22.read())
          .async("pi4j-dispatcher")
    }
}

class PiGpioProvider(system: ActorSystem[_]) extends Extension {
  val piGpio: PiGpio = PiGpio.newNativeInstance()

  CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseServiceStop, "stop-pigpio")(() => {
    Future.fromTry(Try {
      piGpio.shutdown()
      Done
    })
  })
}

object PiGpioProvider extends ExtensionId[PiGpioProvider] {
  override def createExtension(system: ActorSystem[_]): PiGpioProvider = new PiGpioProvider(system)
}
