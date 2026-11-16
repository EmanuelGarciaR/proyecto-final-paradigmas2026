package com.chivasrumberas.domain.models;

import com.chivasrumberas.domain.errors.DomainException;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

public final class Models {

    public static final ZoneId BOGOTA_ZONE = ZoneId.of("America/Bogota");
    private static final Pattern TIME_REGEX = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d)$");
    private static final Pattern DATE_REGEX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final String ALPHANUMERIC_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Models() {}

    public record Chiva(
            String id,
            String name,
            String description,
            int capacity,
            List<String> imageUrls,
            double pricePerHour,
            String status,
            List<String> features,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record Travel(
            String id,
            String name,
            String description,
            double durationHours,
            List<String> highlights,
            boolean isActive
    ) {}

    public record Customer(
            String id,
            String name,
            String email,
            String phone,
            Instant createdAt
    ) {}

    public record Booking(
            String id,
            String chivaId,
            String chivaName,
            String customerId,
            String customerName,
            String customerEmail,
            String customerPhone,
            int guestCount,
            String travel,
            String travelName,
            Instant eventDate,
            String startTime,
            double durationHours,
            double totalPrice,
            String status,
            String notes,
            String googleEventId,
            String calendarSyncStatus,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record User(
            String id,
            String email,
            String passwordHash,
            String role,
            Instant createdAt
    ) {}

    public record JwtPayload(
            String sub,
            String email,
            String role
    ) {}

    public record OccupiedSlotRecord(
            String startTime,
            double durationHours
    ) {}

    public record PaginatedBookingsQuery(
            int page,
            int limit,
            String status,
            String q
    ) {}

    public record PaginatedBookingsResult(
            List<Booking> data,
            int total
    ) {}

    public record TimeSlot(String startTime, double durationHours) {
        public static TimeSlot create(String startTime, double durationHours) {
            if (startTime == null || !TIME_REGEX.matcher(startTime).matches()) {
                throw DomainException.validation("Hora inválida: \"" + startTime + "\". Use formato HH:mm (24h).");
            }
            if (!Double.isFinite(durationHours) || durationHours < 1 || durationHours > 12) {
                throw DomainException.validation("La duración debe estar entre 1 y 12 horas.");
            }
            return new TimeSlot(startTime, durationHours);
        }

        public String getEndTime() {
            String[] parts = startTime.split(":");
            int hours = Integer.parseInt(parts[0]);
            int minutes = Integer.parseInt(parts[1]);
            int totalMinutes = (int) Math.round(hours * 60 + minutes + durationHours * 60);
            int endHour = (totalMinutes / 60) % 24;
            int endMin = totalMinutes % 60;
            return String.format("%02d:%02d", endHour, endMin);
        }

        public static int toMinutes(String time) {
            String[] parts = time.split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        }
    }

    public record EventDate(Instant value, String dateString) {
        public static EventDate create(String dateStr, String startTime) {
            String rawStr = dateStr == null ? "" : dateStr.trim().split("T")[0];
            if (!DATE_REGEX.matcher(rawStr).matches()) {
                throw DomainException.validation("Fecha inválida. Use el formato YYYY-MM-DD.");
            }
            LocalDate localDate;
            try {
                localDate = LocalDate.parse(rawStr);
            } catch (DateTimeParseException ex) {
                throw DomainException.validation("Fecha inválida.");
            }

            Instant parsedDateOnly = localDate.atStartOfDay(ZoneOffset.UTC).toInstant();
            String timePart = (startTime != null && TIME_REGEX.matcher(startTime.trim()).matches())
                    ? startTime.trim()
                    : "23:59";

            OffsetDateTime eventMomentBogota = OffsetDateTime.parse(rawStr + "T" + timePart + ":00-05:00");
            long minAdvanceMs = 24L * 60L * 60L * 1000L;
            if (eventMomentBogota.toInstant().toEpochMilli() - Instant.now().toEpochMilli() < minAdvanceMs) {
                throw DomainException.validation(
                        "Debes agendar tu reserva con más de un día de anticipación (mínimo 24 horas antes del evento)."
                );
            }
            return new EventDate(parsedDateOnly, rawStr);
        }
    }

    public static void validateGuestCount(int count, int chivaCapacity) {
        if (count < 1) {
            throw DomainException.validation("La cantidad de invitados debe ser un número entero mayor a 0.");
        }
        if (count > chivaCapacity) {
            throw DomainException.validation(
                    "La cantidad de invitados (" + count + ") supera la capacidad de la chiva (" + chivaCapacity + ")."
            );
        }
    }

    public static boolean hasBookingDateTimePassed(Instant eventDate, String startTime, Instant now) {
        String dateStr = toUtcDateString(eventDate);
        String timeStr = (startTime != null && TIME_REGEX.matcher(startTime.trim()).matches())
                ? startTime.trim()
                : "00:00";
        OffsetDateTime scheduledBogota = OffsetDateTime.parse(dateStr + "T" + timeStr + ":00-05:00");
        return !scheduledBogota.toInstant().isAfter(now);
    }

    private static final DateTimeFormatter ISO_MILLIS_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    public static String toIsoString(Instant instant) {
        return ISO_MILLIS_UTC.format(instant);
    }

    public static String toUtcDateString(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public static String formatEventDateBogota(Instant date) {
        ZonedDateTime utc = date.atZone(ZoneOffset.UTC);
        if (utc.getHour() == 0 && utc.getMinute() == 0 && utc.getSecond() == 0 && utc.getNano() == 0) {
            return utc.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        return date.atZone(BOGOTA_ZONE).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    public static String generateBookingCode(int length) {
        StringBuilder sb = new StringBuilder("CR-");
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        for (int i = 0; i < length; i++) {
            int idx = Byte.toUnsignedInt(bytes[i]) % ALPHANUMERIC_CHARS.length();
            sb.append(ALPHANUMERIC_CHARS.charAt(idx));
        }
        return sb.toString();
    }

    public static String slugify(String text) {
        if (text == null) return "";
        String normalized = Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("[\\p{InCombiningDiacriticalMarks}]", "");
        return normalized
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }
}
