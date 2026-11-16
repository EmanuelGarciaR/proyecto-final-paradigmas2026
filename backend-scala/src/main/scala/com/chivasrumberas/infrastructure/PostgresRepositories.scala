package com.chivasrumberas.infrastructure

import com.chivasrumberas.domain.*
import com.chivasrumberas.domain.Domain.*
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import java.net.URI
import java.sql.{Array as SqlArray, Connection, PreparedStatement, ResultSet, Timestamp}
import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.util.UUID
import javax.sql.DataSource
import scala.collection.mutable.ListBuffer
import scala.util.Using

object DatabaseFactory:
  def createDataSource(rawUrl: String, defaultUser: String, defaultPassword: String): HikariDataSource =
    val config = new HikariConfig()
    config.setDriverClassName("org.postgresql.Driver")
    config.setMaximumPoolSize(20)
    config.setMinimumIdle(5)

    val trimmed = Option(rawUrl).map(_.trim).getOrElse("jdbc:postgresql://localhost:5432/chivas_rumberas")
    if trimmed.startsWith("jdbc:postgresql://") then
      config.setJdbcUrl(trimmed)
      config.setUsername(defaultUser)
      config.setPassword(defaultPassword)
    else if trimmed.startsWith("postgresql://") || trimmed.startsWith("postgres://") then
      val uri = URI.create(trimmed)
      val host = uri.getHost
      val port = if uri.getPort > 0 then uri.getPort else 5432
      val path = uri.getPath
      config.setJdbcUrl(s"jdbc:postgresql://$host:$port$path")
      Option(uri.getUserInfo).filter(_.contains(":")) match
        case Some(userInfo) =>
          val parts = userInfo.split(":", 2)
          config.setUsername(parts(0))
          config.setPassword(parts(1))
        case None =>
          config.setUsername(defaultUser)
          config.setPassword(defaultPassword)
    else
      config.setJdbcUrl(trimmed)
      config.setUsername(defaultUser)
      config.setPassword(defaultPassword)

    new HikariDataSource(config)

object JdbcHelpers:
  def withConnection[A](ds: DataSource)(f: Connection => A): A =
    Using.resource(ds.getConnection)(f)

  def toStringList(sqlArray: SqlArray): List[String] =
    if sqlArray == null then Nil
    else
      sqlArray.getArray match
        case arr: Array[String] => arr.toList
        case arr: Array[Object] => arr.map(String.valueOf).toList
        case _                  => Nil

  def getUtcInstant(rs: ResultSet, col: String): Instant =
    val ldt = rs.getObject(col, classOf[LocalDateTime])
    if ldt != null then ldt.toInstant(ZoneOffset.UTC) else Instant.EPOCH

  def setUtcInstant(ps: PreparedStatement, index: Int, instant: Instant): Unit =
    ps.setObject(index, LocalDateTime.ofInstant(instant, ZoneOffset.UTC))

