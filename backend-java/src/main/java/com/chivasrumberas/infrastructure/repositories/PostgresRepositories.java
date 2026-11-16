package com.chivasrumberas.infrastructure.repositories;

import com.chivasrumberas.domain.models.Models;
import com.chivasrumberas.domain.models.Models.*;
import com.chivasrumberas.domain.repositories.Repositories.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Configuration
public class PostgresRepositories {

    private static List<String> toStringList(Array sqlArray) throws SQLException {
        if (sqlArray == null) return List.of();
        Object raw = sqlArray.getArray();
        if (raw instanceof String[] strArr) {
            return Arrays.asList(strArr);
        }
        if (raw instanceof Object[] objArr) {
            return Arrays.stream(objArr).map(String::valueOf).toList();
        }
        return List.of();
    }

    private static Array createTextArray(JdbcTemplate jdbcTemplate, List<String> items) {
        List<String> safe = items == null ? List.of() : items;
        return jdbcTemplate.execute((Connection con) -> con.createArrayOf("text", safe.toArray(new String[0])));
    }

    private static Instant getUtcInstant(ResultSet rs, String col) throws SQLException {
        LocalDateTime ldt = rs.getObject(col, LocalDateTime.class);
        return ldt != null ? ldt.toInstant(ZoneOffset.UTC) : Instant.EPOCH;
    }

    private static LocalDateTime toUtcLdt(Instant instant) {
        return LocalDateTime.ofInstant(instant != null ? instant : Instant.now(), ZoneOffset.UTC);
    }

    @Repository
    public static class PgChivaRepository implements ChivaRepository {
        private final JdbcTemplate jdbc;

        public PgChivaRepository(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        private final RowMapper<Chiva> mapper = (rs, rowNum) -> new Chiva(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("capacity"),
                toStringList(rs.getArray("imageUrls")),
                rs.getDouble("pricePerHour"),
                rs.getString("status"),
                toStringList(rs.getArray("features")),
                getUtcInstant(rs, "createdAt"),
                getUtcInstant(rs, "updatedAt")
        );

        @Override
        public List<Chiva> findChivaAll(String status) {
            if (status != null && !status.isBlank()) {
                return jdbc.query(
                        "SELECT * FROM \"chivas\" WHERE \"status\" = ?::\"ChivaStatus\" ORDER BY \"createdAt\" ASC",
                        mapper,
                        status
                );
            }
            return jdbc.query("SELECT * FROM \"chivas\" ORDER BY \"createdAt\" ASC", mapper);
        }

        @Override
        public Optional<Chiva> findChivaById(String id) {
            List<Chiva> rows = jdbc.query("SELECT * FROM \"chivas\" WHERE \"id\" = ?", mapper, id);
            return rows.stream().findFirst();
        }

        @Override
        public Chiva saveChiva(Chiva chiva) {
            Array imagesArr = createTextArray(jdbc, chiva.imageUrls());
            Array featuresArr = createTextArray(jdbc, chiva.features());
            LocalDateTime now = toUtcLdt(chiva.createdAt());

            jdbc.update(
                    """
                    INSERT INTO "chivas" ("id", "name", "description", "capacity", "imageUrls", "pricePerHour", "status", "features", "createdAt", "updatedAt")
                    VALUES (?, ?, ?, ?, ?, ?, ?::"ChivaStatus", ?, ?, ?)
                    """,
                    chiva.id(),
                    chiva.name(),
                    chiva.description(),
                    chiva.capacity(),
                    imagesArr,
                    chiva.pricePerHour(),
                    chiva.status(),
                    featuresArr,
                    now,
                    now
            );
            return findChivaById(chiva.id()).orElseThrow();
        }

        @Override
        public Chiva updateChivaStatus(String id, String status) {
            jdbc.update(
                    "UPDATE \"chivas\" SET \"status\" = ?::\"ChivaStatus\", \"updatedAt\" = CURRENT_TIMESTAMP WHERE \"id\" = ?",
                    status,
                    id
            );
            return findChivaById(id).orElseThrow();
        }

        @Override
        @SuppressWarnings("unchecked")
        public Chiva updateChiva(String id, Map<String, Object> updates) {
            List<String> setClauses = new ArrayList<>();
            List<Object> params = new ArrayList<>();

            if (updates.containsKey("name")) {
                setClauses.add("\"name\" = ?");
                params.add(updates.get("name"));
            }
            if (updates.containsKey("description")) {
                setClauses.add("\"description\" = ?");
                params.add(updates.get("description"));
            }
            if (updates.containsKey("capacity")) {
                setClauses.add("\"capacity\" = ?");
                params.add(updates.get("capacity"));
            }
            if (updates.containsKey("pricePerHour")) {
                setClauses.add("\"pricePerHour\" = ?");
                params.add(updates.get("pricePerHour"));
            }
            if (updates.containsKey("status")) {
                setClauses.add("\"status\" = ?::\"ChivaStatus\"");
                params.add(updates.get("status"));
            }
            if (updates.containsKey("features")) {
                setClauses.add("\"features\" = ?");
                params.add(createTextArray(jdbc, (List<String>) updates.get("features")));
            }
            if (updates.containsKey("imageUrls")) {
                setClauses.add("\"imageUrls\" = ?");
                params.add(createTextArray(jdbc, (List<String>) updates.get("imageUrls")));
            }

            setClauses.add("\"updatedAt\" = CURRENT_TIMESTAMP");
            params.add(id);

            String sql = "UPDATE \"chivas\" SET " + String.join(", ", setClauses) + " WHERE \"id\" = ?";
            jdbc.update(sql, params.toArray());
            return findChivaById(id).orElseThrow();
        }
    }

