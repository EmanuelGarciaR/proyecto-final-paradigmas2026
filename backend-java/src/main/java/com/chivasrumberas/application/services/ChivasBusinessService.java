package com.chivasrumberas.application.services;

import com.chivasrumberas.domain.errors.DomainException;
import com.chivasrumberas.domain.models.Models;
import com.chivasrumberas.domain.models.Models.*;
import com.chivasrumberas.domain.repositories.Repositories.*;
import com.chivasrumberas.infrastructure.services.ExternalServices.*;
import com.chivasrumberas.web.ApiDtos.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ChivasBusinessService {

    private static final Set<String> CHIVA_STATUSES = Set.of("available", "maintenance", "retired");
    private static final Set<String> BOOKING_STATUSES = Set.of("pending", "confirmed", "cancelled", "completed");
    private static final Set<String> ACCEPTED_UPDATE_BOOKING_STATUSES = Set.of("confirmed", "cancelled", "completed");
    private static final Map<String, Set<String>> VALID_TRANSITIONS = Map.of(
            "pending", Set.of("confirmed", "cancelled"),
            "confirmed", Set.of("completed", "cancelled"),
            "cancelled", Set.of(),
            "completed", Set.of()
    );

    private static final Pattern MONTH_REGEX = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");
    private static final Pattern EMAIL_REGEX = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final double OPERATIONAL_START_MINUTES = 8.0 * 60.0; // 480
    private static final double OPERATIONAL_END_MINUTES = 22.0 * 60.0;  // 1320

    private final ChivaRepository chivaRepository;
    private final TravelRepository travelRepository;
    private final CustomerRepository customerRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final JwtTokenService jwtService;
    private final NotificationPort notificationService;
    private final CalendarPort calendarService;
    private final ImageStoragePort imageStorageService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public ChivasBusinessService(
            ChivaRepository chivaRepository,
            TravelRepository travelRepository,
            CustomerRepository customerRepository,
            BookingRepository bookingRepository,
            UserRepository userRepository,
            JwtTokenService jwtService,
            NotificationPort notificationService,
            CalendarPort calendarService,
            ImageStoragePort imageStorageService
    ) {
        this.chivaRepository = chivaRepository;
        this.travelRepository = travelRepository;
        this.customerRepository = customerRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.notificationService = notificationService;
        this.calendarService = calendarService;
        this.imageStorageService = imageStorageService;
    }

    // ── 1. Auth ────────────────────────────────────────────────────────────────
    public AuthResponseDto login(LoginDto dto) {
        if (dto == null || dto.email() == null || dto.password() == null) {
            throw DomainException.invalidCredentials();
        }
        User user = userRepository.findUserByEmail(dto.email())
                .orElseThrow(DomainException::invalidCredentials);

        String normalizedHash = user.passwordHash().startsWith("$2b$")
                ? "$2a$" + user.passwordHash().substring(4)
                : user.passwordHash();

        if (!passwordEncoder.matches(dto.password(), normalizedHash)) {
            throw DomainException.invalidCredentials();
        }

        String token = jwtService.sign(new JwtPayload(user.id(), user.email(), user.role()));
        return new AuthResponseDto(token, new AuthUserDto(user.id(), user.email(), user.role()));
    }

    // ── 2. Chivas ──────────────────────────────────────────────────────────────
    private static final Function<Chiva, ChivaResponseDto> TO_CHIVA_DTO = chiva -> new ChivaResponseDto(
            chiva.id(),
            chiva.name(),
            chiva.description(),
            chiva.capacity(),
            chiva.imageUrls(),
            chiva.pricePerHour(),
            chiva.status(),
            chiva.features()
    );

    public List<ChivaResponseDto> getChivas(String status) {
        return chivaRepository.findChivaAll(status).stream()
                .map(TO_CHIVA_DTO)
                .toList();
    }

    public ChivaResponseDto getChivaById(String id) {
        return chivaRepository.findChivaById(id)
                .map(TO_CHIVA_DTO)
                .orElseThrow(() -> DomainException.chivaNotFound(id));
    }

    @SuppressWarnings("unchecked")
    public ChivaResponseDto createChiva(Map<String, Object> body) {
        if (body == null) {
            throw DomainException.validation("Datos de chiva inválidos.");
        }
        Object rawName = body.get("name");
        if (!(rawName instanceof String nameStr) || nameStr.trim().isEmpty()) {
            throw DomainException.validation("El nombre es requerido.");
        }
        Object rawDesc = body.get("description");
        if (!(rawDesc instanceof String descStr) || descStr.trim().isEmpty()) {
            throw DomainException.validation("La descripción es requerida.");
        }
        int capacity = parsePositiveInt(body.get("capacity"), "capacity debe ser un número entero mayor que cero.");
        double pricePerHour = parsePositiveDouble(body.get("pricePerHour"), "pricePerHour debe ser mayor que cero.");

        String status = "available";
        if (body.get("status") != null) {
            if (!(body.get("status") instanceof String st) || !CHIVA_STATUSES.contains(st)) {
                throw DomainException.validation("El estado de la chiva no es válido.");
            }
            status = st;
        }

        List<String> features = List.of();
        if (body.get("features") != null) {
            if (!(body.get("features") instanceof List<?> rawList)) {
                throw DomainException.validation("features debe ser un arreglo de textos.");
            }
            features = rawList.stream().map( obj -> {
                if (!(obj instanceof String s) || s.trim().isEmpty()) {
                    throw DomainException.validation("Cada elemento de features debe ser un texto no vacío.");
                }
                return s.trim();
            }).toList();
        }

        List<String> imageUrls = List.of();
        if (body.get("imageUrls") != null) {
            if (!(body.get("imageUrls") instanceof List<?> rawUrls)) {
                throw DomainException.validation("imageUrls debe ser un arreglo.");
            }
            if (rawUrls.size() > 10) {
                throw DomainException.validation("Máximo 10 fotos permitidas por chiva.");
            }
            imageUrls = rawUrls.stream().map(obj -> {
                if (!(obj instanceof String u) || !isValidHttpUrl(u)) {
                    throw DomainException.validation("Cada elemento de imageUrls debe ser una URL válida.");
                }
                return u;
            }).toList();
        }

        String baseSlug = Models.slugify(nameStr.trim());
        if (baseSlug.isEmpty()) {
            throw DomainException.validation("El nombre proporcionado no genera un identificador válido.");
        }

        String id = baseSlug;
        int suffix = 2;
        while (chivaRepository.findChivaById(id).isPresent()) {
            id = baseSlug + "-" + suffix;
            suffix++;
        }

        Instant now = Instant.now();
        Chiva created = chivaRepository.saveChiva(new Chiva(
                id,
                nameStr.trim(),
                descStr.trim(),
                capacity,
                imageUrls,
                pricePerHour,
                status,
                features,
                now,
                now
        ));
        return TO_CHIVA_DTO.apply(created);
    }

    public ChivaResponseDto updateChiva(String id, Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw DomainException.validation("Debe proporcionar al menos un campo para actualizar.");
        }

        Map<String, Object> cleanUpdates = new LinkedHashMap<>();

        if (body.containsKey("name") && body.get("name") != null) {
            if (!(body.get("name") instanceof String s) || s.trim().isEmpty()) {
                throw DomainException.validation("El nombre no puede estar vacío.");
            }
            cleanUpdates.put("name", s.trim());
        }
        if (body.containsKey("description") && body.get("description") != null) {
            if (!(body.get("description") instanceof String s) || s.trim().isEmpty()) {
                throw DomainException.validation("La descripción no puede estar vacía.");
            }
            cleanUpdates.put("description", s.trim());
        }
        if (body.containsKey("capacity") && body.get("capacity") != null) {
            cleanUpdates.put("capacity", parsePositiveInt(body.get("capacity"), "capacity debe ser un número entero."));
        }
        if (body.containsKey("pricePerHour") && body.get("pricePerHour") != null) {
            cleanUpdates.put("pricePerHour", parsePositiveDouble(body.get("pricePerHour"), "pricePerHour debe ser mayor que cero."));
        }
        if (body.containsKey("status") && body.get("status") != null) {
            if (!(body.get("status") instanceof String st) || !CHIVA_STATUSES.contains(st)) {
                throw DomainException.validation("El estado de la chiva no es válido.");
            }
            cleanUpdates.put("status", st);
        }
        if (body.containsKey("features") && body.get("features") != null) {
            if (!(body.get("features") instanceof List<?> rawList)) {
                throw DomainException.validation("features debe ser un arreglo.");
            }
            List<String> features = rawList.stream().map(obj -> {
                if (!(obj instanceof String s) || s.trim().isEmpty()) {
                    throw DomainException.validation("features inválido.");
                }
                return s.trim();
            }).toList();
            cleanUpdates.put("features", features);
        }
        if (body.containsKey("imageUrls") && body.get("imageUrls") != null) {
            if (!(body.get("imageUrls") instanceof List<?> rawUrls) || rawUrls.size() > 10) {
                throw DomainException.validation("Máximo 10 fotos permitidas por chiva.");
            }
            List<String> urls = rawUrls.stream().map(obj -> {
                if (!(obj instanceof String u) || !isValidHttpUrl(u)) {
                    throw DomainException.validation("Cada elemento de imageUrls debe ser una URL válida.");
                }
                return u;
            }).toList();
            cleanUpdates.put("imageUrls", urls);
        }

        if (cleanUpdates.isEmpty()) {
            throw DomainException.validation("Debe proporcionar al menos un campo para actualizar.");
        }

        chivaRepository.findChivaById(id).orElseThrow(() -> DomainException.chivaNotFound(id));
        Chiva updated = chivaRepository.updateChiva(id, cleanUpdates);
        return TO_CHIVA_DTO.apply(updated);
    }

    public ChivaResponseDto updateChivaStatus(String id, Map<String, Object> body) {
        if (id == null || id.trim().isEmpty()) {
            throw DomainException.validation("El id de la chiva es requerido.");
        }
        Object rawStatus = body != null ? body.get("status") : null;
        if (rawStatus == null || (rawStatus instanceof String s && s.isEmpty())) {
            throw DomainException.validation("El campo status es requerido.");
        }
        if (!(rawStatus instanceof String statusStr) || !CHIVA_STATUSES.contains(statusStr)) {
            throw DomainException.validation("El estado de la chiva no es válido.");
        }

        chivaRepository.findChivaById(id).orElseThrow(() -> DomainException.chivaNotFound(id));
        Chiva updated = chivaRepository.updateChivaStatus(id, statusStr);
        return TO_CHIVA_DTO.apply(updated);
    }

    public ChivaResponseDto deleteChiva(String id) {
        if (id == null || id.trim().isEmpty()) {
            throw DomainException.validation("El id de la chiva es requerido.");
        }
        chivaRepository.findChivaById(id).orElseThrow(() -> DomainException.chivaNotFound(id));
        if (bookingRepository.hasFutureActiveBookingsByChiva(id, Instant.now())) {
            throw DomainException.chivaHasActiveBookings(id);
        }
        Chiva updated = chivaRepository.updateChivaStatus(id, "retired");
        return TO_CHIVA_DTO.apply(updated);
    }

    // ── 3. Travels ─────────────────────────────────────────────────────────────
    public List<TravelResponseDto> getTravels() {
        return travelRepository.findAllTravelsActive().stream()
                .map(t -> new TravelResponseDto(t.id(), t.name(), t.description(), t.durationHours(), t.highlights()))
                .toList();
    }

    // ── 4. Bookings & Functional Programming Logic ────────────────────────────
    private BookingResponseDto toBookingResponseDto(Booking b) {
        String chivaName = b.chivaName() != null
                ? b.chivaName()
                : chivaRepository.findChivaById(b.chivaId()).map(Chiva::name).orElse("");
        String travelName = b.travelName() != null
                ? b.travelName()
                : travelRepository.findTravelById(b.travel()).map(Travel::name).orElse(b.travel());

        TimeSlot ts = TimeSlot.create(b.startTime(), b.durationHours());
        return new BookingResponseDto(
                b.id(),
                b.chivaId(),
                chivaName,
                b.travel(),
                travelName,
                b.customerName(),
                b.customerEmail(),
                b.customerPhone(),
                b.guestCount(),
                Models.toUtcDateString(b.eventDate()),
                b.startTime(),
                ts.getEndTime(),
                b.durationHours(),
                b.totalPrice(),
                b.status(),
                b.notes(),
                b.calendarSyncStatus() != null ? b.calendarSyncStatus() : "pending",
                Models.toIsoString(b.createdAt())
        );
    }

    /**
     * Programación Funcional: map + reduce para calcular los slots ocupados y si cubre
     * la totalidad de la ventana operativa (08:00 a 22:00 = 840 minutos).
     */
    public AvailabilityResponseDto getAvailability(String chivaId, String dateStr) {
        if (chivaId == null || chivaId.isBlank() || dateStr == null || dateStr.isBlank()) {
            throw DomainException.validation("chivaId y date son requeridos.");
        }
        chivaRepository.findChivaById(chivaId).orElseThrow(() -> DomainException.chivaNotFound(chivaId));

        Instant dateInstant = parseLooseIsoDate(dateStr);
        List<OccupiedSlotRecord> rawSlots = bookingRepository.findOccupiedSlotsByChivaAndDate(chivaId, dateInstant);

        List<OccupiedSlotDto> occupiedSlots = rawSlots.stream()
                .map(slot -> {
                    TimeSlot ts = TimeSlot.create(slot.startTime(), slot.durationHours());
                    return new OccupiedSlotDto(ts.startTime(), ts.getEndTime(), ts.durationHours());
                })
                .toList();

        double totalOperationalMinutes = OPERATIONAL_END_MINUTES - OPERATIONAL_START_MINUTES;
        double bookedMinutes = occupiedSlots.stream()
                .map(slot -> {
                    double slotStart = TimeSlot.toMinutes(slot.startTime());
                    double slotEnd = slotStart + slot.durationHours() * 60.0;
                    double start = Math.max(slotStart, OPERATIONAL_START_MINUTES);
                    double end = Math.min(slotEnd, OPERATIONAL_END_MINUTES);
                    return Math.max(0.0, end - start);
                })
                .reduce(0.0, Double::sum);

        boolean isFullyBooked = bookedMinutes >= totalOperationalMinutes;
        return new AvailabilityResponseDto(chivaId, dateStr, occupiedSlots, isFullyBooked);
    }

    public TrackBookingResponseDto trackBooking(String rawCode, String rawEmail) {
        if (rawCode == null || rawCode.trim().isEmpty()) {
            throw DomainException.validation("El parámetro code es requerido.");
        }
        if (rawEmail == null || rawEmail.trim().isEmpty()) {
            throw DomainException.validation("El parámetro email es requerido.");
        }
        String email = rawEmail.trim().toLowerCase();
        if (!EMAIL_REGEX.matcher(email).matches()) {
            throw DomainException.validation("El parámetro email debe ser un correo válido.");
        }
        String code = rawCode.trim().toUpperCase();

        Booking booking = bookingRepository.findBookingById(code)
                .filter(b -> b.customerEmail() != null && b.customerEmail().trim().toLowerCase().equals(email))
                .orElseThrow(() -> DomainException.bookingNotFound(null));

        String chivaName = booking.chivaName() != null
                ? booking.chivaName()
                : chivaRepository.findChivaById(booking.chivaId()).map(Chiva::name).orElse("");
        String travelName = booking.travelName() != null
                ? booking.travelName()
                : travelRepository.findTravelById(booking.travel()).map(Travel::name).orElse("");

        TimeSlot ts = TimeSlot.create(booking.startTime(), booking.durationHours());
        return new TrackBookingResponseDto(
                booking.id(),
                booking.id(),
                booking.status(),
                Models.toIsoString(booking.createdAt()),
                booking.chivaId(),
                chivaName,
                booking.travel(),
                travelName,
                Models.toUtcDateString(booking.eventDate()),
                booking.startTime(),
                ts.getEndTime(),
                booking.durationHours(),
                booking.guestCount(),
                booking.totalPrice()
        );
    }

    public BookingResponseDto createBooking(CreateBookingDto dto) {
        if (dto == null) {
            throw DomainException.validation("Datos de reserva requeridos.");
        }
        Chiva chiva = chivaRepository.findChivaById(dto.chivaId())
                .orElseThrow(() -> DomainException.chivaNotFound(dto.chivaId()));
        if (!"available".equals(chiva.status())) {
            throw DomainException.chivaNotAvailable(dto.chivaId());
        }

        Travel travel = travelRepository.findTravelById(dto.travelId())
                .orElseThrow(() -> DomainException.travelNotFound(dto.travelId()));

        double durationHours = dto.durationHours() != null ? dto.durationHours() : 0.0;
        int guestCount = dto.guestCount() != null ? dto.guestCount() : 0;

        TimeSlot timeSlot = TimeSlot.create(dto.startTime(), durationHours);
        EventDate eventDate = EventDate.create(dto.eventDate(), timeSlot.startTime());
        Models.validateGuestCount(guestCount, chiva.capacity());

        if (bookingRepository.hasConflict(dto.chivaId(), eventDate.value(), timeSlot.startTime(), timeSlot.durationHours())) {
            throw DomainException.bookingConflict(dto.chivaId(), eventDate.dateString(), timeSlot.startTime());
        }

        String normalizedEmail = dto.customerEmail() != null ? dto.customerEmail().trim().toLowerCase() : "";
        String customerName = dto.customerName() != null ? dto.customerName().trim() : "";
        String customerPhone = dto.customerPhone() != null ? dto.customerPhone().trim() : "";

        Customer customer = customerRepository.upsertByEmail(customerName, normalizedEmail, customerPhone);
        double totalPrice = chiva.pricePerHour() * durationHours;
        Instant now = Instant.now();

        Booking newBooking = new Booking(
                Models.generateBookingCode(6),
                dto.chivaId(),
                chiva.name(),
                customer.id(),
                customerName,
                normalizedEmail,
                customerPhone,
                guestCount,
                travel.id(),
                travel.name(),
                eventDate.value(),
                timeSlot.startTime(),
                timeSlot.durationHours(),
                totalPrice,
                "pending",
                dto.notes(),
                null,
                "pending",
                now,
                now
        );

        Booking saved = bookingRepository.saveBooking(newBooking);
        BookingResponseDto response = toBookingResponseDto(saved);
        notificationService.sendBookingConfirmation(response);
        notificationService.sendNewBookingAlert(response);
        return response;
    }

    public PaginatedBookingsResponseDto getBookings(String rawPage, String rawLimit, String rawStatus, String rawQ) {
        int page = 1;
        if (rawPage != null && !rawPage.isEmpty()) {
            try {
                page = Integer.parseInt(rawPage);
            } catch (NumberFormatException e) {
                throw DomainException.validation("El parámetro page debe ser un entero mayor o igual a 1.");
            }
            if (page < 1) {
                throw DomainException.validation("El parámetro page debe ser un entero mayor o igual a 1.");
            }
        }

        int limit = 10;
        if (rawLimit != null && !rawLimit.isEmpty()) {
            try {
                limit = Integer.parseInt(rawLimit);
            } catch (NumberFormatException e) {
                throw DomainException.validation("El parámetro limit debe ser un entero entre 1 y 50.");
            }
            if (limit < 1 || limit > 50) {
                throw DomainException.validation("El parámetro limit debe ser un entero entre 1 y 50.");
            }
        }

        String status = (rawStatus == null || rawStatus.isEmpty()) ? null : rawStatus;
        if (status != null && !BOOKING_STATUSES.contains(status)) {
            throw DomainException.validation("El parámetro status debe ser pending, confirmed, cancelled o completed.");
        }

        String q = (rawQ != null && !rawQ.trim().isEmpty()) ? rawQ.trim() : null;

        PaginatedBookingsResult result = bookingRepository.findBookingPaginated(
                new PaginatedBookingsQuery(page, limit, status, q)
        );

        List<BookingResponseDto> data = result.data().stream()
                .map(this::toBookingResponseDto)
                .toList();

        int totalPages = (int) Math.ceil((double) result.total() / (double) limit);
        return new PaginatedBookingsResponseDto(data, new PaginationMetaDto(page, limit, result.total(), totalPages));
    }

    public BookingResponseDto getBookingById(String id) {
        Booking booking = bookingRepository.findBookingById(id)
                .orElseThrow(() -> DomainException.bookingNotFound(id));
        return toBookingResponseDto(booking);
    }

    public BookingResponseDto updateBookingStatus(String bookingId, Map<String, Object> body) {
        String status = null;
        if (body != null && body.get("status") != null) {
            if (!(body.get("status") instanceof String s)) {
                throw DomainException.validation("El campo status debe ser confirmed, cancelled o completed.");
            }
            if (!s.isEmpty()) {
                status = s;
            }
        }

        Object rawPrice = body != null
                ? (body.get("totalPrice") != null ? body.get("totalPrice") : body.get("price"))
                : null;

        Double parsedTotalPrice = null;
        if (rawPrice != null && !"".equals(rawPrice)) {
            double numericPrice = parseCustomPrice(rawPrice);
            if (!Double.isFinite(numericPrice) || numericPrice <= 0) {
                throw DomainException.validation("El campo totalPrice debe ser un número mayor a 0.");
            }
            parsedTotalPrice = numericPrice;
        }

        if (status == null && parsedTotalPrice == null) {
            throw DomainException.validation("El campo status es requerido.");
        }

        if (status != null) {
            Set<String> allowedStatuses = parsedTotalPrice != null ? BOOKING_STATUSES : ACCEPTED_UPDATE_BOOKING_STATUSES;
            if (!allowedStatuses.contains(status)) {
                throw DomainException.validation("El campo status debe ser confirmed, cancelled o completed.");
            }
        }

        Booking booking = bookingRepository.findBookingById(bookingId)
                .orElseThrow(() -> DomainException.bookingNotFound(bookingId));

        String targetStatus = status != null ? status : booking.status();
        boolean isSameStatusPriceUpdate = targetStatus.equals(booking.status()) && parsedTotalPrice != null;

        Set<String> validTargets = VALID_TRANSITIONS.getOrDefault(booking.status(), Set.of());
        if (!isSameStatusPriceUpdate && !validTargets.contains(targetStatus)) {
            throw DomainException.invalidBookingStatusTransition(booking.status(), targetStatus);
        }

        Double roundedPrice = parsedTotalPrice != null ? (double) Math.round(parsedTotalPrice) : null;
        Booking updated = bookingRepository.updateBookingStatus(bookingId, targetStatus, roundedPrice);

        String resolvedChivaName = updated.chivaName() != null
                ? updated.chivaName()
                : chivaRepository.findChivaById(updated.chivaId()).map(Chiva::name).orElse("");
        String resolvedTravelName = updated.travelName() != null
                ? updated.travelName()
                : travelRepository.findTravelById(updated.travel()).map(Travel::name).orElse(updated.travel());
        String eventDateStr = Models.toUtcDateString(updated.eventDate());

        if (!isSameStatusPriceUpdate && "confirmed".equals(targetStatus)) {
            String existingEventId = updated.googleEventId() != null ? updated.googleEventId() : booking.googleEventId();
            if (existingEventId != null && !existingEventId.isBlank()) {
                if (!"synced".equals(updated.calendarSyncStatus())) {
                    updated = bookingRepository.updateBookingCalendarSync(bookingId, existingEventId, true, "synced");
                }
            } else {
                try {
                    String eventId = calendarService.createEvent(new CalendarEventInput(
                            updated.id(),
                            resolvedChivaName.isEmpty() ? updated.chivaId() : resolvedChivaName,
                            updated.customerName(),
                            updated.customerPhone(),
                            resolvedTravelName,
                            updated.guestCount(),
                            eventDateStr,
                            updated.startTime(),
                            updated.durationHours(),
                            updated.notes()
                    ));
                    updated = bookingRepository.updateBookingCalendarSync(bookingId, eventId, true, "synced");
                } catch (Exception ex) {
                    updated = bookingRepository.updateBookingCalendarSync(bookingId, null, false, "failed");
                }
            }
        } else if (!isSameStatusPriceUpdate && "cancelled".equals(targetStatus)) {
            String existingEventId = updated.googleEventId() != null ? updated.googleEventId() : booking.googleEventId();
            if (existingEventId != null && !existingEventId.isBlank()) {
                try {
                    calendarService.deleteEvent(existingEventId);
                    updated = bookingRepository.updateBookingCalendarSync(bookingId, null, true, "synced");
                } catch (Exception ex) {
                    updated = bookingRepository.updateBookingCalendarSync(bookingId, null, false, "failed");
                }
            }
        }

        BookingResponseDto response = toBookingResponseDto(updated);
        if (!isSameStatusPriceUpdate) {
            notificationService.sendBookingStatusUpdate(response, booking.status());
        }
        return response;
    }

    public BookingResponseDto syncBookingCalendar(String bookingId) {
        Booking booking = bookingRepository.findBookingById(bookingId)
                .orElseThrow(() -> DomainException.bookingNotFound(bookingId));

        if (!"confirmed".equals(booking.status())) {
            throw DomainException.validation("Solo se pueden sincronizar en el calendario las reservas confirmadas.");
        }

        String resolvedChivaName = booking.chivaName() != null
                ? booking.chivaName()
                : chivaRepository.findChivaById(booking.chivaId()).map(Chiva::name).orElse("");
        String resolvedTravelName = booking.travelName() != null
                ? booking.travelName()
                : travelRepository.findTravelById(booking.travel()).map(Travel::name).orElse(booking.travel());
        String eventDateStr = Models.toUtcDateString(booking.eventDate());

        Booking updated = booking;
        if (booking.googleEventId() != null && !booking.googleEventId().isBlank()) {
            if (!"synced".equals(booking.calendarSyncStatus())) {
                updated = bookingRepository.updateBookingCalendarSync(bookingId, booking.googleEventId(), true, "synced");
            }
        } else {
            try {
                String eventId = calendarService.createEvent(new CalendarEventInput(
                        booking.id(),
                        resolvedChivaName.isEmpty() ? booking.chivaId() : resolvedChivaName,
                        booking.customerName(),
                        booking.customerPhone(),
                        resolvedTravelName,
                        booking.guestCount(),
                        eventDateStr,
                        booking.startTime(),
                        booking.durationHours(),
                        booking.notes()
                ));
                updated = bookingRepository.updateBookingCalendarSync(bookingId, eventId, true, "synced");
            } catch (Exception ex) {
                updated = bookingRepository.updateBookingCalendarSync(bookingId, null, false, "failed");
            }
        }

        return toBookingResponseDto(updated);
    }

    /**
     * Programación Funcional en el Dashboard del Administrador (GET /bookings/calendar?month=YYYY-MM):
     * Uso explícito de Predicate, Function, filter, groupingBy (doble nivel: día y chiva),
     * map, reduce y sorted sobre colecciones inmutables, produciendo resultados idénticos a Express.
     */
    public BookingsCalendarResponseDto getBookingsCalendar(String rawMonth) {
        String trimmedMonth = rawMonth != null ? rawMonth.trim() : "";
        if (trimmedMonth.isEmpty() || !MONTH_REGEX.matcher(trimmedMonth).matches()) {
            throw DomainException.validation("El parámetro month debe tener el formato YYYY-MM.");
        }

        String[] parts = trimmedMonth.split("-");
        int year = Integer.parseInt(parts[0]);
        int monthNumber = Integer.parseInt(parts[1]);

        Instant startDate = ZonedDateTime.of(year, monthNumber, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant();
        ZonedDateTime nextMonthStart = ZonedDateTime.of(year, monthNumber, 1, 5, 0, 0, 0, ZoneOffset.UTC).plusMonths(1);
        Instant endDate = nextMonthStart.toInstant();

        List<Booking> rawBookings = bookingRepository.findConfirmedByDateRange(startDate, endDate);

        Predicate<Booking> isConfirmedInMonth = b ->
                "confirmed".equals(b.status()) &&
                Models.formatEventDateBogota(b.eventDate()).startsWith(trimmedMonth);

        Function<Booking, CalendarSlotDto> toSlotDto = booking -> {
            TimeSlot ts = TimeSlot.create(booking.startTime(), booking.durationHours());
            int startMinutes = TimeSlot.toMinutes(booking.startTime());
            double totalMinutes = startMinutes + booking.durationHours() * 60.0;
            Boolean endsNextDay = totalMinutes > 24.0 * 60.0 ? Boolean.TRUE : null;
            String travelName = booking.travelName() != null ? booking.travelName() : booking.travel();
            return new CalendarSlotDto(
                    booking.id(),
                    booking.startTime(),
                    ts.getEndTime(),
                    booking.durationHours(),
                    booking.customerName(),
                    travelName,
                    booking.guestCount(),
                    endsNextDay
            );
        };

        List<Booking> confirmedInMonth = rawBookings.stream()
                .filter(isConfirmedInMonth)
                .toList();

        // Catálogo único de chivas preservando orden de aparición mediante reducción funcional
        Map<String, String> uniqueChivasMap = confirmedInMonth.stream()
                .collect(Collectors.toMap(
                        Booking::chivaId,
                        b -> b.chivaName() != null ? b.chivaName() : b.chivaId(),
                        (existing, replacement) -> existing,
                        LinkedHashMap::new
                ));

        List<CalendarChivaSummaryDto> chivasSummary = uniqueChivasMap.entrySet().stream()
                .map(e -> new CalendarChivaSummaryDto(e.getKey(), e.getValue()))
                .toList();

        // Doble agrupación funcional: Día (ordenado cronológicamente) -> Chiva (orden de aparición) -> Lista de Reservas
        Map<String, Map<String, List<Booking>>> groupedByDateAndChiva = confirmedInMonth.stream()
                .collect(Collectors.groupingBy(
                        b -> Models.formatEventDateBogota(b.eventDate()),
                        TreeMap::new,
                        Collectors.groupingBy(
                                Booking::chivaId,
                                LinkedHashMap::new,
                                Collectors.toList()
                        )
                ));

        List<CalendarDayDto> days = groupedByDateAndChiva.entrySet().stream()
                .map(dateEntry -> {
                    String date = dateEntry.getKey();
                    List<CalendarDayChivaDto> dayChivas = dateEntry.getValue().entrySet().stream()
                            .map(chivaEntry -> {
                                String chivaId = chivaEntry.getKey();
                                List<Booking> chivaBookings = chivaEntry.getValue();
                                String chivaName = chivaBookings.get(0).chivaName() != null
                                        ? chivaBookings.get(0).chivaName()
                                        : chivaId;
                                List<CalendarSlotDto> sortedSlots = chivaBookings.stream()
                                        .map(toSlotDto)
                                        .sorted(Comparator.comparing(CalendarSlotDto::startTime))
                                        .toList();
                                return new CalendarDayChivaDto(chivaId, chivaName, sortedSlots);
                            })
                            .toList();
                    return new CalendarDayDto(date, dayChivas.size(), dayChivas);
                })
                .toList();

        return new BookingsCalendarResponseDto(trimmedMonth, chivasSummary, days);
    }

    // ── 5. Uploads ─────────────────────────────────────────────────────────────
    public UploadSignatureDto getUploadSignature() {
        return imageStorageService.generateUploadSignature();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────
    private static int parsePositiveInt(Object raw, String errorMessage) {
        if (raw instanceof Integer i && i > 0) return i;
        if (raw instanceof Long l && l > 0 && l <= Integer.MAX_VALUE) return l.intValue();
        if (raw instanceof Double d && d == Math.floor(d) && d > 0) return d.intValue();
        throw DomainException.validation(errorMessage);
    }

    private static double parsePositiveDouble(Object raw, String errorMessage) {
        if (raw instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() > 0) {
            return n.doubleValue();
        }
        throw DomainException.validation(errorMessage);
    }

    private static double parseCustomPrice(Object rawPrice) {
        if (rawPrice instanceof Number n) {
            return n.doubleValue();
        }
        if (rawPrice instanceof String s) {
            String trimmed = s.trim();
            try {
                if (trimmed.matches("^\\$?\\s*\\d{1,3}(\\.\\d{3})+$")) {
                    return Double.parseDouble(trimmed.replaceAll("[^\\d]", ""));
                }
                return Double.parseDouble(trimmed.replaceAll("[$,\\s]", ""));
            } catch (NumberFormatException ex) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private static boolean isValidHttpUrl(String url) {
        try {
            URI uri = URI.create(url.trim());
            return uri.getScheme() != null && (uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https")) && uri.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static Instant parseLooseIsoDate(String dateStr) {
        try {
            String clean = dateStr.trim().split("T")[0];
            return java.time.LocalDate.parse(clean).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            return Instant.now();
        }
    }
}
