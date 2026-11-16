package com.chivasrumberas.application

import com.chivasrumberas.domain.*
import com.chivasrumberas.domain.Domain.*
import com.chivasrumberas.infrastructure.*
import com.chivasrumberas.web.ApiDtos.*
import io.circe.Json
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

import java.net.URI
import java.time.{Instant, LocalDate, ZoneOffset, ZonedDateTime}
import scala.annotation.tailrec
import scala.util.Try
import scala.util.matching.Regex

class ChivasBusinessService(
    chivaRepository: ChivaRepository,
    travelRepository: TravelRepository,
    customerRepository: CustomerRepository,
    bookingRepository: BookingRepository,
    userRepository: UserRepository,
    jwtService: JwtTokenService,
    notificationService: NotificationPort,
    calendarService: CalendarPort,
    imageStorageService: ImageStoragePort
):
  import ChivasBusinessService.*

  private val passwordEncoder = new BCryptPasswordEncoder()

  // ── 1. Auth ────────────────────────────────────────────────────────────────
  def login(dto: LoginDto): AuthResponseDto =
    val email = dto.email.getOrElse(throw DomainException.invalidCredentials())
    val password = dto.password.getOrElse(throw DomainException.invalidCredentials())
    val user = userRepository.findUserByEmail(email).getOrElse(throw DomainException.invalidCredentials())

    val normalizedHash =
      if user.passwordHash.startsWith("$2b$") then s"$$2a$$${user.passwordHash.substring(4)}"
      else user.passwordHash

    if !passwordEncoder.matches(password, normalizedHash) then
      throw DomainException.invalidCredentials()

    val token = jwtService.sign(JwtPayload(user.id, user.email, user.role))
    AuthResponseDto(token, AuthUserDto(user.id, user.email, user.role))

  // ── 2. Chivas ──────────────────────────────────────────────────────────────
  private val toChivaDto: Chiva => ChivaResponseDto = chiva =>
    ChivaResponseDto(
      id = chiva.id,
      name = chiva.name,
      description = chiva.description,
      capacity = chiva.capacity,
      imageUrls = chiva.imageUrls,
      pricePerHour = chiva.pricePerHour,
      status = chiva.status,
      features = chiva.features
    )

  def getChivas(status: Option[String]): List[ChivaResponseDto] =
    chivaRepository.findChivaAll(status).map(toChivaDto)

  def getChivaById(id: String): ChivaResponseDto =
    chivaRepository
      .findChivaById(id)
      .map(toChivaDto)
      .getOrElse(throw DomainException.chivaNotFound(id))

  def createChiva(body: Json): ChivaResponseDto =
    val obj = body.asObject.getOrElse(throw DomainException.validation("Datos de chiva inválidos."))
    val name = obj("name").flatMap(_.asString).map(_.trim).filter(_.nonEmpty).getOrElse(
      throw DomainException.validation("El nombre es requerido.")
    )
    val description = obj("description").flatMap(_.asString).map(_.trim).filter(_.nonEmpty).getOrElse(
      throw DomainException.validation("La descripción es requerida.")
    )
    val capacity = parsePositiveInt(obj("capacity"), "capacity debe ser un número entero mayor que cero.")
    val pricePerHour = parsePositiveDouble(obj("pricePerHour"), "pricePerHour debe ser mayor que cero.")

    val status = obj("status").filter(!_.isNull) match
      case None => "available"
      case Some(j) =>
        val st = j.asString.getOrElse(throw DomainException.validation("El estado de la chiva no es válido."))
        if !ChivaStatuses.contains(st) then throw DomainException.validation("El estado de la chiva no es válido.")
        st

    val features: List[String] = obj("features").filter(!_.isNull) match
      case None => Nil
      case Some(j) =>
        val arr = j.asArray.getOrElse(throw DomainException.validation("features debe ser un arreglo de textos."))
        arr.toList.map { item =>
          item.asString.map(_.trim).filter(_.nonEmpty).getOrElse(
            throw DomainException.validation("Cada elemento de features debe ser un texto no vacío.")
          )
        }

    val imageUrls: List[String] = obj("imageUrls").filter(!_.isNull) match
      case None => Nil
      case Some(j) =>
        val arr = j.asArray.getOrElse(throw DomainException.validation("imageUrls debe ser un arreglo."))
        if arr.size > 10 then throw DomainException.validation("Máximo 10 fotos permitidas por chiva.")
        arr.toList.map { item =>
          val url = item.asString.getOrElse(throw DomainException.validation("Cada elemento de imageUrls debe ser una URL válida."))
          if !isValidHttpUrl(url) then throw DomainException.validation("Cada elemento de imageUrls debe ser una URL válida.")
          url
        }

    val baseSlug = Domain.slugify(name)
    if baseSlug.isEmpty then
      throw DomainException.validation("El nombre proporcionado no genera un identificador válido.")

    @tailrec
    def findUniqueSlug(candidate: String, suffix: Int): String =
      if chivaRepository.findChivaById(candidate).isEmpty then candidate
      else findUniqueSlug(s"$baseSlug-$suffix", suffix + 1)

    val uniqueId = findUniqueSlug(baseSlug, 2)
    val now = Instant.now()
    val created = chivaRepository.saveChiva(
      Chiva(
        id = uniqueId,
        name = name,
        description = description,
        capacity = capacity,
        imageUrls = imageUrls,
        pricePerHour = pricePerHour,
        status = status,
        features = features,
        createdAt = now,
        updatedAt = now
      )
    )
    toChivaDto(created)

  def updateChiva(id: String, body: Json): ChivaResponseDto =
    val obj = body.asObject.getOrElse(throw DomainException.validation("Debe proporcionar al menos un campo para actualizar."))

    val updatesBuilder = Map.newBuilder[String, Any]

    obj("name").filter(!_.isNull).foreach { j =>
      val s = j.asString.map(_.trim).filter(_.nonEmpty).getOrElse(
        throw DomainException.validation("El nombre no puede estar vacío.")
      )
      updatesBuilder += ("name" -> s)
    }

    obj("description").filter(!_.isNull).foreach { j =>
      val s = j.asString.map(_.trim).filter(_.nonEmpty).getOrElse(
        throw DomainException.validation("La descripción no puede estar vacía.")
      )
      updatesBuilder += ("description" -> s)
    }

    obj("capacity").filter(!_.isNull).foreach { j =>
      updatesBuilder += ("capacity" -> parsePositiveInt(Some(j), "capacity debe ser un número entero."))
    }

    obj("pricePerHour").filter(!_.isNull).foreach { j =>
      updatesBuilder += ("pricePerHour" -> parsePositiveDouble(Some(j), "pricePerHour debe ser mayor que cero."))
    }

    obj("status").filter(!_.isNull).foreach { j =>
      val st = j.asString.filter(ChivaStatuses.contains).getOrElse(
        throw DomainException.validation("El estado de la chiva no es válido.")
      )
      updatesBuilder += ("status" -> st)
    }

    obj("features").filter(!_.isNull).foreach { j =>
      val arr = j.asArray.getOrElse(throw DomainException.validation("features debe ser un arreglo."))
      val list = arr.toList.map(item =>
        item.asString.map(_.trim).filter(_.nonEmpty).getOrElse(throw DomainException.validation("features inválido."))
      )
      updatesBuilder += ("features" -> list)
    }

    obj("imageUrls").filter(!_.isNull).foreach { j =>
      val arr = j.asArray.getOrElse(throw DomainException.validation("Máximo 10 fotos permitidas por chiva."))
      if arr.size > 10 then throw DomainException.validation("Máximo 10 fotos permitidas por chiva.")
      val urls = arr.toList.map { item =>
        val u = item.asString.getOrElse(throw DomainException.validation("Cada elemento de imageUrls debe ser una URL válida."))
        if !isValidHttpUrl(u) then throw DomainException.validation("Cada elemento de imageUrls debe ser una URL válida.")
        u
      }
      updatesBuilder += ("imageUrls" -> urls)
    }

    val updates = updatesBuilder.result()
    if updates.isEmpty then
      throw DomainException.validation("Debe proporcionar al menos un campo para actualizar.")

    chivaRepository.findChivaById(id).getOrElse(throw DomainException.chivaNotFound(id))
    toChivaDto(chivaRepository.updateChiva(id, updates))

  def updateChivaStatus(id: String, body: Json): ChivaResponseDto =
    if id == null || id.trim.isEmpty then throw DomainException.validation("El id de la chiva es requerido.")
    val obj = body.asObject.getOrElse(throw DomainException.validation("El campo status es requerido."))
    val rawStatus = obj("status").filter(!_.isNull).getOrElse(
      throw DomainException.validation("El campo status es requerido.")
    )
    val statusStr = rawStatus.asString.getOrElse(
      throw DomainException.validation("El estado de la chiva no es válido.")
    )
    if statusStr.isEmpty then throw DomainException.validation("El campo status es requerido.")
    if !ChivaStatuses.contains(statusStr) then
      throw DomainException.validation("El estado de la chiva no es válido.")

    chivaRepository.findChivaById(id).getOrElse(throw DomainException.chivaNotFound(id))
    toChivaDto(chivaRepository.updateChivaStatus(id, statusStr))

  def deleteChiva(id: String): ChivaResponseDto =
    if id == null || id.trim.isEmpty then throw DomainException.validation("El id de la chiva es requerido.")
    chivaRepository.findChivaById(id).getOrElse(throw DomainException.chivaNotFound(id))
    if bookingRepository.hasFutureActiveBookingsByChiva(id, Instant.now()) then
      throw DomainException.chivaHasActiveBookings(id)
    toChivaDto(chivaRepository.updateChivaStatus(id, "retired"))

  // ── 3. Travels ─────────────────────────────────────────────────────────────
  def getTravels(): List[TravelResponseDto] =
    travelRepository.findAllTravelsActive().map { t =>
      TravelResponseDto(t.id, t.name, t.description, t.durationHours, t.highlights)
    }

  // ── 4. Bookings & Functional Programming Pipelines ─────────────────────────
  private def toBookingResponseDto(b: Booking): BookingResponseDto =
    val chivaName = b.chivaName.getOrElse(
      chivaRepository.findChivaById(b.chivaId).map(_.name).getOrElse("")
    )
    val travelName = b.travelName.getOrElse(
      travelRepository.findTravelById(b.travel).map(_.name).getOrElse(b.travel)
    )
    val ts = TimeSlot.create(b.startTime, b.durationHours)
    BookingResponseDto(
      id = b.id,
      chivaId = b.chivaId,
      chivaName = chivaName,
      travelId = b.travel,
      travelName = travelName,
      customerName = b.customerName,
      customerEmail = b.customerEmail,
      customerPhone = b.customerPhone,
      guestCount = b.guestCount,
      eventDate = Domain.toUtcDateString(b.eventDate),
      startTime = b.startTime,
      endTime = ts.getEndTime,
      durationHours = b.durationHours,
      totalPrice = b.totalPrice,
      status = b.status,
      notes = b.notes,
      calendarSyncStatus = b.calendarSyncStatus,
      createdAt = Domain.toIsoString(b.createdAt)
    )

  /**
   * Programación Funcional: map + foldLeft para proyectar slots ocupados y reducir
   * los minutos reservados dentro de la ventana operativa (08:00 a 22:00 = 840 min).
   */
  def getAvailability(chivaIdOpt: Option[String], dateOpt: Option[String]): AvailabilityResponseDto =
    val chivaId = chivaIdOpt.map(_.trim).filter(_.nonEmpty).getOrElse(
      throw DomainException.validation("chivaId y date son requeridos.")
    )
    val dateStr = dateOpt.map(_.trim).filter(_.nonEmpty).getOrElse(
      throw DomainException.validation("chivaId y date son requeridos.")
    )

    chivaRepository.findChivaById(chivaId).getOrElse(throw DomainException.chivaNotFound(chivaId))

    val dateInstant = parseLooseIsoDate(dateStr)
    val rawSlots = bookingRepository.findOccupiedSlotsByChivaAndDate(chivaId, dateInstant)

    val occupiedSlots = rawSlots.map { slot =>
      val ts = TimeSlot.create(slot.startTime, slot.durationHours)
      OccupiedSlotDto(ts.startTime, ts.getEndTime, ts.durationHours)
    }

    val totalOperationalMinutes = OperationalEndMinutes - OperationalStartMinutes
    val bookedMinutes = occupiedSlots.foldLeft(0.0) { (acc, slot) =>
      val slotStart = TimeSlot.toMinutes(slot.startTime).toDouble
      val slotEnd = slotStart + slot.durationHours * 60.0
      val start = math.max(slotStart, OperationalStartMinutes)
      val end = math.min(slotEnd, OperationalEndMinutes)
      acc + math.max(0.0, end - start)
    }

    AvailabilityResponseDto(
      chivaId = chivaId,
      date = dateStr,
      occupiedSlots = occupiedSlots,
      isFullyBooked = bookedMinutes >= totalOperationalMinutes
    )

  def trackBooking(codeOpt: Option[String], emailOpt: Option[String]): TrackBookingResponseDto =
    val rawCode = codeOpt.map(_.trim).filter(_.nonEmpty).getOrElse(
      throw DomainException.validation("El parámetro code es requerido.")
    )
    val rawEmail = emailOpt.map(_.trim).filter(_.nonEmpty).getOrElse(
      throw DomainException.validation("El parámetro email es requerido.")
    )
    val email = rawEmail.toLowerCase
    if !EmailRegex.matches(email) then
      throw DomainException.validation("El parámetro email debe ser un correo válido.")
    val code = rawCode.toUpperCase

    val booking = bookingRepository
      .findBookingById(code)
      .filter(_.customerEmail.trim.toLowerCase == email)
      .getOrElse(throw DomainException.bookingNotFound(None))

    val chivaName = booking.chivaName.getOrElse(
      chivaRepository.findChivaById(booking.chivaId).map(_.name).getOrElse("")
    )
    val travelName = booking.travelName.getOrElse(
      travelRepository.findTravelById(booking.travel).map(_.name).getOrElse("")
    )
    val ts = TimeSlot.create(booking.startTime, booking.durationHours)

    TrackBookingResponseDto(
      id = booking.id,
      code = booking.id,
      status = booking.status,
      createdAt = Domain.toIsoString(booking.createdAt),
      chivaId = booking.chivaId,
      chivaName = chivaName,
      travelId = booking.travel,
      travelName = travelName,
      eventDate = Domain.toUtcDateString(booking.eventDate),
      startTime = booking.startTime,
      endTime = ts.getEndTime,
      durationHours = booking.durationHours,
      guestCount = booking.guestCount,
      totalPrice = booking.totalPrice
    )

  def createBooking(dto: CreateBookingDto): BookingResponseDto =
    val chivaId = dto.chivaId.getOrElse("")
    val chiva = chivaRepository.findChivaById(chivaId).getOrElse(throw DomainException.chivaNotFound(chivaId))
    if chiva.status != "available" then throw DomainException.chivaNotAvailable(chivaId)

    val travelId = dto.travelId.getOrElse("")
    val travel = travelRepository.findTravelById(travelId).getOrElse(throw DomainException.travelNotFound(travelId))

    val durationHours = dto.durationHours.getOrElse(0.0)
    val guestCount = dto.guestCount.getOrElse(0)
    val timeSlot = TimeSlot.create(dto.startTime.getOrElse(""), durationHours)
    val eventDate = EventDate.create(dto.eventDate.getOrElse(""), Some(timeSlot.startTime))
    Domain.validateGuestCount(guestCount, chiva.capacity)

    if bookingRepository.hasConflict(chivaId, eventDate.value, timeSlot.startTime, timeSlot.durationHours) then
      throw DomainException.bookingConflict(chivaId, eventDate.dateString, timeSlot.startTime)

    val normalizedEmail = dto.customerEmail.getOrElse("").trim.toLowerCase
    val customerName = dto.customerName.getOrElse("").trim
    val customerPhone = dto.customerPhone.getOrElse("").trim

    val customer = customerRepository.upsertByEmail(customerName, normalizedEmail, customerPhone)
    val totalPrice = chiva.pricePerHour * durationHours
    val now = Instant.now()

    val newBooking = Booking(
      id = Domain.generateBookingCode(6),
      chivaId = chivaId,
      chivaName = Some(chiva.name),
      customerId = Some(customer.id),
      customerName = customerName,
      customerEmail = normalizedEmail,
      customerPhone = customerPhone,
      guestCount = guestCount,
      travel = travel.id,
      travelName = Some(travel.name),
      eventDate = eventDate.value,
      startTime = timeSlot.startTime,
      durationHours = timeSlot.durationHours,
      totalPrice = totalPrice,
      status = "pending",
      notes = dto.notes,
      googleEventId = None,
      calendarSyncStatus = "pending",
      createdAt = now,
      updatedAt = now
    )

    val saved = bookingRepository.saveBooking(newBooking)
    val response = toBookingResponseDto(saved)
    notificationService.sendBookingConfirmation(response)
    notificationService.sendNewBookingAlert(response)
    response

  def getBookings(
      rawPage: Option[String],
      rawLimit: Option[String],
      rawStatus: Option[String],
      rawQ: Option[String]
  ): PaginatedBookingsResponseDto =
    val page = rawPage.filter(_.nonEmpty) match
      case None => 1
      case Some(s) =>
        val p = s.toIntOption.getOrElse(
          throw DomainException.validation("El parámetro page debe ser un entero mayor o igual a 1.")
        )
        if p < 1 then throw DomainException.validation("El parámetro page debe ser un entero mayor o igual a 1.")
        p

    val limit = rawLimit.filter(_.nonEmpty) match
      case None => 10
      case Some(s) =>
        val l = s.toIntOption.getOrElse(
          throw DomainException.validation("El parámetro limit debe ser un entero entre 1 y 50.")
        )
        if l < 1 || l > 50 then
          throw DomainException.validation("El parámetro limit debe ser un entero entre 1 y 50.")
        l

    val status = rawStatus.filter(_.nonEmpty)
    status.foreach { st =>
      if !BookingStatuses.contains(st) then
        throw DomainException.validation("El parámetro status debe ser pending, confirmed, cancelled o completed.")
    }

    val q = rawQ.map(_.trim).filter(_.nonEmpty)
    val result = bookingRepository.findBookingPaginated(PaginatedBookingsQuery(page, limit, status, q))
    val data = result.data.map(toBookingResponseDto)
    val totalPages = math.ceil(result.total.toDouble / limit.toDouble).toInt

    PaginatedBookingsResponseDto(data, PaginationMetaDto(page, limit, result.total, totalPages))

  def getBookingById(id: String): BookingResponseDto =
    val booking = bookingRepository.findBookingById(id).getOrElse(throw DomainException.bookingNotFound(Some(id)))
    toBookingResponseDto(booking)

  def updateBookingStatus(bookingId: String, body: Json): BookingResponseDto =
    val obj = body.asObject.getOrElse(throw DomainException.validation("El campo status es requerido."))

    val statusOpt: Option[String] = obj("status").filter(!_.isNull) match
      case None => None
      case Some(j) =>
        val s = j.asString.getOrElse(
          throw DomainException.validation("El campo status debe ser confirmed, cancelled o completed.")
        )
        if s.isEmpty then None else Some(s)

    val rawPriceJson = obj("totalPrice").orElse(obj("price")).filter(!_.isNull)
    val parsedTotalPrice: Option[Double] = rawPriceJson.flatMap { j =>
      if j.asString.contains("") then None
      else
        val num = parseCustomPrice(j)
        if !num.isFinite || num <= 0.0 then
          throw DomainException.validation("El campo totalPrice debe ser un número mayor a 0.")
        Some(num)
    }

    if statusOpt.isEmpty && parsedTotalPrice.isEmpty then
      throw DomainException.validation("El campo status es requerido.")

    statusOpt.foreach { st =>
      val allowed = if parsedTotalPrice.isDefined then BookingStatuses else AcceptedUpdateStatuses
      if !allowed.contains(st) then
        throw DomainException.validation("El campo status debe ser confirmed, cancelled o completed.")
    }

    val booking = bookingRepository
      .findBookingById(bookingId)
      .getOrElse(throw DomainException.bookingNotFound(Some(bookingId)))

    val targetStatus = statusOpt.getOrElse(booking.status)
    val isSameStatusPriceUpdate = targetStatus == booking.status && parsedTotalPrice.isDefined

    val validTargets = ValidTransitions.getOrElse(booking.status, Set.empty)
    if !isSameStatusPriceUpdate && !validTargets.contains(targetStatus) then
      throw DomainException.invalidBookingStatusTransition(booking.status, targetStatus)

    val roundedPrice = parsedTotalPrice.map(p => math.round(p).toDouble)
    var updated = bookingRepository.updateBookingStatus(bookingId, targetStatus, roundedPrice)

    val resolvedChivaName = updated.chivaName.getOrElse(
      chivaRepository.findChivaById(updated.chivaId).map(_.name).getOrElse("")
    )
    val resolvedTravelName = updated.travelName.getOrElse(
      travelRepository.findTravelById(updated.travel).map(_.name).getOrElse(updated.travel)
    )
    val eventDateStr = Domain.toUtcDateString(updated.eventDate)

    if !isSameStatusPriceUpdate && targetStatus == "confirmed" then
      val existingEventId = updated.googleEventId.orElse(booking.googleEventId).filter(_.trim.nonEmpty)
      existingEventId match
        case Some(evtId) =>
          if updated.calendarSyncStatus != "synced" then
            updated = bookingRepository.updateBookingCalendarSync(bookingId, Some(evtId), true, "synced")
        case None =>
          try
            val eventId = calendarService.createEvent(
              CalendarEventInput(
                bookingId = updated.id,
                chivaName = if resolvedChivaName.isEmpty then updated.chivaId else resolvedChivaName,
                customerName = updated.customerName,
                customerPhone = updated.customerPhone,
                travelName = resolvedTravelName,
                guestCount = updated.guestCount,
                eventDate = eventDateStr,
                startTime = updated.startTime,
                durationHours = updated.durationHours,
                notes = updated.notes
              )
            )
            updated = bookingRepository.updateBookingCalendarSync(bookingId, Some(eventId), true, "synced")
          catch
            case _: Exception =>
              updated = bookingRepository.updateBookingCalendarSync(bookingId, None, false, "failed")
    else if !isSameStatusPriceUpdate && targetStatus == "cancelled" then
      val existingEventId = updated.googleEventId.orElse(booking.googleEventId).filter(_.trim.nonEmpty)
      existingEventId.foreach { evtId =>
        try
          calendarService.deleteEvent(evtId)
          updated = bookingRepository.updateBookingCalendarSync(bookingId, None, true, "synced")
        catch
          case _: Exception =>
            updated = bookingRepository.updateBookingCalendarSync(bookingId, None, false, "failed")
      }

    val response = toBookingResponseDto(updated)
    if !isSameStatusPriceUpdate then
      notificationService.sendBookingStatusUpdate(response, booking.status)
    response

  def syncBookingCalendar(bookingId: String): BookingResponseDto =
    val booking = bookingRepository
      .findBookingById(bookingId)
      .getOrElse(throw DomainException.bookingNotFound(Some(bookingId)))

    if booking.status != "confirmed" then
      throw DomainException.validation("Solo se pueden sincronizar en el calendario las reservas confirmadas.")

    val resolvedChivaName = booking.chivaName.getOrElse(
      chivaRepository.findChivaById(booking.chivaId).map(_.name).getOrElse("")
    )
    val resolvedTravelName = booking.travelName.getOrElse(
      travelRepository.findTravelById(booking.travel).map(_.name).getOrElse(booking.travel)
    )
    val eventDateStr = Domain.toUtcDateString(booking.eventDate)

    val updated = booking.googleEventId.filter(_.trim.nonEmpty) match
      case Some(evtId) =>
        if booking.calendarSyncStatus != "synced" then
          bookingRepository.updateBookingCalendarSync(bookingId, Some(evtId), true, "synced")
        else booking
      case None =>
        try
          val eventId = calendarService.createEvent(
            CalendarEventInput(
              bookingId = booking.id,
              chivaName = if resolvedChivaName.isEmpty then booking.chivaId else resolvedChivaName,
              customerName = booking.customerName,
              customerPhone = booking.customerPhone,
              travelName = resolvedTravelName,
              guestCount = booking.guestCount,
              eventDate = eventDateStr,
              startTime = booking.startTime,
              durationHours = booking.durationHours,
              notes = booking.notes
            )
          )
          bookingRepository.updateBookingCalendarSync(bookingId, Some(eventId), true, "synced")
        catch
          case _: Exception =>
            bookingRepository.updateBookingCalendarSync(bookingId, None, false, "failed")

    toBookingResponseDto(updated)

  /**
   * Programación Funcional en Scala 3 para el Dashboard del Administrador (GET /bookings/calendar?month=YYYY-MM):
   * Uso de funciones de orden superior, lambdas, filter, groupBy, foldLeft, map y sortBy
   * sobre estructuras 100 % inmutables con resultados idénticos a Express y Java.
   */
  def getBookingsCalendar(rawMonthOpt: Option[String]): BookingsCalendarResponseDto =
    val trimmedMonth = rawMonthOpt.map(_.trim).getOrElse("")
    if trimmedMonth.isEmpty || !MonthRegex.matches(trimmedMonth) then
      throw DomainException.validation("El parámetro month debe tener el formato YYYY-MM.")

    val parts = trimmedMonth.split("-").map(_.toInt)
    val (year, monthNumber) = (parts(0), parts(1))

    val startDate = ZonedDateTime.of(year, monthNumber, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant
    val endDate = ZonedDateTime.of(year, monthNumber, 1, 5, 0, 0, 0, ZoneOffset.UTC).plusMonths(1).toInstant

    val rawBookings = bookingRepository.findConfirmedByDateRange(startDate, endDate)

    val isConfirmedInMonth: Booking => Boolean = b =>
      b.status == "confirmed" && Domain.formatEventDateBogota(b.eventDate).startsWith(trimmedMonth)

    val toSlotDto: Booking => CalendarSlotDto = booking =>
      val ts = TimeSlot.create(booking.startTime, booking.durationHours)
      val startMinutes = TimeSlot.toMinutes(booking.startTime)
      val totalMinutes = startMinutes + booking.durationHours * 60.0
      val endsNextDay = Option.when(totalMinutes > 24.0 * 60.0)(true)
      val travelName = booking.travelName.getOrElse(booking.travel)
      CalendarSlotDto(
        bookingId = booking.id,
        startTime = booking.startTime,
        endTime = ts.getEndTime,
        durationHours = booking.durationHours,
        customerName = booking.customerName,
        travelName = travelName,
        guestCount = booking.guestCount,
        endsNextDay = endsNextDay
      )

    val confirmedInMonth = rawBookings.filter(isConfirmedInMonth)

    // Reducción funcional inmutable con foldLeft para conservar el orden de aparición de las chivas
    val chivasSummary = confirmedInMonth
      .foldLeft(Vector.empty[CalendarChivaSummaryDto]) { (acc, b) =>
        if acc.exists(_.id == b.chivaId) then acc
        else acc :+ CalendarChivaSummaryDto(b.chivaId, b.chivaName.getOrElse(b.chivaId))
      }
      .toList

    // Agrupación funcional por fecha (groupBy) y dentro de cada día por chiva (foldLeft + groupBy)
    val groupedByDate = confirmedInMonth.groupBy(b => Domain.formatEventDateBogota(b.eventDate))

    val days = groupedByDate.toList
      .sortBy(_._1)
      .map { case (dateStr, dayBookings) =>
        val chivaOrder = dayBookings.foldLeft(Vector.empty[String]) { (acc, b) =>
          if acc.contains(b.chivaId) then acc else acc :+ b.chivaId
        }
        val groupedByChiva = dayBookings.groupBy(_.chivaId)

        val dayChivas = chivaOrder.toList.map { chivaId =>
          val bookingsForChiva = groupedByChiva(chivaId)
          val chivaName = bookingsForChiva.headOption.flatMap(_.chivaName).getOrElse(chivaId)
          val sortedSlots = bookingsForChiva.map(toSlotDto).sortBy(_.startTime)
          CalendarDayChivaDto(chivaId, chivaName, sortedSlots)
        }

        CalendarDayDto(date = dateStr, chivasCount = dayChivas.size, chivas = dayChivas)
      }

    BookingsCalendarResponseDto(month = trimmedMonth, chivas = chivasSummary, days = days)

  // ── 5. Uploads ─────────────────────────────────────────────────────────────
  def getUploadSignature(): UploadSignatureDto =
    imageStorageService.generateUploadSignature()

object ChivasBusinessService:
  private val ChivaStatuses = Set("available", "maintenance", "retired")
  private val BookingStatuses = Set("pending", "confirmed", "cancelled", "completed")
  private val AcceptedUpdateStatuses = Set("confirmed", "cancelled", "completed")
  private val ValidTransitions: Map[String, Set[String]] = Map(
    "pending" -> Set("confirmed", "cancelled"),
    "confirmed" -> Set("completed", "cancelled"),
    "cancelled" -> Set.empty,
    "completed" -> Set.empty
  )

  private val MonthRegex: Regex = "^\\d{4}-(0[1-9]|1[0-2])$".r
  private val EmailRegex: Regex = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$".r
  private val FormattedThousandsRegex: Regex = "^\\$?\\s*\\d{1,3}(\\.\\d{3})+$".r
  private val OperationalStartMinutes = 8.0 * 60.0
  private val OperationalEndMinutes = 22.0 * 60.0

  private def parsePositiveInt(optJson: Option[Json], errMsg: String): Int =
    optJson
      .flatMap(_.asNumber)
      .flatMap(_.toInt)
      .filter(_ > 0)
      .getOrElse(throw DomainException.validation(errMsg))

  private def parsePositiveDouble(optJson: Option[Json], errMsg: String): Double =
    optJson
      .flatMap(_.asNumber)
      .map(_.toDouble)
      .filter(d => d.isFinite && d > 0.0)
      .getOrElse(throw DomainException.validation(errMsg))

  private def parseCustomPrice(json: Json): Double =
    json.asNumber.map(_.toDouble).getOrElse {
      json.asString match
        case Some(s) =>
          val trimmed = s.trim
          if FormattedThousandsRegex.matches(trimmed) then
            trimmed.replaceAll("[^\\d]", "").toDoubleOption.getOrElse(Double.NaN)
          else
            trimmed.replaceAll("[$,\\s]", "").toDoubleOption.getOrElse(Double.NaN)
        case None => Double.NaN
    }

  private def isValidHttpUrl(url: String): Boolean =
    Try {
      val uri = URI.create(url.trim)
      val scheme = Option(uri.getScheme).map(_.toLowerCase).getOrElse("")
      (scheme == "http" || scheme == "https") && uri.getHost != null
    }.getOrElse(false)

  private def parseLooseIsoDate(dateStr: String): Instant =
    Try {
      val clean = dateStr.trim.split("T")(0)
      LocalDate.parse(clean).atStartOfDay(ZoneOffset.UTC).toInstant
    }.getOrElse(Instant.now())