    @Repository
    public static class PgTravelRepository implements TravelRepository {
        private final JdbcTemplate jdbc;

        public PgTravelRepository(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        private final RowMapper<Travel> mapper = (rs, rowNum) -> new Travel(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getDouble("durationHours"),
                toStringList(rs.getArray("highlights")),
                rs.getBoolean("isActive")
        );

        @Override
        public List<Travel> findAllTravelsActive() {
            return jdbc.query("SELECT * FROM \"travels\" WHERE \"isActive\" = true ORDER BY \"name\" ASC", mapper);
        }

        @Override
        public Optional<Travel> findTravelById(String id) {
            List<Travel> rows = jdbc.query("SELECT * FROM \"travels\" WHERE \"id\" = ?", mapper, id);
            return rows.stream().findFirst();
        }
    }

    @Repository
    public static class PgCustomerRepository implements CustomerRepository {
        private final JdbcTemplate jdbc;

        public PgCustomerRepository(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        private final RowMapper<Customer> mapper = (rs, rowNum) -> new Customer(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("email"),
                rs.getString("phone"),
                getUtcInstant(rs, "createdAt")
        );

        @Override
        public Optional<Customer> findByEmail(String email) {
            String normalized = email.trim().toLowerCase();
            List<Customer> rows = jdbc.query("SELECT * FROM \"customers\" WHERE \"email\" = ?", mapper, normalized);
            return rows.stream().findFirst();
        }

        @Override
        public Optional<Customer> findById(String id) {
            List<Customer> rows = jdbc.query("SELECT * FROM \"customers\" WHERE \"id\" = ?", mapper, id);
            return rows.stream().findFirst();
        }

        @Override
        public Customer upsertByEmail(String name, String email, String phone) {
            String normalizedEmail = email.trim().toLowerCase();
            String trimmedName = name.trim();
            String trimmedPhone = phone.trim();
            String newId = UUID.randomUUID().toString();

            jdbc.update(
                    """
                    INSERT INTO "customers" ("id", "name", "email", "phone", "createdAt")
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT ("email") DO UPDATE
                    SET "name" = EXCLUDED."name",
                        "phone" = EXCLUDED."phone"
                    """,
                    newId,
                    trimmedName,
                    normalizedEmail,
                    trimmedPhone
            );
            return findByEmail(normalizedEmail).orElseThrow();
        }
    }

    @Repository
    public static class PgUserRepository implements UserRepository {
        private final JdbcTemplate jdbc;

        public PgUserRepository(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        private final RowMapper<User> mapper = (rs, rowNum) -> new User(
                rs.getString("id"),
                rs.getString("email"),
                rs.getString("passwordHash"),
                rs.getString("role"),
                getUtcInstant(rs, "createdAt")
        );

        @Override
        public Optional<User> findUserByEmail(String email) {
            List<User> rows = jdbc.query("SELECT * FROM \"users\" WHERE \"email\" = ?", mapper, email);
            return rows.stream().findFirst();
        }

        @Override
        public Optional<User> findUserById(String id) {
            List<User> rows = jdbc.query("SELECT * FROM \"users\" WHERE \"id\" = ?", mapper, id);
            return rows.stream().findFirst();
        }
    }

    @Repository
    public static class PgBookingRepository implements BookingRepository {
        private final JdbcTemplate jdbc;

