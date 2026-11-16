package com.chivasrumberas.web

import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.semiauto.*

object ApiDtos:
  final case class ErrorDetail(typeName: String, message: String)
  object ErrorDetail:
    given Encoder[ErrorDetail] = Encoder.instance { d =>
      Json.obj(
        "type" -> Json.fromString(d.typeName),
        "message" -> Json.fromString(d.message)
      )
    }

  final case class ErrorResponse(error: ErrorDetail)
  object ErrorResponse:
    def of(typeName: String, message: String): ErrorResponse =
      ErrorResponse(ErrorDetail(typeName, message))
    given Encoder[ErrorResponse] = deriveEncoder

  final case class HealthResponse(status: String, timestamp: String)
  object HealthResponse:
    given Encoder[HealthResponse] = deriveEncoder

  final case class LoginDto(email: Option[String], password: Option[String])
  object LoginDto:
    given Decoder[LoginDto] = deriveDecoder

  final case class AuthUserDto(id: String, email: String, role: String)
  object AuthUserDto:
    given Encoder[AuthUserDto] = deriveEncoder

  final case class AuthResponseDto(accessToken: String, user: AuthUserDto)
  object AuthResponseDto:
    given Encoder[AuthResponseDto] = deriveEncoder

  final case class ChivaResponseDto(
      id: String,
      name: String,
      description: String,
      capacity: Int,
      imageUrls: List[String],
      pricePerHour: Double,
      status: String,
      features: List[String]
  )
  object ChivaResponseDto:
    given Encoder[ChivaResponseDto] = deriveEncoder

  final case class OccupiedSlotDto(
      startTime: String,
      endTime: String,
      durationHours: Double
  )
  object OccupiedSlotDto:
    given Encoder[OccupiedSlotDto] = deriveEncoder

  final case class AvailabilityResponseDto(
      chivaId: String,
      date: String,
      occupiedSlots: List[OccupiedSlotDto],
      isFullyBooked: Boolean
  )
  object AvailabilityResponseDto:
    given Encoder[AvailabilityResponseDto] = deriveEncoder

  final case class TravelResponseDto(
      id: String,
      name: String,
      description: String,
      durationHours: Double,
      highlights: List[String]
  )
  object TravelResponseDto:
    given Encoder[TravelResponseDto] = deriveEncoder

  final case class CreateBookingDto(
      chivaId: Option[String],
      travelId: Option[String],
      customerName: Option[String],
      customerEmail: Option[String],
      customerPhone: Option[String],
      guestCount: Option[Int],
      eventDate: Option[String],
      startTime: Option[String],
      durationHours: Option[Double],
      notes: Option[String]
  )
  object CreateBookingDto:
    given Decoder[CreateBookingDto] = deriveDecoder

  final case class BookingResponseDto(
      id: String,
      chivaId: String,
      chivaName: String,
      travelId: String,
      travelName: String,
      customerName: String,
      customerEmail: String,
      customerPhone: String,
      guestCount: Int,
      eventDate: String,
      startTime: String,
      endTime: String,
      durationHours: Double,
      totalPrice: Double,
      status: String,
      notes: Option[String],
      calendarSyncStatus: String,
      createdAt: String
  )
  object BookingResponseDto:
    given Encoder[BookingResponseDto] = deriveEncoder[BookingResponseDto].mapJson(_.dropNullValues)

  final case class PaginationMetaDto(
      page: Int,
      limit: Int,
      total: Int,
      totalPages: Int
  )
  object PaginationMetaDto:
    given Encoder[PaginationMetaDto] = deriveEncoder

  final case class PaginatedBookingsResponseDto(
      data: List[BookingResponseDto],
      meta: PaginationMetaDto
  )
  object PaginatedBookingsResponseDto:
    given Encoder[PaginatedBookingsResponseDto] = deriveEncoder

  final case class TrackBookingResponseDto(
      id: String,
      code: String,
      status: String,
      createdAt: String,
      chivaId: String,
      chivaName: String,
      travelId: String,
      travelName: String,
      eventDate: String,
      startTime: String,
      endTime: String,
      durationHours: Double,
      guestCount: Int,
      totalPrice: Double
  )
  object TrackBookingResponseDto:
    given Encoder[TrackBookingResponseDto] = deriveEncoder

  final case class CalendarSlotDto(
      bookingId: String,
      startTime: String,
      endTime: String,
      durationHours: Double,
      customerName: String,
      travelName: String,
      guestCount: Int,
      endsNextDay: Option[Boolean]
  )
  object CalendarSlotDto:
    given Encoder[CalendarSlotDto] = deriveEncoder[CalendarSlotDto].mapJson(_.dropNullValues)

  final case class CalendarDayChivaDto(
      chivaId: String,
      chivaName: String,
      slots: List[CalendarSlotDto]
  )
  object CalendarDayChivaDto:
    given Encoder[CalendarDayChivaDto] = deriveEncoder

  final case class CalendarDayDto(
      date: String,
      chivasCount: Int,
      chivas: List[CalendarDayChivaDto]
  )
  object CalendarDayDto:
    given Encoder[CalendarDayDto] = deriveEncoder

  final case class CalendarChivaSummaryDto(
      id: String,
      name: String
  )
  object CalendarChivaSummaryDto:
    given Encoder[CalendarChivaSummaryDto] = deriveEncoder

  final case class BookingsCalendarResponseDto(
      month: String,
      chivas: List[CalendarChivaSummaryDto],
      days: List[CalendarDayDto]
  )
  object BookingsCalendarResponseDto:
    given Encoder[BookingsCalendarResponseDto] = deriveEncoder

  final case class UploadSignatureDto(
      cloudName: String,
      apiKey: String,
      timestamp: Long,
      signature: String,
      folder: String,
      allowedFormats: List[String],
      maxFileSize: Long
  )
  object UploadSignatureDto:
    given Encoder[UploadSignatureDto] = deriveEncoder
