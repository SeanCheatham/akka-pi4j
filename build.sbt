inThisBuild(
    List(
        organization := "com.seancheatham",
        scalaVersion := "2.13.6"
    )
)

lazy val root = Project(id = "akka-pi4j", base = file("."))
  .aggregate(akkaPi4jCore)

lazy val akkaPi4jCore = Project(id = "akka-pi4j-core", base = file("core"))
  .settings(
      libraryDependencies ++= Seq(
          Dependencies.akka("actor"),
          Dependencies.akka("actor-typed"),
          Dependencies.cats,
          Dependencies.pi4j
      )
  )