class PgChivaRepository(ds: DataSource) extends ChivaRepository:
  import JdbcHelpers.*

  private def mapRow(rs: ResultSet): Chiva =
    Chiva(
      id = rs.getString("id"),
      name = rs.getString("name"),
      description = rs.getString("description"),
      capacity = rs.getInt("capacity"),
      imageUrls = toStringList(rs.getArray("imageUrls")),
      pricePerHour = rs.getDouble("pricePerHour"),
      status = rs.getString("status"),
      features = toStringList(rs.getArray("features")),
      createdAt = getUtcInstant(rs, "createdAt"),
      updatedAt = getUtcInstant(rs, "updatedAt")
    )

  override def findChivaAll(status: Option[String]): List[Chiva] =
    withConnection(ds) { conn =>
      val sql = status.filter(_.trim.nonEmpty) match
        case Some(_) => """SELECT * FROM "chivas" WHERE "status" = ?::"ChivaStatus" ORDER BY "createdAt" ASC"""
        case None    => """SELECT * FROM "chivas" ORDER BY "createdAt" ASC"""
      Using.resource(conn.prepareStatement(sql)) { ps =>
        status.filter(_.trim.nonEmpty).foreach(st => ps.setString(1, st))
        Using.resource(ps.executeQuery()) { rs =>
          val buf = ListBuffer.empty[Chiva]
          while rs.next() do buf += mapRow(rs)
          buf.toList
        }
      }
    }

  override def findChivaById(id: String): Option[Chiva] =
    withConnection(ds) { conn =>
      Using.resource(conn.prepareStatement("""SELECT * FROM "chivas" WHERE "id" = ?""")) { ps =>
        ps.setString(1, id)
        Using.resource(ps.executeQuery()) { rs =>
          if rs.next() then Some(mapRow(rs)) else None
        }
      }
    }

  override def saveChiva(chiva: Chiva): Chiva =
    withConnection(ds) { conn =>
      val sql =
        """INSERT INTO "chivas" ("id", "name", "description", "capacity", "imageUrls", "pricePerHour", "status", "features", "createdAt", "updatedAt")
           VALUES (?, ?, ?, ?, ?, ?, ?::"ChivaStatus", ?, ?, ?)"""
      Using.resource(conn.prepareStatement(sql)) { ps =>
        ps.setString(1, chiva.id)
        ps.setString(2, chiva.name)
        ps.setString(3, chiva.description)
        ps.setInt(4, chiva.capacity)
        ps.setArray(5, conn.createArrayOf("text", chiva.imageUrls.toArray))
        ps.setDouble(6, chiva.pricePerHour)
        ps.setString(7, chiva.status)
        ps.setArray(8, conn.createArrayOf("text", chiva.features.toArray))
        setUtcInstant(ps, 9, chiva.createdAt)
        setUtcInstant(ps, 10, chiva.createdAt)
        ps.executeUpdate()
      }
    }
    findChivaById(chiva.id).get

  override def updateChivaStatus(id: String, status: String): Chiva =
    withConnection(ds) { conn =>
      Using.resource(
        conn.prepareStatement(
          """UPDATE "chivas" SET "status" = ?::"ChivaStatus", "updatedAt" = CURRENT_TIMESTAMP WHERE "id" = ?"""
        )
      ) { ps =>
        ps.setString(1, status)
        ps.setString(2, id)
        ps.executeUpdate()
      }
    }
    findChivaById(id).get

  override def updateChiva(id: String, updates: Map[String, Any]): Chiva =
    withConnection(ds) { conn =>
      val clauses = ListBuffer.empty[String]
      val setters = ListBuffer.empty[(java.sql.PreparedStatement, Int) => Unit]

      updates.get("name").foreach { v =>
        clauses += """"name" = ?"""
        setters += ((ps, idx) => ps.setString(idx, v.toString))
      }
      updates.get("description").foreach { v =>
        clauses += """"description" = ?"""
        setters += ((ps, idx) => ps.setString(idx, v.toString))
      }
      updates.get("capacity").foreach { v =>
        clauses += """"capacity" = ?"""
        setters += ((ps, idx) => ps.setInt(idx, v.asInstanceOf[Int]))
      }
      updates.get("pricePerHour").foreach { v =>
        clauses += """"pricePerHour" = ?"""
        setters += ((ps, idx) => ps.setDouble(idx, v.asInstanceOf[Double]))
      }
      updates.get("status").foreach { v =>
        clauses += """"status" = ?::"ChivaStatus""""
        setters += ((ps, idx) => ps.setString(idx, v.toString))
      }
      updates.get("features").foreach { v =>
        clauses += """"features" = ?"""
        val arr = v.asInstanceOf[List[String]].toArray[Object]
        setters += ((ps, idx) => ps.setArray(idx, conn.createArrayOf("text", arr)))
      }
      updates.get("imageUrls").foreach { v =>
        clauses += """"imageUrls" = ?"""
        val arr = v.asInstanceOf[List[String]].toArray[Object]
        setters += ((ps, idx) => ps.setArray(idx, conn.createArrayOf("text", arr)))
      }

      clauses += """"updatedAt" = CURRENT_TIMESTAMP"""
      val sql = s"""UPDATE "chivas" SET ${clauses.mkString(", ")} WHERE "id" = ?"""
      Using.resource(conn.prepareStatement(sql)) { ps =>
        setters.zipWithIndex.foreach { case (setter, i) => setter(ps, i + 1) }
        ps.setString(setters.size + 1, id)
        ps.executeUpdate()
      }
    }
    findChivaById(id).get

