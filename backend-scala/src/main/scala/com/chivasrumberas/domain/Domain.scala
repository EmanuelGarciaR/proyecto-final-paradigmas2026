package com.chivasrumberas.domain

import java.security.SecureRandom
import java.text.Normalizer
import java.time.{Instant, LocalDate, OffsetDateTime, ZoneId, ZoneOffset}
import java.time.format.DateTimeFormatter
import scala.util.Try
import scala.util.matching.Regex

final case class DomainException(errorType: String, httpStatus: Int, message: String)
    extends RuntimeException(message)

object DomainException:
  def validation(message: String): DomainException =
    DomainException("ValidationError", 400, message)

  def invalidCredentials(): DomainException =
    DomainException("InvalidCredentialsError", 401, "Email o contraseña incorrectos.")

  def invalidToken(): DomainException =
    DomainException("InvalidTokenError", 401, "Token inválido o expirado.")

  def unauthorized(): DomainException =
    DomainException("UnauthorizedError", 403, "No tienes permisos para realizar esta acción.")

  def chivaNotFound(chivaId: String): DomainException =
    DomainException("ChivaNotFoundError", 404, "Chiva no encontrada.")

  def bookingNotFound(bookingId: Option[String] = None): DomainException =
    val msg = bookingId.filter(_.trim.nonEmpty) match
      case Some(id) => s"La reserva con ID \"$id\" no fue encontrada."
      case None     => "No se encontró ninguna reserva con los datos proporcionados."
    DomainException("BookingNotFoundError", 404, msg)

  def travelNotFound(travelId: String): DomainException =
    DomainException("TravelNotFoundError", 404, s"El recorrido con ID \"$travelId\" no fue encontrado.")

  def chivaNotAvailable(chivaId: String): DomainException =
    DomainException("ChivaNotAvailableError", 409, s"La chiva con ID \"$chivaId\" no está disponible para reservas.")

  def bookingConflict(chivaId: String, date: String, startTime: String): DomainException =
    DomainException(
      "BookingConflictError",
      409,
      s"La chiva \"$chivaId\" ya tiene una reserva el $date a las $startTime. Por favor elige otro horario."
    )

  def chivaHasActiveBookings(chivaId: String): DomainException =
    DomainException(
      "ChivaHasActiveBookingsError",
      409,
      s"No se puede retirar la chiva \"$chivaId\" porque tiene reservas futuras pendientes o confirmadas."
    )

  def invalidBookingStatusTransition(from: String, to: String): DomainException =
    DomainException(
      "InvalidBookingStatusTransitionError",
      422,
      s"No se puede cambiar el estado de \"$from\" a \"$to\"."
    )

  def tooManyRequests(message: String): DomainException =
    DomainException("TooManyRequestsError", 429, message)

  def internalError(message: String): DomainException =
    DomainException("InternalServerError", 500, message)

