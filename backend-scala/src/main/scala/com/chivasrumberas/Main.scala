package com.chivasrumberas

import cats.effect.{ExitCode, IO, IOApp}
import com.chivasrumberas.application.ChivasBusinessService
import com.chivasrumberas.infrastructure.*
import com.chivasrumberas.web.{HttpAppBuilder, ObservabilityRegistry, SecurityAndRateLimiter}
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder

import java.util.concurrent.atomic.AtomicReference

object Main extends IOApp:
  private val processStartNanos: Long = System.nanoTime()

  private def env(key: String, default: String): String =
    sys.env.get(key).filter(_.trim.nonEmpty).getOrElse(default)

  override def run(args: List[String]): IO[ExitCode] =
    val portInt = env("PORT", "3000").toIntOption.getOrElse(3000)
    val dbUrl = env("DATABASE_URL", "jdbc:postgresql://localhost:5432/chivas_rumberas")
    val dbUser = env("DB_USERNAME", env("POSTGRES_USER", "chivas"))
    val dbPass = env("DB_PASSWORD", env("POSTGRES_PASSWORD", "chivas_secret_2026"))
    val jwtSecret = env("JWT_SECRET", "chivas_rumberas_super_secret_jwt_key_2026_benchmark")
    val jwtExpiresIn = env("JWT_EXPIRES_IN", "8h")
    val mockLatencyMs = env("MOCK_LATENCY_MS", "50").toLongOption.getOrElse(50L)
    val disableRateLimit = env("DISABLE_RATE_LIMIT", "false").equalsIgnoreCase("true")
    val cloudName = env("CLOUDINARY_CLOUD_NAME", "")
    val cloudApiKey = env("CLOUDINARY_API_KEY", "")
    val cloudApiSecret = env("CLOUDINARY_API_SECRET", "")

    val startupDurationRef = new AtomicReference[java.lang.Double](0.0)
    val registry = ObservabilityRegistry.create(startupDurationRef)

    val dataSource = DatabaseFactory.createDataSource(dbUrl, dbUser, dbPass)
    val chivaRepo = new PgChivaRepository(dataSource)
    val travelRepo = new PgTravelRepository(dataSource)
    val customerRepo = new PgCustomerRepository(dataSource)
    val bookingRepo = new PgBookingRepository(dataSource)
    val userRepo = new PgUserRepository(dataSource)

    val jwtService = new HmacJwtService(jwtSecret, jwtExpiresIn)
    val calendarService = new ConfigurableCalendarService(mockLatencyMs)
    val notificationService = new ConfigurableNotificationService(mockLatencyMs)
    val imageStorage = new CloudinaryImageStorageService(cloudName, cloudApiKey, cloudApiSecret)

    val businessService = new ChivasBusinessService(
      chivaRepo,
      travelRepo,
      customerRepo,
      bookingRepo,
      userRepo,
      jwtService,
      notificationService,
      calendarService,
      imageStorage
    )

    val security = new SecurityAndRateLimiter(jwtService, disableRateLimit)
    val httpApp = HttpAppBuilder.buildHttpApp(businessService, security, registry)

    EmberServerBuilder
      .default[IO]
      .withHost(Host.fromString("0.0.0.0").get)
      .withPort(Port.fromInt(portInt).get)
      .withHttpApp(httpApp)
      .build
      .evalTap { server =>
        IO {
          val elapsedSeconds = (System.nanoTime() - processStartNanos) / 1_000_000_000.0
          startupDurationRef.set(elapsedSeconds)
          println(s"🚀 Backend Scala 3 (http4s) corriendo en http://0.0.0.0:$portInt (startup: ${f"$elapsedSeconds%.3f"}s)")
        }
      }
      .useForever
      .as(ExitCode.Success)
