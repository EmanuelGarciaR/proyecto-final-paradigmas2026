package com.chivasrumberas

import cats.effect.IO
import com.chivasrumberas.application.ChivasBusinessService
import com.chivasrumberas.domain.*
import com.chivasrumberas.domain.Domain.*
import com.chivasrumberas.infrastructure.*
import com.chivasrumberas.web.{HttpAppBuilder, ObservabilityRegistry, SecurityAndRateLimiter}
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.*
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.typelevel.ci.CIString

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable

class ContractAndFunctionalSuite extends CatsEffectSuite:

  final case class TestRes(status: Int, body: String)

  private var httpApp: HttpApp[IO] = scala.compiletime.uninitialized
  private var adminToken: String = ""
  private var operatorToken: String = ""

  private val chivasStore = mutable.LinkedHashMap.empty[String, Chiva]
  private val travelsStore = mutable.LinkedHashMap.empty[String, Travel]
  private val customersByEmail = mutable.LinkedHashMap.empty[String, Customer]
  private val bookingsStore = mutable.LinkedHashMap.empty[String, Booking]
  private val usersByEmail = mutable.LinkedHashMap.empty[String, User]

  private var forceCalendarFailure = false
  private var calendarCreateCalls = 0
  private var lastDeletedCalendarEventId: Option[String] = None

  override def beforeEach(context: BeforeEach): Unit =
    chivasStore.clear()
    travelsStore.clear()
    customersByEmail.clear()
    bookingsStore.clear()
    usersByEmail.clear()
    forceCalendarFailure = false
    calendarCreateCalls = 0
    lastDeletedCalendarEventId = None

    val jwtService = new HmacJwtService("test_secret_key_for_scala_contract_tests_2026", "8h")
    adminToken = jwtService.sign(JwtPayload("admin-1", "admin@chivasrumberas.com", "admin"))
    operatorToken = jwtService.sign(JwtPayload("op-1", "operador@chivasrumberas.com", "operator"))

    val encoder = new BCryptPasswordEncoder()
    usersByEmail.put(
      "admin@chivasrumberas.com",
      User(
        "admin-1",
        "admin@chivasrumberas.com",
        s"$$2b$$10$$${encoder.encode("Admin123*Chiva").substring(7)}",
        "admin",
        Instant.now()
      )
    )

    chivasStore.put(
      "la-coqueta-1954",
      Chiva(
        "la-coqueta-1954",
        "La Coqueta",
        "Chiva tradicional paisa",
        50,
        List("https://images.unsplash.com/photo-1544620347-c4fd4a3d5957"),
        280000,
        "available",
        List("Sonido 4000W"),
        Instant.parse("2026-11-01T00:00:00Z"),
        Instant.parse("2026-11-01T00:00:00Z")
      )
    )
    chivasStore.put(
      "la-parrandera-1962",
      Chiva(
        "la-parrandera-1962",
        "La Parrandera",
        "Chiva rumbera",
        40,
        Nil,
        260000,
        "available",
        Nil,
        Instant.parse("2026-11-01T00:01:00Z"),
        Instant.parse("2026-11-01T00:01:00Z")
      )
    )

    travelsStore.put(
      "trv_alumbrados",
      Travel("trv_alumbrados", "Ruta Navideña y Alumbrados", "Recorrido navideño", 4, List("Río Medellín"), true)
    )

    val chivaRepo = new ChivaRepository:
      override def findChivaAll(status: Option[String]): List[Chiva] =
        chivasStore.values.filter(c => status.forall(s => s.isEmpty || c.status == s)).toList
      override def findChivaById(id: String): Option[Chiva] = chivasStore.get(id)
      override def saveChiva(chiva: Chiva): Chiva =
        chivasStore.put(chiva.id, chiva)
        chiva
      override def updateChivaStatus(id: String, status: String): Chiva =
        val c = chivasStore(id)
        val updated = c.copy(status = status, updatedAt = Instant.now())
        chivasStore.put(id, updated)
        updated
      override def updateChiva(id: String, updates: Map[String, Any]): Chiva =
        val c = chivasStore(id)
        val updated = c.copy(
          name = updates.get("name").map(_.toString).getOrElse(c.name),
          description = updates.get("description").map(_.toString).getOrElse(c.description),
          capacity = updates.get("capacity").map(_.asInstanceOf[Int]).getOrElse(c.capacity),
          pricePerHour = updates.get("pricePerHour").map(_.asInstanceOf[Double]).getOrElse(c.pricePerHour),
          status = updates.get("status").map(_.toString).getOrElse(c.status),
          features = updates.get("features").map(_.asInstanceOf[List[String]]).getOrElse(c.features),
          imageUrls = updates.get("imageUrls").map(_.asInstanceOf[List[String]]).getOrElse(c.imageUrls),
          updatedAt = Instant.now()
        )
        chivasStore.put(id, updated)
        updated

    val travelRepo = new TravelRepository:
      override def findAllTravelsActive(): List[Travel] = travelsStore.values.filter(_.isActive).toList
      override def findTravelById(id: String): Option[Travel] = travelsStore.get(id)

    val customerRepo = new CustomerRepository:
      override def findByEmail(email: String): Option[Customer] = customersByEmail.get(email.trim.toLowerCase)
      override def findById(id: String): Option[Customer] = customersByEmail.values.find(_.id == id)
      override def upsertByEmail(name: String, email: String, phone: String): Customer =
        val norm = email.trim.toLowerCase
        customersByEmail.get(norm) match
          case Some(existing) =>
            val updated = existing.copy(name = name.trim, phone = phone.trim)
            customersByEmail.put(norm, updated)
            updated
          case None =>
            val created = Customer(s"cust-${customersByEmail.size + 1}", name.trim, norm, phone.trim, Instant.now())
            customersByEmail.put(norm, created)
            created

    val bookingRepo = new BookingRepository:
      override def findBookingById(id: String): Option[Booking] = bookingsStore.get(id)
      override def findBookingPaginated(query: PaginatedBookingsQuery): PaginatedBookingsResult =
        val filtered = bookingsStore.values.toList
          .filter(b => query.status.forall(_ == b.status))
          .filter { b =>
            query.q.forall { qStr =>
              val q = qStr.toLowerCase
              b.id.toLowerCase.contains(q) || b.customerName.toLowerCase.contains(q) || b.customerEmail.toLowerCase.contains(q)
            }
          }
        val skip = (query.page - 1) * query.limit
        PaginatedBookingsResult(filtered.slice(skip, skip + query.limit), filtered.size)

      override def findConfirmedByDateRange(startDate: Instant, endDate: Instant): List[Booking] =
        bookingsStore.values.toList
          .filter(_.status == "confirmed")
          .filter(b => !b.eventDate.isBefore(startDate) && b.eventDate.isBefore(endDate))

      override def findOccupiedSlotsByChivaAndDate(chivaId: String, date: Instant): List[OccupiedSlotRecord] =
        val targetDate = Domain.toUtcDateString(date)
        bookingsStore.values.toList
          .filter(_.chivaId == chivaId)
          .filter(b => Set("pending", "confirmed").contains(b.status))
          .filter(b => Domain.toUtcDateString(b.eventDate) == targetDate)
          .map(b => OccupiedSlotRecord(b.startTime, b.durationHours))

      override def hasConflict(chivaId: String, eventDate: Instant, startTime: String, durationHours: Double): Boolean =
        val occupied = findOccupiedSlotsByChivaAndDate(chivaId, eventDate)
        val newStart = TimeSlot.toMinutes(startTime)
        val newEnd = newStart + durationHours * 60.0
        occupied.exists { s =>
          val sStart = TimeSlot.toMinutes(s.startTime)
          val sEnd = sStart + s.durationHours * 60.0
          newStart < sEnd && sStart < newEnd
        }

      override def hasFutureActiveBookingsByChiva(chivaId: String, fromDate: Instant): Boolean =
        bookingsStore.values
          .filter(_.chivaId == chivaId)
          .filter(b => Set("pending", "confirmed").contains(b.status))
          .exists(b => !b.eventDate.isBefore(fromDate.truncatedTo(ChronoUnit.DAYS)))

      override def saveBooking(booking: Booking): Booking =
        bookingsStore.put(booking.id, booking)
        booking

      override def updateBookingStatus(id: String, status: String, totalPrice: Option[Double]): Booking =
        val b = bookingsStore(id)
        val updated = b.copy(status = status, totalPrice = totalPrice.getOrElse(b.totalPrice), updatedAt = Instant.now())
        bookingsStore.put(id, updated)
        updated

      override def updateBookingCalendarSync(
          id: String,
          googleEventId: Option[String],
          updateGoogleEventId: Boolean,
          calendarSyncStatus: String
      ): Booking =
        val b = bookingsStore(id)
        val updated = b.copy(
          googleEventId = if updateGoogleEventId then googleEventId else b.googleEventId,
          calendarSyncStatus = calendarSyncStatus,
          updatedAt = Instant.now()
        )
        bookingsStore.put(id, updated)
        updated

    val userRepo = new UserRepository:
      override def findUserByEmail(email: String): Option[User] = usersByEmail.get(email)
      override def findUserById(id: String): Option[User] = usersByEmail.values.find(_.id == id)

    val calendarService = new CalendarPort:
      override def createEvent(input: CalendarEventInput): String =
        calendarCreateCalls += 1
        if forceCalendarFailure then throw new RuntimeException("Google API 503 Unavailable")
        s"gcal-evt-${input.bookingId}"
      override def deleteEvent(googleEventId: String): Unit =
        lastDeletedCalendarEventId = Some(googleEventId)

    val notificationService = new ConfigurableNotificationService(0L)
    val imageStorage = new CloudinaryImageStorageService("demo-cloud", "12345", "secret-xyz")

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

    val startupRef = new AtomicReference[java.lang.Double](0.125)
    val registry = ObservabilityRegistry.create(startupRef)
    val security = new SecurityAndRateLimiter(jwtService, false)
    httpApp = HttpAppBuilder.buildHttpApp(businessService, security, registry)

  private def sendReq(
      method: Method,
      pathAndQuery: String,
      token: Option[String] = None,
      bodyJson: Option[String] = None
  ): IO[TestRes] =
    val uri = Uri.unsafeFromString(pathAndQuery)
    var req = Request[IO](method = method, uri = uri)
    token.foreach(t => req = req.putHeaders(Header.Raw(CIString("Authorization"), s"Bearer $t")))
    bodyJson.foreach(b => req = req.withEntity(b))
    for
      resp <- httpApp.run(req)
      body <- resp.bodyText.compile.string
    yield TestRes(resp.status.code, body)

  private def sampleBooking(
      id: String,
      chivaId: String,
      chivaName: String,
      status: String,
      eventDateIso: String,
      startTime: String,
      durationHours: Double
  ): Booking =
    Booking(
      id = id,
      chivaId = chivaId,
      chivaName = Some(chivaName),
      customerId = Some("cust-1"),
      customerName = "Carolina Montoya",
      customerEmail = "carolina@example.com",
      customerPhone = "3001234567",
      guestCount = 30,
      travel = "trv_alumbrados",
      travelName = Some("Ruta Navideña y Alumbrados"),
      eventDate = Instant.parse(eventDateIso),
      startTime = startTime,
      durationHours = durationHours,
      totalPrice = 1120000,
      status = status,
      notes = Some("Cumpleaños"),
      googleEventId = None,
      calendarSyncStatus = "pending",
      createdAt = Instant.parse("2026-11-01T15:00:00Z"),
      updatedAt = Instant.parse("2026-11-01T15:00:00Z")
    )

  test("1. GET /health y GET /metrics exponen estado y métricas Prometheus") {
    for
      hRes <- sendReq(Method.GET, "/health")
      mRes <- sendReq(Method.GET, "/metrics")
    yield
      assertEquals(hRes.status, 200)
      assert(hRes.body.contains("\"status\":\"ok\""))
      assertEquals(mRes.status, 200)
      assert(mRes.body.contains("chivas_rumberas_http_requests_total"))
      assert(mRes.body.contains("chivas_rumberas_http_request_duration_seconds_bucket"))
      assert(mRes.body.contains("chivas_rumberas_startup_duration_seconds"))
  }

  test("2. POST /auth/login autentica con BCrypt y rechaza credenciales inválidas") {
    for
      okRes <- sendReq(
        Method.POST,
        "/auth/login",
        bodyJson = Some("""{"email":"admin@chivasrumberas.com","password":"Admin123*Chiva"}""")
      )
      badRes <- sendReq(
        Method.POST,
        "/auth/login",
        bodyJson = Some("""{"email":"admin@chivasrumberas.com","password":"Wrong"}""")
      )
    yield
      assertEquals(okRes.status, 200)
      assert(okRes.body.contains("\"accessToken\""))
      assertEquals(badRes.status, 401)
      assert(badRes.body.contains("InvalidCredentialsError"))
  }

  test("3. GET /bookings paginación y validación 400") {
    bookingsStore.put("CR-AAAA22", sampleBooking("CR-AAAA22", "la-coqueta-1954", "La Coqueta", "confirmed", "2026-11-20T00:00:00Z", "18:00", 4))
    bookingsStore.put("CR-BBBB33", sampleBooking("CR-BBBB33", "la-coqueta-1954", "La Coqueta", "pending", "2026-11-21T00:00:00Z", "18:00", 4))

    for
      r1 <- sendReq(Method.GET, "/bookings", token = Some(adminToken))
      r2 <- sendReq(Method.GET, "/bookings?page=0", token = Some(adminToken))
      r3 <- sendReq(Method.GET, "/bookings?limit=51", token = Some(adminToken))
      r4 <- sendReq(Method.GET, "/bookings?status=invalid", token = Some(adminToken))
    yield
      assertEquals(r1.status, 200)
      assert(r1.body.contains("\"total\":2"))
      assertEquals(r2.status, 400)
      assertEquals(r3.status, 400)
      assertEquals(r4.status, 400)
  }

  test("4. POST /bookings reutiliza Customer por email y detecta conflicto 409") {
    val futureDate = Domain.toUtcDateString(Instant.now().plus(5, ChronoUnit.DAYS))
    val req1 =
      s"""{"chivaId":"la-coqueta-1954","travelId":"trv_alumbrados","customerName":"Carolina Montoya","customerEmail":"Carolina.Montoya@Example.com","customerPhone":"3001112233","guestCount":25,"eventDate":"$futureDate","startTime":"18:00","durationHours":3}"""
    val req2 =
      s"""{"chivaId":"la-coqueta-1954","travelId":"trv_alumbrados","customerName":"Carolina Montoya Actualizada","customerEmail":"carolina.montoya@example.com","customerPhone":"3009998877","guestCount":30,"eventDate":"$futureDate","startTime":"12:00","durationHours":3}"""

    for
      r1 <- sendReq(Method.POST, "/bookings", bodyJson = Some(req1))
      rConflict <- sendReq(Method.POST, "/bookings", bodyJson = Some(req1))
      r2 <- sendReq(Method.POST, "/bookings", bodyJson = Some(req2))
    yield
      assertEquals(r1.status, 201)
      assert(r1.body.contains("\"customerEmail\":\"carolina.montoya@example.com\""))
      assertEquals(rConflict.status, 409)
      assert(rConflict.body.contains("BookingConflictError"))
      assertEquals(r2.status, 201)
      assertEquals(customersByEmail.size, 1)
      assertEquals(customersByEmail("carolina.montoya@example.com").name, "Carolina Montoya Actualizada")
  }

  test("5. GET /bookings/track es case-insensitive y responde 404 genérico") {
    bookingsStore.put("CR-8P2Q9M", sampleBooking("CR-8P2Q9M", "la-coqueta-1954", "La Coqueta", "confirmed", "2026-11-20T00:00:00Z", "18:00", 4))

    for
      okRes <- sendReq(Method.GET, "/bookings/track?code=cr-8p2q9m&email=CAROLINA@example.com")
      m1Res <- sendReq(Method.GET, "/bookings/track?code=CR-NOEXST&email=carolina@example.com")
      m2Res <- sendReq(Method.GET, "/bookings/track?code=CR-8P2Q9M&email=otro@example.com")
    yield
      assertEquals(okRes.status, 200)
      assert(okRes.body.contains("\"endTime\":\"22:00\""))
      assert(!okRes.body.contains("customerEmail"))
      assertEquals(m1Res.status, 404)
      assertEquals(m2Res.status, 404)
      assertEquals(m1Res.body, m2Res.body)
  }

  test("6. Google Calendar sync, transiciones de estado y actualización de precio sugerido") {
    bookingsStore.put("CR-8P2Q9M", sampleBooking("CR-8P2Q9M", "la-coqueta-1954", "La Coqueta", "pending", "2026-11-20T00:00:00Z", "18:00", 4))

    for
      cRes <- sendReq(
        Method.PATCH,
        "/bookings/CR-8P2Q9M/status",
        token = Some(adminToken),
        bodyJson = Some("""{"status":"confirmed","totalPrice":950000}""")
      )
      syncRes <- sendReq(Method.POST, "/bookings/CR-8P2Q9M/calendar-sync", token = Some(adminToken))
      canRes <- sendReq(
        Method.PATCH,
        "/bookings/CR-8P2Q9M/status",
        token = Some(adminToken),
        bodyJson = Some("""{"status":"cancelled"}""")
      )
      invRes <- sendReq(
        Method.PATCH,
        "/bookings/CR-8P2Q9M/status",
        token = Some(adminToken),
        bodyJson = Some("""{"status":"confirmed"}""")
      )
    yield
      assertEquals(cRes.status, 200)
      assert(cRes.body.contains("\"calendarSyncStatus\":\"synced\""))
      assert(cRes.body.contains("950000"))
      assertEquals(syncRes.status, 200)
      assertEquals(calendarCreateCalls, 1)
      assertEquals(canRes.status, 200)
      assertEquals(lastDeletedCalendarEventId, Some("gcal-evt-CR-8P2Q9M"))
      assertEquals(invRes.status, 422)
      assert(invRes.body.contains("InvalidBookingStatusTransitionError"))
  }

  test("7. Permisos 401 y 403 en endpoints de administración") {
    for
      r401 <- sendReq(Method.GET, "/bookings")
      r403 <- sendReq(Method.GET, "/bookings", token = Some(operatorToken))
      c401 <- sendReq(Method.GET, "/bookings/calendar?month=2026-11")
      c403 <- sendReq(Method.GET, "/bookings/calendar?month=2026-11", token = Some(operatorToken))
    yield
      assertEquals(r401.status, 401)
      assert(r401.body.contains("InvalidTokenError"))
      assertEquals(r403.status, 403)
      assert(r403.body.contains("UnauthorizedError"))
      assertEquals(c401.status, 401)
      assertEquals(c403.status, 403)
  }

  test("8. Programación Funcional en GET /bookings/calendar (groupBy por fecha y chiva, endsNextDay)") {
    bookingsStore.put("CR-CONF01", sampleBooking("CR-CONF01", "la-coqueta-1954", "La Coqueta", "confirmed", "2026-11-20T00:00:00Z", "22:00", 3))
    bookingsStore.put("CR-CONF02", sampleBooking("CR-CONF02", "la-parrandera-1962", "La Parrandera", "confirmed", "2026-11-20T00:00:00Z", "18:00", 3))
    bookingsStore.put("CR-PEND01", sampleBooking("CR-PEND01", "la-coqueta-1954", "La Coqueta", "pending", "2026-11-22T00:00:00Z", "18:00", 3))

    for
      res <- sendReq(Method.GET, "/bookings/calendar?month=2026-11", token = Some(adminToken))
    yield
      assertEquals(res.status, 200)
      val json = parse(res.body).toOption.get
      val cursor = json.hcursor
      assertEquals(cursor.downField("month").as[String], Right("2026-11"))
      assertEquals(cursor.downField("days").downArray.downField("date").as[String], Right("2026-11-20"))
      assertEquals(cursor.downField("days").downArray.downField("chivasCount").as[Int], Right(2))
      assert(res.body.contains("\"endsNextDay\":true"))
  }

  test("9. Reducción funcional en GET /bookings/availability y DELETE /chivas/:id con conflicto 409") {
    val futureDate = Domain.toUtcDateString(Instant.now().plus(10, ChronoUnit.DAYS))
    bookingsStore.put("CR-FULL01", sampleBooking("CR-FULL01", "la-coqueta-1954", "La Coqueta", "confirmed", s"${futureDate}T00:00:00Z", "08:00", 8))
    bookingsStore.put("CR-FULL02", sampleBooking("CR-FULL02", "la-coqueta-1954", "La Coqueta", "confirmed", s"${futureDate}T00:00:00Z", "16:00", 6))

    for
      avRes <- sendReq(Method.GET, s"/bookings/availability?chivaId=la-coqueta-1954&date=$futureDate")
      delConflict <- sendReq(Method.DELETE, "/chivas/la-coqueta-1954", token = Some(adminToken))
      delOk <- sendReq(Method.DELETE, "/chivas/la-parrandera-1962", token = Some(adminToken))
    yield
      assertEquals(avRes.status, 200)
      assert(avRes.body.contains("\"isFullyBooked\":true"))
      assertEquals(delConflict.status, 409)
      assert(delConflict.body.contains("ChivaHasActiveBookingsError"))
      assertEquals(delOk.status, 200)
      assert(delOk.body.contains("\"status\":\"retired\""))
  }

  test("10. hasBookingDateTimePassed evalúa correctamente la hora de Colombia (UTC-5)") {
    val evDate = Instant.parse("2026-11-15T00:00:00.000Z")
    assert(!Domain.hasBookingDateTimePassed(evDate, "18:00", Instant.parse("2026-11-15T22:59:00Z")))
    assert(Domain.hasBookingDateTimePassed(evDate, "18:00", Instant.parse("2026-11-15T23:00:00Z")))
    assert(Domain.hasBookingDateTimePassed(evDate, "18:00", Instant.parse("2026-11-16T00:30:00Z")))
  }

  test("11. CRUD de Chivas, colisión de slug, Travels y firma de Cloudinary") {
    for
      cRes <- sendReq(Method.GET, "/chivas?status=available")
      tRes <- sendReq(Method.GET, "/travels")
      crRes <- sendReq(
        Method.POST,
        "/chivas",
        token = Some(adminToken),
        bodyJson = Some("""{"name":"La Coqueta 1954","description":"Nueva","capacity":40,"pricePerHour":300000}""")
      )
      upRes <- sendReq(
        Method.PATCH,
        "/chivas/la-coqueta-1954-2",
        token = Some(adminToken),
        bodyJson = Some("""{"pricePerHour":350000,"capacity":45}""")
      )
      stRes <- sendReq(
        Method.PATCH,
        "/chivas/la-coqueta-1954-2/status",
        token = Some(adminToken),
        bodyJson = Some("""{"status":"maintenance"}""")
      )
      sigRes <- sendReq(Method.GET, "/uploads/signature", token = Some(adminToken))
    yield
      assertEquals(cRes.status, 200)
      assertEquals(tRes.status, 200)
      assertEquals(crRes.status, 201)
      assert(crRes.body.contains("\"id\":\"la-coqueta-1954-2\""))
      assertEquals(upRes.status, 200)
      assert(upRes.body.contains("350000"))
      assertEquals(stRes.status, 200)
      assert(stRes.body.contains("\"status\":\"maintenance\""))
      assertEquals(sigRes.status, 200)
      assert(sigRes.body.contains("\"cloudName\":\"demo-cloud\""))
  }