        public PgBookingRepository(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        private static final String SELECT_WITH_RELATIONS = """
                SELECT b.*, c."name" AS "chivaName", t."name" AS "travelName"
                FROM "bookings" b
                LEFT JOIN "chivas" c ON c."id" = b."chivaId"
                LEFT JOIN "travels" t ON t."id" = b."travelId"
                """;

        private final RowMapper<Booking> mapper = (ResultSet rs, int rowNum) -> new Booking(
                rs.getString("id"),
                rs.getString("chivaId"),
                rs.getString("chivaName"),
                rs.getString("customerId"),
                rs.getString("customerName"),
                rs.getString("customerEmail"),
                rs.getString("customerPhone"),
                rs.getInt("guestCount"),
                rs.getString("travelId"),
                rs.getString("travelName"),
                getUtcInstant(rs, "eventDate"),
                rs.getString("startTime"),
                rs.getDouble("durationHours"),
                rs.getDouble("totalPrice"),
                rs.getString("status"),
                rs.getString("notes"),
                rs.getString("googleEventId"),
                rs.getString("calendarSyncStatus") != null ? rs.getString("calendarSyncStatus") : "pending",
                getUtcInstant(rs, "createdAt"),
                getUtcInstant(rs, "updatedAt")
        );

        private record ElapsedCandidate(String id, Instant eventDate, String startTime) {}

        private void completeElapsedConfirmedBookings(Instant now) {
            Instant upperBound = now.plusSeconds(24L * 60L * 60L);
            List<ElapsedCandidate> candidates = jdbc.query(
                    """
                    SELECT "id", "eventDate", "startTime"
                    FROM "bookings"
                    WHERE "status" = 'confirmed'::"BookingStatus" AND "eventDate" <= ?
                    """,
                    (rs, i) -> new ElapsedCandidate(
                            rs.getString("id"),
                            getUtcInstant(rs, "eventDate"),
                            rs.getString("startTime")
                    ),
                    toUtcLdt(upperBound)
            );

            if (candidates.isEmpty()) return;

            List<String> elapsedIds = candidates.stream()
                    .filter(row -> Models.hasBookingDateTimePassed(row.eventDate(), row.startTime(), now))
                    .map(ElapsedCandidate::id)
                    .toList();

            if (!elapsedIds.isEmpty()) {
                String placeholders = String.join(",", Collections.nCopies(elapsedIds.size(), "?"));
                String sql = "UPDATE \"bookings\" SET \"status\" = 'completed'::\"BookingStatus\", \"updatedAt\" = CURRENT_TIMESTAMP " +
                             "WHERE \"status\" = 'confirmed'::\"BookingStatus\" AND \"id\" IN (" + placeholders + ")";
                jdbc.update(sql, elapsedIds.toArray());
            }
        }

        @Override
        public Optional<Booking> findBookingById(String id) {
            completeElapsedConfirmedBookings(Instant.now());
            List<Booking> rows = jdbc.query(
                    SELECT_WITH_RELATIONS + " WHERE b.\"id\" = ?",
                    mapper,
                    id
            );
            return rows.stream().findFirst();
        }

        @Override
        public PaginatedBookingsResult findBookingPaginated(PaginatedBookingsQuery query) {
            completeElapsedConfirmedBookings(Instant.now());
            List<String> whereClauses = new ArrayList<>();
            List<Object> params = new ArrayList<>();

            if (query.status() != null && !query.status().isBlank()) {
                whereClauses.add("b.\"status\" = ?::\"BookingStatus\"");
                params.add(query.status());
            }

            if (query.q() != null && !query.q().isBlank()) {
                String pattern = "%" + query.q().trim() + "%";
                whereClauses.add("(b.\"id\" ILIKE ? OR b.\"customerName\" ILIKE ? OR b.\"customerEmail\" ILIKE ?)");
                params.add(pattern);
                params.add(pattern);
                params.add(pattern);
            }

            String whereSql = whereClauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", whereClauses);

            Integer total = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM \"bookings\" b" + whereSql,
                    Integer.class,
                    params.toArray()
            );

            int skip = (query.page() - 1) * query.limit();
            List<Object> dataParams = new ArrayList<>(params);
            dataParams.add(query.limit());
            dataParams.add(skip);

            List<Booking> rows = jdbc.query(
                    SELECT_WITH_RELATIONS + whereSql + " ORDER BY b.\"createdAt\" DESC, b.\"id\" DESC LIMIT ? OFFSET ?",
                    mapper,
                    dataParams.toArray()
            );

            return new PaginatedBookingsResult(rows, total != null ? total : 0);
        }

        @Override
        public List<Booking> findConfirmedByDateRange(Instant startDate, Instant endDate) {
            completeElapsedConfirmedBookings(Instant.now());
            return jdbc.query(
                    SELECT_WITH_RELATIONS +
                    " WHERE b.\"status\" = 'confirmed'::\"BookingStatus\" AND b.\"eventDate\" >= ? AND b.\"eventDate\" < ? " +
                    " ORDER BY b.\"eventDate\" ASC, b.\"startTime\" ASC, b.\"id\" ASC",
                    mapper,
                    toUtcLdt(startDate),
                    toUtcLdt(endDate)
            );
        }

