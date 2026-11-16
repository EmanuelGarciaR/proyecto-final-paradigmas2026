import java.io.{BufferedOutputStream, FileInputStream, FileOutputStream}
import java.util.jar.{Attributes, JarEntry, JarInputStream, JarOutputStream, Manifest}
import scala.collection.mutable

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
    assembly := {
      val log = streams.value.log
      val classDir = (Compile / classDirectory).value
      val _ = (Compile / compile).value
      val cp = (Compile / fullClasspath).value
      val outJar = crossTarget.value / "backend-scala.jar"
      outJar.getParentFile.mkdirs()

      val manifest = new Manifest()
      manifest.getMainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")
      manifest.getMainAttributes.put(Attributes.Name.MAIN_CLASS, "com.chivasrumberas.Main")

      val seen = mutable.HashSet.empty[String]
      val services = mutable.LinkedHashMap.empty[String, mutable.ListBuffer[Byte]]
      val buf = new Array[Byte](16384)

      val jos = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(outJar)), manifest)
      try {
        def addDir(rootDir: File, current: File): Unit = {
          Option(current.listFiles()).getOrElse(Array.empty).foreach { f =>
            if (f.isDirectory) addDir(rootDir, f)
            else {
              val rel = rootDir.toURI.relativize(f.toURI).getPath.replace('\\', '/')
              if (!rel.startsWith("META-INF/") && seen.add(rel)) {
                jos.putNextEntry(new JarEntry(rel))
                val fis = new FileInputStream(f)
                try {
                  var n = fis.read(buf)
                  while (n != -1) {
                    jos.write(buf, 0, n)
                    n = fis.read(buf)
                  }
                } finally fis.close()
                jos.closeEntry()
              }
            }
          }
        }

        cp.map(_.data).foreach { file =>
          if (file.isDirectory) {
            addDir(file, file)
          } else if (file.isFile && file.getName.endsWith(".jar")) {
            val jis = new JarInputStream(new FileInputStream(file))
            try {
              var entry = jis.getNextJarEntry
              while (entry != null) {
                val name = entry.getName
                if (!entry.isDirectory) {
                  if (name.startsWith("META-INF/services/")) {
                    val b = services.getOrElseUpdate(name, mutable.ListBuffer.empty[Byte])
                    var n = jis.read(buf)
                    while (n != -1) {
                      var i = 0
                      while (i < n) { b += buf(i); i += 1 }
                      n = jis.read(buf)
                    }
                    b += '\n'.toByte
                  } else if (
                    !name.startsWith("META-INF/") &&
                    name != "module-info.class" &&
                    !name.endsWith("/module-info.class") &&
                    seen.add(name)
                  ) {
                    jos.putNextEntry(new JarEntry(name))
                    var n = jis.read(buf)
                    while (n != -1) {
                      jos.write(buf, 0, n)
                      n = jis.read(buf)
                    }
                    jos.closeEntry()
                  }
                }
                entry = jis.getNextJarEntry
              }
            } finally jis.close()
          }
        }

        services.foreach { case (serviceName, bytes) =>
          jos.putNextEntry(new JarEntry(serviceName))
          jos.write(bytes.toArray)
          jos.closeEntry()
        }
      } finally jos.close()

      log.info(s"Fat-JAR empaquetado exitosamente: ${outJar.getAbsolutePath}")
      outJar
    }
  )
