package com.chivasrumberas.infrastructure

import com.chivasrumberas.domain.Domain.JwtPayload
import com.chivasrumberas.domain.DomainException
import com.chivasrumberas.web.ApiDtos.{BookingResponseDto, UploadSignatureDto}
import io.circe.Json
import io.circe.parser.parse

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.{Base64, HexFormat}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

final case class CalendarEventInput(
    bookingId: String,
    chivaName: String,
    customerName: String,
    customerPhone: String,
    travelName: String,
    guestCount: Int,
    eventDate: String,
    startTime: String,
    durationHours: Double,
    notes: Option[String]
)

trait JwtTokenService:
  def sign(payload: JwtPayload): String
  def verify(token: String): JwtPayload

trait CalendarPort:
  def createEvent(input: CalendarEventInput): String
  def deleteEvent(googleEventId: String): Unit

trait NotificationPort:
  def sendBookingConfirmation(booking: BookingResponseDto): Unit
  def sendNewBookingAlert(booking: BookingResponseDto): Unit
  def sendBookingStatusUpdate(booking: BookingResponseDto, previousStatus: String): Unit

trait ImageStoragePort:
  def generateUploadSignature(): UploadSignatureDto

class HmacJwtService(secret: String, expiresInRaw: String) extends JwtTokenService:
  if secret == null || secret.trim.isEmpty then
    throw new IllegalStateException("Error con la configuración JWT_SECRET")

  private val secretBytes = secret.getBytes(StandardCharsets.UTF_8)
  private val expiresInSeconds = HmacJwtService.parseExpiresIn(expiresInRaw)

  override def sign(payload: JwtPayload): String =
    val headerJson = Json.obj("alg" -> Json.fromString("HS256"), "typ" -> Json.fromString("JWT")).noSpaces
    val iat = Instant.now().getEpochSecond
    val exp = iat + expiresInSeconds
    val claimsJson = Json
      .obj(
        "sub" -> Json.fromString(payload.sub),
        "email" -> Json.fromString(payload.email),
        "role" -> Json.fromString(payload.role),
        "iat" -> Json.fromLong(iat),
        "exp" -> Json.fromLong(exp)
      )
      .noSpaces

    val enc = Base64.getUrlEncoder.withoutPadding()
    val headerB64 = enc.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8))
    val payloadB64 = enc.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8))
    val signingInput = s"$headerB64.$payloadB64"
    val sigB64 = hmacSha256Base64Url(signingInput)
    s"$signingInput.$sigB64"

  override def verify(token: String): JwtPayload =
    if token == null || token.trim.isEmpty then throw DomainException.invalidToken()
    val parts = token.split("\\.")
    if parts.length != 3 then throw DomainException.invalidToken()

    val signingInput = s"${parts(0)}.${parts(1)}"
    val expectedSig = hmacSha256Base64Url(signingInput)
    if !MessageDigest.isEqual(
        expectedSig.getBytes(StandardCharsets.UTF_8),
        parts(2).getBytes(StandardCharsets.UTF_8)
      )
    then throw DomainException.invalidToken()

    val decodedJsonStr =
      try new String(Base64.getUrlDecoder.decode(parts(1)), StandardCharsets.UTF_8)
      catch case _: Exception => throw DomainException.invalidToken()

    val cursor = parse(decodedJsonStr).getOrElse(throw DomainException.invalidToken()).hcursor
    cursor.downField("exp").as[Long].toOption.foreach { exp =>
      if Instant.now().getEpochSecond >= exp then throw DomainException.invalidToken()
    }

    val sub = cursor.downField("sub").as[String].getOrElse(throw DomainException.invalidToken())
    val email = cursor.downField("email").as[String].getOrElse(throw DomainException.invalidToken())
    val role = cursor.downField("role").as[String].getOrElse(throw DomainException.invalidToken())
    JwtPayload(sub, email, role)

  private def hmacSha256Base64Url(input: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"))
    val rawHmac = mac.doFinal(input.getBytes(StandardCharsets.UTF_8))
    Base64.getUrlEncoder.withoutPadding().encodeToString(rawHmac)

object HmacJwtService:
  def parseExpiresIn(raw: String): Long =
    Option(raw).map(_.trim.toLowerCase).filter(_.nonEmpty) match
      case None                        => 4L * 3600L
      case Some(s) if s.endsWith("h")  => s.dropRight(1).toLong * 3600L
      case Some(s) if s.endsWith("m")  => s.dropRight(1).toLong * 60L
      case Some(s) if s.endsWith("d")  => s.dropRight(1).toLong * 86400L
      case Some(s) if s.endsWith("s")  => s.dropRight(1).toLong
      case Some(s)                     => s.toLongOption.getOrElse(4L * 3600L)

class ConfigurableCalendarService(mockLatencyMs: Long) extends CalendarPort:
  private def simulateLatency(): Unit =
    if mockLatencyMs > 0 then Thread.sleep(mockLatencyMs)

  override def createEvent(input: CalendarEventInput): String =
    simulateLatency()
    if input.notes.exists(_.contains("[FORCE_CALENDAR_ERROR]")) then
      throw new RuntimeException("Simulated Google Calendar 503 Unavailable")
    s"gcal-mock-${input.bookingId}"

  override def deleteEvent(googleEventId: String): Unit =
    simulateLatency()

class ConfigurableNotificationService(mockLatencyMs: Long) extends NotificationPort:
  private def simulateLatency(): Unit =
    if mockLatencyMs > 0 then Thread.sleep(mockLatencyMs)

  override def sendBookingConfirmation(booking: BookingResponseDto): Unit =
    simulateLatency()

  override def sendNewBookingAlert(booking: BookingResponseDto): Unit =
    simulateLatency()

  override def sendBookingStatusUpdate(booking: BookingResponseDto, previousStatus: String): Unit =
    simulateLatency()

class CloudinaryImageStorageService(rawCloudName: String, rawApiKey: String, rawApiSecret: String)
    extends ImageStoragePort:
  private def stripQuotes(s: String): String =
    Option(s).map(_.trim.replaceAll("^[\"']+|[\"']+$", "")).getOrElse("")

  private val cloudName = stripQuotes(rawCloudName)
  private val apiKey = stripQuotes(rawApiKey)
  private val apiSecret = stripQuotes(rawApiSecret)

  override def generateUploadSignature(): UploadSignatureDto =
    if cloudName.isEmpty || apiKey.isEmpty || apiSecret.isEmpty then
      throw DomainException.internalError(
        "Las credenciales de Cloudinary (CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY, CLOUDINARY_API_SECRET) no están configuradas."
      )

    val timestamp = Instant.now().getEpochSecond
    val folder = "chivas-rumberas/chivas"
    val allowedFormats = List("jpg", "png", "webp")
    val maxFileSize = 5L * 1024L * 1024L

    val paramsToSign = Map(
      "allowed_formats" -> allowedFormats.mkString(","),
      "folder" -> folder,
      "timestamp" -> timestamp.toString
    )

    val sortedParams = paramsToSign.toSeq
      .sortBy(_._1)
      .map { case (k, v) => s"$k=$v" }
      .reduce((a, b) => s"$a&$b")

    val sha1 = MessageDigest.getInstance("SHA-1")
    val digest = sha1.digest(s"$sortedParams$apiSecret".getBytes(StandardCharsets.UTF_8))
    val signature = HexFormat.of().formatHex(digest)

    UploadSignatureDto(
      cloudName = cloudName,
      apiKey = apiKey,
      timestamp = timestamp,
      signature = signature,
      folder = folder,
      allowedFormats = allowedFormats,
      maxFileSize = maxFileSize
    )
