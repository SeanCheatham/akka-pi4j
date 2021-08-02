package com.seancheatham.akkapi4j.scaladsl

import com.pi4j.library.pigpio._

import scala.concurrent.duration.{FiniteDuration, _}
trait TemperatureReader {

  def read(): TemperatureHumidity

}

case class TemperatureHumidity(temperatureF: Float, humidity: Float)

class DHT22(pin: Int) extends TemperatureReader {
  private val pigpio = PiGpio.newNativeInstance()

  pigpio.gpioSetPullUpDown(pin, PiGpioPud.OFF)

  private val LongestZero = 50_000
  private val readDelay: FiniteDuration = 2100.milli

  override def read(): TemperatureHumidity = {
    pigpio.gpioInitialize()
    pigpio.gpioWrite(pin, PiGpioState.LOW)
    // Thread.sleep(17)
    pigpio.gpioDelayMilliseconds(17)
    pigpio.gpioSetMode(pin, PiGpioMode.INPUT)
    // TODO setWatchdog(pin, 200)

    var lastTick = 0L
    var index = 40
    var CS: Int = null
    var humidity: Option[Float] = None
    var temperature: Option[Float] = None

    var lowTemperatureByte: Int = null
    var highTemperatureByte: Int = null
    var lowHumidityByte: Int = null
    var highHumidityByte: Int = null

    val listener: PiGpioStateChangeListener = {
      new PiGpioStateChangeListener {
        override def onChange(event: PiGpioStateChangeEvent): Unit = {
          val diff = event.tick() - lastTick
          val level = event.state().value()
          level match {
            case 0 =>
              require(diff < 200)
              val bitValue: Boolean = diff >= 50
              if(index >= 40) {
                index = 40
              } else if(index >= 32) {
                CS = (CS << 1) + bitValue
                if(index == 39) {
                  pigpio.removePinListener(pin, this)
                  val total: Int = highHumidityByte + lowHumidityByte + highTemperatureByte + lowTemperatureByte
                  require(total & 255 == CS)
                  humidity = Some(((highHumidityByte << 8) + lowHumidityByte) * 0.1f)
                  val temperatureMultiplier: Float =
                    if(highTemperatureByte & 128 == CS) -0.1f else 0.1f
                  if(highTemperatureByte & 128 == CS) highTemperatureByte &= 127
                  temperature = Some(((highTemperatureByte << 8) + lowTemperatureByte) * temperatureMultiplier)
                }
              } else if(index >= 24) {
                lowTemperatureByte = (lowTemperatureByte << 1) + bitValue
              } else if(index >= 16) {
                highTemperatureByte = (highTemperatureByte << 1) + bitValue
              } else if(index >= 8) {
                lowHumidityByte = (lowHumidityByte << 1) + bitValue
              } else if(index >= 0) {
                highHumidityByte = (highHumidityByte << 1) + bitValue
              } else {}

              index += 1
            case 1 =>
              lastTick = event.tick()
              if(diff > 250000) {
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
    TemperatureHumidity(temperature.get, humidity.get)
  }
}
