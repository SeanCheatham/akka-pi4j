import sbt._

object Dependencies {

    def akka(name: String): ModuleID =
        "com.typesafe.akka" %% s"akka-$name" % Versions.Akka

    val cats: ModuleID =
        "org.typelevel" %% "cats-core" % Versions.Cats

    val circe: ModuleID =
         "io.circe" %% "circe-core" % Versions.Circe

    val pi4j: Seq[ModuleID] =
         Seq(
             "com.pi4j" % "pi4j-core" % Versions.Pi4j,
             "com.pi4j" % "pi4j-plugin-raspberrypi" % Versions.Pi4j,
             "com.pi4j" % "pi4j-plugin-pigpio" % Versions.Pi4j
         )
}

object Versions {
    val Akka = "2.6.14"
    val AkkaHttp = "10.2.4"
    val Cats = "2.6.1"
    val Circe = "0.15.0-M1"
    val Pi4j = "2.0-SNAPSHOT"
}