class PgTravelRepository(ds: DataSource) extends TravelRepository:
  import JdbcHelpers.*

  private def mapRow(rs: ResultSet): Travel =
    Travel(
      id = rs.getString("id"),
      name = rs.getString("name"),
      description = rs.getString("description"),
      durationHours = rs.getDouble("durationHours"),
      highlights = toStringList(rs.getArray("highlights")),
      isActive = rs.getBoolean("isActive")
    )

  override def findAllTravelsActive(): List[Travel] =
    withConnection(ds) { conn =>
      Using.resource(conn.prepareStatement("""SELECT * FROM "travels" WHERE "isActive" = true ORDER BY "name" ASC""")) {
        ps =>
          Using.resource(ps.executeQuery()) { rs =>
            val buf = ListBuffer.empty[Travel]
            while rs.next() do buf += mapRow(rs)
            buf.toList
          }
      }
    }

  override def findTravelById(id: String): Option[Travel] =
    withConnection(ds) { conn =>
      Using.resource(conn.prepareStatement("""SELECT * FROM "travels" WHERE "id" = ?""")) { ps =>
        ps.setString(1, id)
        Using.resource(ps.executeQuery()) { rs =>
          if rs.next() then Some(mapRow(rs)) else None
        }
      }
    }

class PgCustomerRepository(ds: DataSource) extends CustomerRepository:
  import JdbcHelpers.*

  private def mapRow(rs: ResultSet): Customer =
    Customer(
      id = rs.getString("id"),
      name = rs.getString("name"),
      email = rs.getString("email"),
      phone = rs.getString("phone"),
      createdAt = getUtcInstant(rs, "createdAt")
    )

  override def findByEmail(email: String): Option[Customer] =
    val norm = email.trim.toLowerCase
    withConnection(ds) { conn =>
      Using.resource(conn.prepareStatement("""SELECT * FROM "customers" WHERE "email" = ?""")) { ps =>
        ps.setString(1, norm)
        Using.resource(ps.executeQuery()) { rs =>
          if rs.next() then Some(mapRow(rs)) else None
        }
      }
    }

  override def findById(id: String): Option[Customer] =
    withConnection(ds) { conn =>
      Using.resource(conn.prepareStatement("""SELECT * FROM "customers" WHERE "id" = ?""")) { ps =>
        ps.setString(1, id)
        Using.resource(ps.executeQuery()) { rs =>
          if rs.next() then Some(mapRow(rs)) else None
        }
      }
    }

  override def upsertByEmail(name: String, email: String, phone: String): Customer =
    val normEmail = email.trim.toLowerCase
    val cleanName = name.trim
    val cleanPhone = phone.trim
    withConnection(ds) { conn =>
      val sql =
        """INSERT INTO "customers" ("id", "name", "email", "phone", "createdAt")
           VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
           ON CONFLICT ("email") DO UPDATE
           SET "name" = EXCLUDED."name", "phone" = EXCLUDED."phone""""
      Using.resource(conn.prepareStatement(sql)) { ps =>
        ps.setString(1, UUID.randomUUID().toString)
        ps.setString(2, cleanName)
        ps.setString(3, normEmail)
        ps.setString(4, cleanPhone)
        ps.executeUpdate()
      }
    }
    findByEmail(normEmail).get

class PgUserRepository(ds: DataSource) extends UserRepository:
  import JdbcHelpers.*

  private def mapRow(rs: ResultSet): User =
    User(
      id = rs.getString("id"),
      email = rs.getString("email"),
      passwordHash = rs.getString("passwordHash"),
      role = rs.getString("role"),
      createdAt = getUtcInstant(rs, "createdAt")
    )

  override def findUserByEmail(email: String): Option[User] =
    withConnection(ds) { conn =>
      Using.resource(conn.prepareStatement("""SELECT * FROM "users" WHERE "email" = ?""")) { ps =>
        ps.setString(1, email)
        Using.resource(ps.executeQuery()) { rs =>
          if rs.next() then Some(mapRow(rs)) else None
        }
      }
    }

  override def findUserById(id: String): Option[User] =
    withConnection(ds) { conn =>
      Using.resource(conn.prepareStatement("""SELECT * FROM "users" WHERE "id" = ?""")) { ps =>
        ps.setString(1, id)
        Using.resource(ps.executeQuery()) { rs =>
          if rs.next() then Some(mapRow(rs)) else None
        }
      }
    }

