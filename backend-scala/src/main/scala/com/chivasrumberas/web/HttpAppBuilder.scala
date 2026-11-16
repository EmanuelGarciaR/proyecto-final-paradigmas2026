package com.chivasrumberas.web

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import com.chivasrumberas.application.ChivasBusinessService
import com.chivasrumberas.domain.{Domain, DomainException}
import com.chivasrumberas.domain.Domain.JwtPayload
import com.chivasrumberas.infrastructure.JwtTokenService
import com.chivasrumberas.web.ApiDtos.*
import io.circe.Json
import io.circe.syntax.*
import io.micrometer.core.instrument.{Counter, FunctionCounter, Gauge, Timer}
import io.micrometer.core.instrument.binder.jvm.{JvmGcMetrics, JvmMemoryMetrics}
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.{PrometheusConfig, PrometheusMeterRegistry}
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

import java.lang.management.ManagementFactory
import java.time.{Duration, Instant}
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedDeque, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

object ObservabilityRegistry:
  def create(startupDurationSeconds: AtomicReference[ java.lang.Double ]): PrometheusMeterRegistry =
    val registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    new JvmMemoryMetrics().bindTo(registry)
    new JvmGcMetrics().bindTo(registry)
    new ProcessorMetrics().bindTo(registry)

    Gauge
      .builder("chivas_rumberas_startup_duration_seconds", startupDurationSeconds, (ref: AtomicReference[java.lang.Double]) => ref.get())
      .description("Tiempo de arranque del backend en segundos.")
      .register(registry)

    Gauge
      .builder(
        "chivas_rumberas_process_resident_memory_bytes",
        Runtime.getRuntime,
        (rt: Runtime) => (rt.totalMemory() - rt.freeMemory()).toDouble
      )
      .description("Memoria residente/usada por el proceso en bytes.")
      .register(registry)

    ManagementFactory.getOperatingSystemMXBean match
      case osBean: com.sun.management.OperatingSystemMXBean =>
        FunctionCounter
          .builder(
            "chivas_rumberas_process_cpu_seconds",
            osBean,
            (bean: com.sun.management.OperatingSystemMXBean) => bean.getProcessCpuTime / 1_000_000_000.0
          )
          .description("Tiempo total de CPU consumido por el proceso en segundos.")
          .register(registry)
      case _ => ()

    registry

class SecurityAndRateLimiter(jwtService: JwtTokenService, rateLimitDisabled: Boolean):
  private val loginAttempts = new ConcurrentHashMap[String, ConcurrentLinkedDeque[ java.lang.Long ]]()
  private val trackAttempts = new ConcurrentHashMap[String, ConcurrentLinkedDeque[ java.lang.Long ]]()
  private val WindowMs = 15L * 60L * 1000L

  def authenticate(req: Request[IO]): JwtPayload =
    val authHeader = req.headers.get(CIString("Authorization")).map(_.head.value).getOrElse("")
    if !authHeader.startsWith("Bearer ") then throw DomainException.invalidToken()
    val token = authHeader.substring("Bearer ".length).trim
    jwtService.verify(token)

  def requireAdmin(req: Request[IO]): JwtPayload =
    val payload = authenticate(req)
    if payload.role != "admin" then throw DomainException.unauthorized()
    payload

  def checkLoginRateLimit(req: Request[IO]): Unit =
    if !rateLimitDisabled then
      checkRateLimit(
        loginAttempts,
        clientKey(req),
        10,
        "Demasiados intentos de inicio de sesión. Por favor intenta de nuevo en unos minutos."
      )

  def checkTrackRateLimit(req: Request[IO]): Unit =
    if !rateLimitDisabled then
      checkRateLimit(
        trackAttempts,
        clientKey(req),
        15,
        "Demasiados intentos de seguimiento. Por favor intenta de nuevo en unos minutos."
      )

  private def clientKey(req: Request[IO]): String =
    req.headers
      .get(CIString("X-Forwarded-For"))
      .map(_.head.value.split(",")(0).trim)
      .orElse(req.remote.map(_.host.toUriString))
      .getOrElse("local")

  private def checkRateLimit(
      store: ConcurrentHashMap[String, ConcurrentLinkedDeque[java.lang.Long]],
      key: String,
      maxRequests: Int,
      message: String
  ): Unit =
    val now = System.currentTimeMillis()
    val deque = store.computeIfAbsent(key, _ => new ConcurrentLinkedDeque[java.lang.Long]())
    deque.synchronized {
      while !deque.isEmpty && (now - deque.peekFirst()) > WindowMs do deque.pollFirst()
      if deque.size() >= maxRequests then throw DomainException.tooManyRequests(message)
      deque.addLast(now)
    }