        @Override
        public List<OccupiedSlotRecord> findOccupiedSlotsByChivaAndDate(String chivaId, Instant date) {
            LocalDate localDate = date.atZone(ZoneOffset.UTC).toLocalDate();
            Instant start = localDate.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant end = localDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

            return jdbc.query(
                    """
                    SELECT "startTime", "durationHours"
                    FROM "bookings"
                    WHERE "chivaId" = ?
                      AND "status" IN ('pending'::"BookingStatus", 'confirmed'::"BookingStatus")
                      AND "eventDate" >= ?
                      AND "eventDate" < ?
                    """,
                    (rs, i) -> new OccupiedSlotRecord(rs.getString("startTime"), rs.getDouble("durationHours")),
                    chivaId,
                    toUtcLdt(start),
                    toUtcLdt(end)
            );
        }

        @Override
        public boolean hasConflict(String chivaId, Instant eventDate, String startTime, double durationHours) {
            List<OccupiedSlotRecord> occupied = findOccupiedSlotsByChivaAndDate(chivaId, eventDate);
            int newStart = TimeSlot.toMinutes(startTime);
            double newEnd = newStart + durationHours * 60.0;

            return occupied.stream().anyMatch(slot -> {
                int slotStart = TimeSlot.toMinutes(slot.startTime());
                double slotEnd = slotStart + slot.durationHours() * 60.0;
                return newStart < slotEnd && slotStart < newEnd;
            });
        }

        @Override
        public boolean hasFutureActiveBookingsByChiva(String chivaId, Instant fromDate) {
            completeElapsedConfirmedBookings(fromDate);
            LocalDate todayUtc = fromDate.atZone(ZoneOffset.UTC).toLocalDate();
            Instant startOfToday = todayUtc.atStartOfDay(ZoneOffset.UTC).toInstant();

            Integer count = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM "bookings"
                    WHERE "chivaId" = ?
                      AND "status" IN ('pending'::"BookingStatus", 'confirmed'::"BookingStatus")
                      AND "eventDate" >= ?
                    """,
                    Integer.class,
                    chivaId,
                    toUtcLdt(startOfToday)
            );
            return count != null && count > 0;
        }

        @Override
        public Booking saveBooking(Booking booking) {
            LocalDateTime now = toUtcLdt(booking.createdAt());
            jdbc.update(
                    """
                    INSERT INTO "bookings" (
                      "id", "chivaId", "travelId", "customerId", "customerName", "customerEmail", "customerPhone",
                      "guestCount", "eventDate", "startTime", "durationHours", "totalPrice", "status",
                      "notes", "calendarSyncStatus", "createdAt", "updatedAt"
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::"BookingStatus", ?, 'pending'::"CalendarSyncStatus", ?, ?)
                    """,
                    booking.id(),
                    booking.chivaId(),
                    booking.travel(),
                    booking.customerId(),
                    booking.customerName(),
                    booking.customerEmail(),
                    booking.customerPhone(),
                    booking.guestCount(),
                    toUtcLdt(booking.eventDate()),
                    booking.startTime(),
                    booking.durationHours(),
                    booking.totalPrice(),
                    booking.status(),
                    booking.notes(),
                    now,
                    now
            );
            return findBookingById(booking.id()).orElseThrow();
        }

        @Override
        public Booking updateBookingStatus(String id, String status, Double totalPrice) {
            if (totalPrice != null) {
                jdbc.update(
                        """
                        UPDATE "bookings"
                        SET "status" = ?::"BookingStatus",
                            "totalPrice" = ?,
                            "updatedAt" = CURRENT_TIMESTAMP
                        WHERE "id" = ?
                        """,
                        status,
                        totalPrice,
                        id
                );
            } else {
                jdbc.update(
                        """
                        UPDATE "bookings"
                        SET "status" = ?::"BookingStatus",
                            "updatedAt" = CURRENT_TIMESTAMP
                        WHERE "id" = ?
                        """,
                        status,
                        id
                );
            }
            return findBookingById(id).orElseThrow();
        }

        @Override
        public Booking updateBookingCalendarSync(String id, String googleEventId, boolean updateGoogleEventId, String calendarSyncStatus) {
            if (updateGoogleEventId) {
                jdbc.update(
                        """
                        UPDATE "bookings"
                        SET "googleEventId" = ?,
                            "calendarSyncStatus" = ?::"CalendarSyncStatus",
                            "updatedAt" = CURRENT_TIMESTAMP
                        WHERE "id" = ?
                        """,
                        googleEventId,
                        calendarSyncStatus,
                        id
                );
            } else {
                jdbc.update(
                        """
                        UPDATE "bookings"
                        SET "calendarSyncStatus" = ?::"CalendarSyncStatus",
                            "updatedAt" = CURRENT_TIMESTAMP
                        WHERE "id" = ?
                        """,
                        calendarSyncStatus,
                        id
                );
            }
            return findBookingById(id).orElseThrow();
        }
    }
}