object Domain:
  val BogotaZone: ZoneId = ZoneId.of("America/Bogota")
  private val TimeRegex: Regex = "^([01]\\d|2[0-3]):([0-5]\\d)$".r
  private val DateRegex: Regex = "^\\d{4}-\\d{2}-\\d{2}$".r
  private val AlphanumericChars = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
  private val RandomGen = new SecureRandom()

  final case class Chiva(
      id: String,
      name: String,
      description: String,
      capacity: Int,
      imageUrls: List[String],
      pricePerHour: Double,
      status: String,
      features: List[String],
      createdAt: Instant,
      updatedAt: Instant
  )

  final case class Travel(
      id: String,
      name: String,
      description: String,
      durationHours: Double,
      highlights: List[String],
      isActive: Boolean
  )

  final case class Customer(
      id: String,
      name: String,
      email: String,
      phone: String,
      createdAt: Instant
  )

  final case class Booking(
      id: String,
      chivaId: String,
      chivaName: Option[String],
      customerId: Option[String],
      customerName: String,
      customerEmail: String,
      customerPhone: String,
      guestCount: Int,
      travel: String,
      travelName: Option[String],
      eventDate: Instant,
      startTime: String,
      durationHours: Double,
      totalPrice: Double,
      status: String,
      notes: Option[String],
      googleEventId: Option[String],
      calendarSyncStatus: String,
      createdAt: Instant,
      updatedAt: Instant
  )

  final case class User(
      id: String,
      email: String,
      passwordHash: String,
      role: String,
      createdAt: Instant
  )

  final case class JwtPayload(
      sub: String,
      email: String,
      role: String
  )

  final case class OccupiedSlotRecord(
      startTime: String,
      durationHours: Double
  )

  final case class PaginatedBookingsQuery(
      page: Int,
      limit: Int,
      status: Option[String],
      q: Option[String]
  )

  final case class PaginatedBookingsResult(
      data: List[Booking],
      total: Int
  )

  final case class TimeSlot(startTime: String, durationHours: Double):
    def getEndTime: String =
      val parts = startTime.split(":").map(_.toInt)
      val totalMinutes = math.round(parts(0) * 60 + parts(1) + durationHours * 60).toInt
      val endHour = (totalMinutes / 60) % 24
      val endMin = totalMinutes % 60
      f"$endHour%02d:$endMin%02d"

  object TimeSlot:
    def create(startTime: String, durationHours: Double): TimeSlot =
      if startTime == null || !TimeRegex.matches(startTime) then
        throw DomainException.validation(s"Hora inválida: \"$startTime\". Use formato HH:mm (24h).")
      if !durationHours.isFinite || durationHours < 1.0 || durationHours > 12.0 then
        throw DomainException.validation("La duración debe estar entre 1 y 12 horas.")
      TimeSlot(startTime, durationHours)

    def toMinutes(time: String): Int =
      val parts = time.split(":").map(_.toInt)
      parts(0) * 60 + parts(1)

  final case class EventDate(value: Instant, dateString: String)

  object EventDate:
    def create(dateStr: String, startTime: Option[String]): EventDate =
      val rawStr = Option(dateStr).map(_.trim.split("T")(0)).getOrElse("")
      if !DateRegex.matches(rawStr) then
        throw DomainException.validation("Fecha inválida. Use el formato YYYY-MM-DD.")
      val localDate = Try(LocalDate.parse(rawStr)).getOrElse(
        throw DomainException.validation("Fecha inválida.")
      )
      val parsedDateOnly = localDate.atStartOfDay(ZoneOffset.UTC).toInstant
      val timePart = startTime
        .map(_.trim)
        .filter(TimeRegex.matches)
        .getOrElse("23:59")
      val eventMomentBogota = OffsetDateTime.parse(s"${rawStr}T$timePart:00-05:00")
      val minAdvanceMs = 24L * 60L * 60L * 1000L
      if eventMomentBogota.toInstant.toEpochMilli - Instant.now().toEpochMilli < minAdvanceMs then
        throw DomainException.validation(
          "Debes agendar tu reserva con más de un día de anticipación (mínimo 24 horas antes del evento)."
        )
      EventDate(parsedDateOnly, rawStr)

  def validateGuestCount(count: Int, chivaCapacity: Int): Unit =
    if count < 1 then
      throw DomainException.validation("La cantidad de invitados debe ser un número entero mayor a 0.")
    if count > chivaCapacity then
      throw DomainException.validation(
        s"La cantidad de invitados ($count) supera la capacidad de la chiva ($chivaCapacity)."
      )

  def toUtcDateString(instant: Instant): String =
    instant.atZone(ZoneOffset.UTC).toLocalDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

  def formatEventDateBogota(date: Instant): String =
    val utc = date.atZone(ZoneOffset.UTC)
    if utc.getHour == 0 && utc.getMinute == 0 && utc.getSecond == 0 && utc.getNano == 0 then
      utc.toLocalDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
    else
      date.atZone(BogotaZone).toLocalDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

  def hasBookingDateTimePassed(eventDate: Instant, startTime: String, now: Instant = Instant.now()): Boolean =
    val dateStr = toUtcDateString(eventDate)
    val timeStr = Option(startTime).map(_.trim).filter(TimeRegex.matches).getOrElse("00:00")
    val scheduledBogota = OffsetDateTime.parse(s"${dateStr}T$timeStr:00-05:00")
    !scheduledBogota.toInstant.isAfter(now)

  def generateBookingCode(length: Int = 6): String =
    val bytes = new Array[Byte](length)
    RandomGen.nextBytes(bytes)
    val chars = bytes.map(b => AlphanumericChars(java.lang.Byte.toUnsignedInt(b) % AlphanumericChars.length)).mkString
    s"CR-$chars"

  def slugify(text: String): String =
    if text == null then ""
    else
      Normalizer
        .normalize(text.toLowerCase, Normalizer.Form.NFD)
        .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "")
        .replaceAll("[^a-z0-9]+", "-")
        .replaceAll("^-+|-+$", "")