class PgBookingRepository(ds: DataSource) extends BookingRepository:
  import JdbcHelpers.*

  private val SelectWithRelations =
    """SELECT b.*, c."name" AS "chivaName", t."name" AS "travelName"
       FROM "bookings" b
       LEFT JOIN "chivas" c ON c."id" = b."chivaId"
       LEFT JOIN "travels" t ON t."id" = b."travelId""""

  private def mapRow(rs: ResultSet): Booking =
    Booking(
      id = rs.getString("id"),
      chivaId = rs.getString("chivaId"),
      chivaName = Option(rs.getString("chivaName")),
      customerId = Option(rs.getString("customerId")),
      customerName = rs.getString("customerName"),
      customerEmail = rs.getString("customerEmail"),
      customerPhone = rs.getString("customerPhone"),
      guestCount = rs.getInt("guestCount"),
      travel = rs.getString("travelId"),
      travelName = Option(rs.getString("travelName")),
      eventDate = getUtcInstant(rs, "eventDate"),
      startTime = rs.getString("startTime"),
      durationHours = rs.getDouble("durationHours"),
      totalPrice = rs.getDouble("totalPrice"),
      status = rs.getString("status"),
      notes = Option(rs.getString("notes")),
      googleEventId = Option(rs.getString("googleEventId")),
      calendarSyncStatus = Option(rs.getString("calendarSyncStatus")).getOrElse("pending"),
      createdAt = getUtcInstant(rs, "createdAt"),
      updatedAt = getUtcInstant(rs, "updatedAt")
    )

  private def completeElapsedConfirmedBookings(now: Instant = Instant.now()): Unit =
    withConnection(ds) { conn =>
      val upperBound = now.plusSeconds(24L * 3600L)
      val candidates = ListBuffer.empty[(String, Instant, String)]
      Using.resource(
        conn.prepareStatement(
          """SELECT "id", "eventDate", "startTime" FROM "bookings"
             WHERE "status" = 'confirmed'::"BookingStatus" AND "eventDate" <= ?"""
        )
      ) { ps =>
        setUtcInstant(ps, 1, upperBound)
        Using.resource(ps.executeQuery()) { rs =>
          while rs.next() do
            candidates += ((rs.getString("id"), getUtcInstant(rs, "eventDate"), rs.getString("startTime")))
        }
      }

      val elapsedIds = candidates.toList
        .filter { case (_, evDate, stTime) => Domain.hasBookingDateTimePassed(evDate, stTime, now) }
        .map(_._1)

      if elapsedIds.nonEmpty then
        val placeholders = elapsedIds.map(_ => "?").mkString(",")
        val sql =
          s"""UPDATE "bookings" SET "status" = 'completed'::"BookingStatus", "updatedAt" = CURRENT_TIMESTAMP
              WHERE "status" = 'confirmed'::"BookingStatus" AND "id" IN ($placeholders)"""
        Using.resource(conn.prepareStatement(sql)) { ps =>
          elapsedIds.zipWithIndex.foreach { case (id, idx) => ps.setString(idx + 1, id) }
          ps.executeUpdate()
        }
    }

  override def findBookingById(id: String): Option[Booking] =
    completeElapsedConfirmedBookings()
    withConnection(ds) { conn =>
      Using.resource(conn.prepareStatement(s"$SelectWithRelations WHERE b.\"id\" = ?")) { ps =>
        ps.setString(1, id)
        Using.resource(ps.executeQuery()) { rs =>
          if rs.next() then Some(mapRow(rs)) else None
        }
      }
    }

  override def findBookingPaginated(query: PaginatedBookingsQuery): PaginatedBookingsResult =
    completeElapsedConfirmedBookings()
    withConnection(ds) { conn =>
      val whereClauses = ListBuffer.empty[String]
      val setters = ListBuffer.empty[(java.sql.PreparedStatement, Int) => Unit]

      query.status.filter(_.trim.nonEmpty).foreach { st =>
        whereClauses += """b."status" = ?::"BookingStatus""""
        setters += ((ps, idx) => ps.setString(idx, st))
      }

      query.q.map(_.trim).filter(_.nonEmpty).foreach { search =>
        val pattern = s"%$search%"
        whereClauses += """(b."id" ILIKE ? OR b."customerName" ILIKE ? OR b."customerEmail" ILIKE ?)"""
        setters += ((ps, idx) => ps.setString(idx, pattern))
        setters += ((ps, idx) => ps.setString(idx, pattern))
        setters += ((ps, idx) => ps.setString(idx, pattern))
      }

      val whereSql = if whereClauses.isEmpty then "" else s" WHERE ${whereClauses.mkString(" AND ")}"

      val total = Using.resource(conn.prepareStatement(s"""SELECT COUNT(*) FROM "bookings" b$whereSql""")) { ps =>
        setters.zipWithIndex.foreach { case (setter, idx) => setter(ps, idx + 1) }
        Using.resource(ps.executeQuery()) { rs =>
          if rs.next() then rs.getInt(1) else 0
        }
      }

      val skip = (query.page - 1) * query.limit
      val dataSql = s"""$SelectWithRelations$whereSql ORDER BY b."createdAt" DESC, b."id" DESC LIMIT ? OFFSET ?"""
      val rows = Using.resource(conn.prepareStatement(dataSql)) { ps =>
        setters.zipWithIndex.foreach { case (setter, idx) => setter(ps, idx + 1) }
        ps.setInt(setters.size + 1, query.limit)
        ps.setInt(setters.size + 2, skip)
        Using.resource(ps.executeQuery()) { rs =>
          val buf = ListBuffer.empty[Booking]
          while rs.next() do buf += mapRow(rs)
          buf.toList
        }
      }

      PaginatedBookingsResult(rows, total)
    }

  override def findConfirmedByDateRange(startDate: Instant, endDate: Instant): List[Booking] =
    completeElapsedConfirmedBookings()
    withConnection(ds) { conn =>
      val sql =
        s"""$SelectWithRelations
            WHERE b."status" = 'confirmed'::"BookingStatus" AND b."eventDate" >= ? AND b."eventDate" < ?
            ORDER BY b."eventDate" ASC, b."startTime" ASC, b."id" ASC"""
      Using.resource(conn.prepareStatement(sql)) { ps =>
        setUtcInstant(ps, 1, startDate)
        setUtcInstant(ps, 2, endDate)
        Using.resource(ps.executeQuery()) { rs =>
          val buf = ListBuffer.empty[Booking]
          while rs.next() do buf += mapRow(rs)
          buf.toList
        }
      }
    }

  override def findOccupiedSlotsByChivaAndDate(chivaId: String, date: Instant): List[OccupiedSlotRecord] =
    val localDate = date.atZone(ZoneOffset.UTC).toLocalDate
    val start = localDate.atStartOfDay(ZoneOffset.UTC).toInstant
    val end = localDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant
    withConnection(ds) { conn =>
      val sql =
        """SELECT "startTime", "durationHours" FROM "bookings"
           WHERE "chivaId" = ?
             AND "status" IN ('pending'::"BookingStatus", 'confirmed'::"BookingStatus")
             AND "eventDate" >= ? AND "eventDate" < ?"""
      Using.resource(conn.prepareStatement(sql)) { ps =>
        ps.setString(1, chivaId)
        setUtcInstant(ps, 2, start)
        setUtcInstant(ps, 3, end)
        Using.resource(ps.executeQuery()) { rs =>
          val buf = ListBuffer.empty[OccupiedSlotRecord]
          while rs.next() do buf += OccupiedSlotRecord(rs.getString("startTime"), rs.getDouble("durationHours"))
          buf.toList
        }
      }
    }

  override def hasConflict(chivaId: String, eventDate: Instant, startTime: String, durationHours: Double): Boolean =
    val occupied = findOccupiedSlotsByChivaAndDate(chivaId, eventDate)
    val newStart = TimeSlot.toMinutes(startTime)
    val newEnd = newStart + durationHours * 60.0
    occupied.exists { slot =>
      val slotStart = TimeSlot.toMinutes(slot.startTime)
      val slotEnd = slotStart + slot.durationHours * 60.0
      newStart < slotEnd && slotStart < newEnd
    }

  override def hasFutureActiveBookingsByChiva(chivaId: String, fromDate: Instant): Boolean =
    completeElapsedConfirmedBookings(fromDate)
    val startOfToday = fromDate.atZone(ZoneOffset.UTC).toLocalDate.atStartOfDay(ZoneOffset.UTC).toInstant
    withConnection(ds) { conn =>
      val sql =
        """SELECT COUNT(*) FROM "bookings"
           WHERE "chivaId" = ?
             AND "status" IN ('pending'::"BookingStatus", 'confirmed'::"BookingStatus")
             AND "eventDate" >= ?"""
      Using.resource(conn.prepareStatement(sql)) { ps =>
        ps.setString(1, chivaId)
        setUtcInstant(ps, 2, startOfToday)
        Using.resource(ps.executeQuery()) { rs =>
          rs.next() && rs.getInt(1) > 0
        }
      }
    }

  override def saveBooking(booking: Booking): Booking =
    withConnection(ds) { conn =>
      val sql =
        """INSERT INTO "bookings" (
             "id", "chivaId", "travelId", "customerId", "customerName", "customerEmail", "customerPhone",
             "guestCount", "eventDate", "startTime", "durationHours", "totalPrice", "status",
             "notes", "calendarSyncStatus", "createdAt", "updatedAt"
           )
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::"BookingStatus", ?, 'pending'::"CalendarSyncStatus", ?, ?)"""
      Using.resource(conn.prepareStatement(sql)) { ps =>
        ps.setString(1, booking.id)
        ps.setString(2, booking.chivaId)
        ps.setString(3, booking.travel)
        ps.setString(4, booking.customerId.orNull)
        ps.setString(5, booking.customerName)
        ps.setString(6, booking.customerEmail)
        ps.setString(7, booking.customerPhone)
        ps.setInt(8, booking.guestCount)
        setUtcInstant(ps, 9, booking.eventDate)
        ps.setString(10, booking.startTime)
        ps.setDouble(11, booking.durationHours)
        ps.setDouble(12, booking.totalPrice)
        ps.setString(13, booking.status)
        ps.setString(14, booking.notes.orNull)
        setUtcInstant(ps, 15, booking.createdAt)
        setUtcInstant(ps, 16, booking.createdAt)
        ps.executeUpdate()
      }
    }
    findBookingById(booking.id).get

  override def updateBookingStatus(id: String, status: String, totalPrice: Option[Double]): Booking =
    withConnection(ds) { conn =>
      totalPrice match
        case Some(price) =>
          Using.resource(
            conn.prepareStatement(
              """UPDATE "bookings" SET "status" = ?::"BookingStatus", "totalPrice" = ?, "updatedAt" = CURRENT_TIMESTAMP WHERE "id" = ?"""
            )
          ) { ps =>
            ps.setString(1, status)
            ps.setDouble(2, price)
            ps.setString(3, id)
            ps.executeUpdate()
          }
        case None =>
          Using.resource(
            conn.prepareStatement(
              """UPDATE "bookings" SET "status" = ?::"BookingStatus", "updatedAt" = CURRENT_TIMESTAMP WHERE "id" = ?"""
            )
          ) { ps =>
            ps.setString(1, status)
            ps.setString(2, id)
            ps.executeUpdate()
          }
    }
    findBookingById(id).get

  override def updateBookingCalendarSync(
      id: String,
      googleEventId: Option[String],
      updateGoogleEventId: Boolean,
      calendarSyncStatus: String
  ): Booking =
    withConnection(ds) { conn =>
      if updateGoogleEventId then
        Using.resource(
          conn.prepareStatement(
            """UPDATE "bookings" SET "googleEventId" = ?, "calendarSyncStatus" = ?::"CalendarSyncStatus", "updatedAt" = CURRENT_TIMESTAMP WHERE "id" = ?"""
          )
        ) { ps =>
          ps.setString(1, googleEventId.orNull)
          ps.setString(2, calendarSyncStatus)
          ps.setString(3, id)
          ps.executeUpdate()
        }
      else
        Using.resource(
          conn.prepareStatement(
            """UPDATE "bookings" SET "calendarSyncStatus" = ?::"CalendarSyncStatus", "updatedAt" = CURRENT_TIMESTAMP WHERE "id" = ?"""
          )
        ) { ps =>
          ps.setString(1, calendarSyncStatus)
          ps.setString(2, id)
          ps.executeUpdate()
        }
    }
    findBookingById(id).get