object HttpAppBuilder:
  private val BookingReservedPaths = Set("availability", "track", "calendar")

  private def normalizeRoute(path: String): String =
    val parts = path.split("/").filter(_.nonEmpty)
    parts match
      case Array("chivas", _)                      => "/chivas/:id"
      case Array("chivas", _, "status")            => "/chivas/:id/status"
      case Array("bookings", seg) if !BookingReservedPaths.contains(seg) => "/bookings/:id"
      case Array("bookings", _, "status")          => "/bookings/:id/status"
      case Array("bookings", _, "calendar-sync")   => "/bookings/:id/calendar-sync"
      case _                                       => path

  private def parseJsonOrEmpty(req: Request[IO]): IO[Json] =
    req.bodyText.compile.string.map { raw =>
      if raw == null || raw.trim.isEmpty then Json.obj()
      else io.circe.parser.parse(raw).getOrElse(throw DomainException.validation("Cuerpo de solicitud JSON inválido."))
    }

  private def toResponse(statusInt: Int, json: Json): IO[Response[IO]] =
    val st = Status.fromInt(statusInt).getOrElse(Status.BadRequest)
    IO.pure(
      Response[IO](status = st)
        .withEntity(json)
        .putHeaders(
          Header.Raw(CIString("Access-Control-Allow-Origin"), "*"),
          Header.Raw(CIString("Access-Control-Allow-Methods"), "GET, POST, PUT, PATCH, DELETE, OPTIONS"),
          Header.Raw(CIString("Access-Control-Allow-Headers"), "Content-Type, Authorization")
        )
    )

  def buildHttpApp(
      service: ChivasBusinessService,
      security: SecurityAndRateLimiter,
      registry: PrometheusMeterRegistry
  ): HttpApp[IO] =
    val routes = HttpRoutes.of[IO] {
      case req @ OPTIONS -> _ =>
        toResponse(204, Json.Null)

      case GET -> Root / "health" =>
        toResponse(200, HealthResponse("ok", Instant.now().toString).asJson)

      case GET -> Root / "metrics" =>
        Ok(registry.scrape()).map(
          _.withContentType(`Content-Type`(MediaType.text.plain))
        )

      case req @ POST -> Root / "auth" / "login" =>
        for
          _    <- IO.blocking(security.checkLoginRateLimit(req))
          json <- parseJsonOrEmpty(req)
          dto  <- IO.fromEither(json.as[LoginDto].left.map(_ => DomainException.invalidCredentials()))
          res  <- IO.blocking(service.login(dto))
          resp <- toResponse(200, res.asJson)
        yield resp

      case req @ GET -> Root / "chivas" =>
        val statusParam = req.uri.query.params.get("status")
        IO.blocking(service.getChivas(statusParam)).flatMap(res => toResponse(200, res.asJson))

      case req @ POST -> Root / "chivas" =>
        for
          _    <- IO(security.requireAdmin(req))
          json <- parseJsonOrEmpty(req)
          res  <- IO.blocking(service.createChiva(json))
          resp <- toResponse(201, res.asJson)
        yield resp

      case req @ PATCH -> Root / "chivas" / id / "status" =>
        for
          _    <- IO(security.requireAdmin(req))
          json <- parseJsonOrEmpty(req)
          res  <- IO.blocking(service.updateChivaStatus(id, json))
          resp <- toResponse(200, res.asJson)
        yield resp

      case GET -> Root / "chivas" / id =>
        IO.blocking(service.getChivaById(id)).flatMap(res => toResponse(200, res.asJson))

      case req @ PATCH -> Root / "chivas" / id =>
        for
          _    <- IO(security.requireAdmin(req))
          json <- parseJsonOrEmpty(req)
          res  <- IO.blocking(service.updateChiva(id, json))
          resp <- toResponse(200, res.asJson)
        yield resp

      case req @ DELETE -> Root / "chivas" / id =>
        for
          _    <- IO(security.requireAdmin(req))
          res  <- IO.blocking(service.deleteChiva(id))
          resp <- toResponse(200, res.asJson)
        yield resp

      case GET -> Root / "travels" =>
        IO.blocking(service.getTravels()).flatMap(res => toResponse(200, res.asJson))

      case req @ GET -> Root / "bookings" / "availability" =>
        val chivaId = req.uri.query.params.get("chivaId")
        val date = req.uri.query.params.get("date")
        IO.blocking(service.getAvailability(chivaId, date)).flatMap(res => toResponse(200, res.asJson))

      case req @ GET -> Root / "bookings" / "track" =>
        val code = req.uri.query.params.get("code")
        val email = req.uri.query.params.get("email")
        for
          _    <- IO.blocking(security.checkTrackRateLimit(req))
          res  <- IO.blocking(service.trackBooking(code, email))
          resp <- toResponse(200, res.asJson)
        yield resp

      case req @ GET -> Root / "bookings" / "calendar" =>
        val month = req.uri.query.params.get("month")
        for
          _    <- IO(security.requireAdmin(req))
          res  <- IO.blocking(service.getBookingsCalendar(month))
          resp <- toResponse(200, res.asJson)
        yield resp

      case req @ POST -> Root / "bookings" =>
        for
          json <- parseJsonOrEmpty(req)
          dto  <- IO.fromEither(json.as[CreateBookingDto].left.map(_ => DomainException.validation("Datos de reserva inválidos.")))
          res  <- IO.blocking(service.createBooking(dto))
          resp <- toResponse(201, res.asJson)
        yield resp

      case req @ GET -> Root / "bookings" =>
        val params = req.uri.query.params
        for
          _ <- IO(security.requireAdmin(req))
          res <- IO.blocking(
            service.getBookings(
              params.get("page"),
              params.get("limit"),
              params.get("status"),
              params.get("q")
            )
          )
          resp <- toResponse(200, res.asJson)
        yield resp

      case req @ PATCH -> Root / "bookings" / id / "status" =>
        for
          _    <- IO(security.authenticate(req))
          json <- parseJsonOrEmpty(req)
          res  <- IO.blocking(service.updateBookingStatus(id, json))
          resp <- toResponse(200, res.asJson)
        yield resp

      case req @ POST -> Root / "bookings" / id / "calendar-sync" =>
        for
          _    <- IO(security.requireAdmin(req))
          res  <- IO.blocking(service.syncBookingCalendar(id))
          resp <- toResponse(200, res.asJson)
        yield resp

      case req @ GET -> Root / "bookings" / id =>
        for
          _    <- IO(security.authenticate(req))
          res  <- IO.blocking(service.getBookingById(id))
          resp <- toResponse(200, res.asJson)
        yield resp

      case req @ GET -> Root / "uploads" / "signature" =>
        for
          _    <- IO(security.requireAdmin(req))
          res  <- IO.blocking(service.getUploadSignature())
          resp <- toResponse(200, res.asJson)
        yield resp
    }

    Kleisli { (req: Request[IO]) =>
      val startNanos = System.nanoTime()
      val handled: IO[Response[IO]] = routes
        .run(req)
        .getOrElseF(toResponse(404, ErrorResponse.of("NotFoundError", "Ruta no encontrada.").asJson))
        .handleErrorWith {
          case de: DomainException =>
            toResponse(de.httpStatus, ErrorResponse.of(de.errorType, de.message).asJson)
          case ex: Throwable =>
            toResponse(500, ErrorResponse.of("InternalServerError", "Ocurrió un error interno en el servidor.").asJson)
        }

      handled.flatTap { resp =>
        IO {
          val durationNanos = System.nanoTime() - startNanos
          val method = req.method.name
          val route = normalizeRoute(req.uri.path.renderString)
          val statusCode = resp.status.code.toString

          Counter
            .builder("chivas_rumberas_http_requests")
            .description("Total de solicitudes HTTP recibidas.")
            .tag("method", method)
            .tag("route", route)
            .tag("status_code", statusCode)
            .register(registry)
            .increment()

          Timer
            .builder("chivas_rumberas_http_request_duration")
            .description("Duración de las solicitudes HTTP en segundos.")
            .tag("method", method)
            .tag("route", route)
            .serviceLevelObjectives(
              Duration.ofMillis(5),
              Duration.ofMillis(10),
              Duration.ofMillis(50),
              Duration.ofMillis(100),
              Duration.ofMillis(500),
              Duration.ofSeconds(1),
              Duration.ofSeconds(2),
              Duration.ofSeconds(5)
            )
            .register(registry)
            .record(durationNanos, TimeUnit.NANOSECONDS)
        }
      }
    }
