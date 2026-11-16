ThisBuild / version := "1.0.0"
ThisBuild / scalaVersion := "3.3.4"
ThisBuild / organization := "com.chivasrumberas"

val http4sVersion = "0.23.27"
val circeVersion = "0.14.9"
val micrometerVersion = "1.13.6"

lazy val root = (project in file("."))
  .settings(
    name := "chivas-rumberas-backend-scala",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl"          % http4sVersion,
      "org.http4s" %% "http4s-circe"        % http4sVersion,
      "io.circe"   %% "circe-core"          % circeVersion,
      "io.circe"   %% "circe-generic"       % circeVersion,
      "io.circe"   %% "circe-parser"        % circeVersion,
      "org.postgresql" % "postgresql"       % "42.7.4",
      "com.zaxxer"     % "HikariCP"         % "5.1.0",
      "org.springframework.security" % "spring-security-crypto" % "6.3.4",
      "commons-logging" % "commons-logging" % "1.3.4",
      "io.micrometer"  % "micrometer-core"  % micrometerVersion,
      "io.micrometer"  % "micrometer-registry-prometheus" % micrometerVersion,
      "org.slf4j"      % "slf4j-simple"     % "2.0.16",
      "org.scalameta"  %% "munit"           % "1.0.0" % Test,
      "org.typelevel"  %% "munit-cats-effect" % "2.0.0" % Test
    ),
    assembly / assemblyJarName := "backend-scala.jar",
    assembly / mainClass := Some("com.chivasrumberas.Main"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", xs @ _*) => MergeStrategy.concat
      case PathList("META-INF", xs @ _*)             => MergeStrategy.discard
      case "module-info.class"                       => MergeStrategy.discard
      case x                                         => MergeStrategy.first
    }
  )
