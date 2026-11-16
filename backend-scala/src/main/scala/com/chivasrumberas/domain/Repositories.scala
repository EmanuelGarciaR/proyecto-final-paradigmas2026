package com.chivasrumberas.domain

import com.chivasrumberas.domain.Domain.*
import java.time.Instant

trait ChivaRepository:
  def findChivaAll(status: Option[String]): List[Chiva]
  def findChivaById(id: String): Option[Chiva]
  def saveChiva(chiva: Chiva): Chiva
  def updateChivaStatus(id: String, status: String): Chiva
  def updateChiva(id: String, updates: Map[String, Any]): Chiva

trait TravelRepository:
  def findAllTravelsActive(): List[Travel]
  def findTravelById(id: String): Option[Travel]

trait CustomerRepository:
  def findByEmail(email: String): Option[Customer]
  def findById(id: String): Option[Customer]
  def upsertByEmail(name: String, email: String, phone: String): Customer

trait BookingRepository:
  def findBookingById(id: String): Option[Booking]
  def findBookingPaginated(query: PaginatedBookingsQuery): PaginatedBookingsResult
  def findConfirmedByDateRange(startDate: Instant, endDate: Instant): List[Booking]
  def findOccupiedSlotsByChivaAndDate(chivaId: String, date: Instant): List[OccupiedSlotRecord]
  def hasConflict(chivaId: String, eventDate: Instant, startTime: String, durationHours: Double): Boolean
  def hasFutureActiveBookingsByChiva(chivaId: String, fromDate: Instant): Boolean
  def saveBooking(booking: Booking): Booking
  def updateBookingStatus(id: String, status: String, totalPrice: Option[Double]): Booking
  def updateBookingCalendarSync(
      id: String,
      googleEventId: Option[String],
      updateGoogleEventId: Boolean,
      calendarSyncStatus: String
  ): Booking

trait UserRepository:
  def findUserByEmail(email: String): Option[User]
  def findUserById(id: String): Option[User]
