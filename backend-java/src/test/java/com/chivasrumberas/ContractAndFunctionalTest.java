package com.chivasrumberas;

import com.chivasrumberas.application.services.ChivasBusinessService;
import com.chivasrumberas.domain.models.Models;
import com.chivasrumberas.domain.models.Models.*;
import com.chivasrumberas.domain.repositories.Repositories.*;
import com.chivasrumberas.infrastructure.services.ExternalServices.*;
import com.chivasrumberas.web.ApiControllers.*;
import com.chivasrumberas.web.MetricsAndObservability;
import com.chivasrumberas.web.MetricsAndObservability.HttpMetricsFilter;
import com.chivasrumberas.web.MetricsAndObservability.ObservabilityController;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ContractAndFunctionalTest {

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private HmacJwtService jwtService;
    private String adminToken;
    private String operatorToken;

    private final Map<String, Chiva> chivasStore = new LinkedHashMap<>();
    private final Map<String, Travel> travelsStore = new LinkedHashMap<>();
    private final Map<String, Customer> customersByEmail = new LinkedHashMap<>();
    private final Map<String, Booking> bookingsStore = new LinkedHashMap<>();
    private final Map<String, User> usersByEmail = new LinkedHashMap<>();
    private boolean forceCalendarFailure = false;
    private int calendarCreateCalls = 0;
    private String lastDeletedCalendarEventId = null;

    @BeforeEach
    void setUp() {
        chivasStore.clear();
        travelsStore.clear();
        customersByEmail.clear();
        bookingsStore.clear();
        usersByEmail.clear();
        forceCalendarFailure = false;
        calendarCreateCalls = 0;
        lastDeletedCalendarEventId = null;

        jwtService = new HmacJwtService("test_secret_key_for_contract_tests_2026", "8h");
        adminToken = jwtService.sign(new JwtPayload("admin-1", "admin@chivasrumberas.com", "admin"));
        operatorToken = jwtService.sign(new JwtPayload("op-1", "operador@chivasrumberas.com", "operator"));

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        usersByEmail.put("admin@chivasrumberas.com", new User(
                "admin-1",
                "admin@chivasrumberas.com",
                "$2b$10$" + encoder.encode("Admin123*Chiva").substring(7),
                "admin",
                Instant.now()
        ));

        chivasStore.put("la-coqueta-1954", new Chiva(
                "la-coqueta-1954",
                "La Coqueta",
                "Chiva tradicional paisa",
                50,
                List.of("https://images.unsplash.com/photo-1544620347-c4fd4a3d5957"),
                280000,
                "available",
                List.of("Sonido 4000W"),
                Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-01T00:00:00Z")
        ));
        chivasStore.put("la-parrandera-1962", new Chiva(
                "la-parrandera-1962",
                "La Parrandera",
                "Chiva rumbera",
                40,
                List.of(),
                260000,
                "available",
                List.of(),
                Instant.parse("2026-11-01T00:01:00Z"),
                Instant.parse("2026-11-01T00:01:00Z")
        ));

        travelsStore.put("trv_alumbrados", new Travel(
                "trv_alumbrados",
                "Ruta Navideña y Alumbrados",
                "Recorrido navideño",
                4,
                List.of("Río Medellín"),
                true
        ));

        ChivaRepository chivaRepo = new ChivaRepository() {
            @Override
            public List<Chiva> findChivaAll(String status) {
                return chivasStore.values().stream()
                        .filter(c -> status == null || status.isBlank() || c.status().equals(status))
                        .toList();
            }

            @Override
            public Optional<Chiva> findChivaById(String id) {
                return Optional.ofNullable(chivasStore.get(id));
            }

            @Override
            public Chiva saveChiva(Chiva chiva) {
                chivasStore.put(chiva.id(), chiva);
                return chiva;
            }

            @Override
            public Chiva updateChivaStatus(String id, String status) {
                Chiva c = chivasStore.get(id);
                Chiva updated = new Chiva(c.id(), c.name(), c.description(), c.capacity(), c.imageUrls(), c.pricePerHour(), status, c.features(), c.createdAt(), Instant.now());
                chivasStore.put(id, updated);
                return updated;
            }

            @Override
            @SuppressWarnings("unchecked")
            public Chiva updateChiva(String id, Map<String, Object> updates) {
                Chiva c = chivasStore.get(id);
                Chiva updated = new Chiva(
                        c.id(),
                        (String) updates.getOrDefault("name", c.name()),
                        (String) updates.getOrDefault("description", c.description()),
                        (Integer) updates.getOrDefault("capacity", c.capacity()),
                        (List<String>) updates.getOrDefault("imageUrls", c.imageUrls()),
                        (Double) updates.getOrDefault("pricePerHour", c.pricePerHour()),
                        (String) updates.getOrDefault("status", c.status()),
                        (List<String>) updates.getOrDefault("features", c.features()),
                        c.createdAt(),
                        Instant.now()
                );
                chivasStore.put(id, updated);
                return updated;
            }
        };

        TravelRepository travelRepo = new TravelRepository() {
            @Override
            public List<Travel> findAllTravelsActive() {
                return travelsStore.values().stream().filter(Travel::isActive).toList();
            }

            @Override
            public Optional<Travel> findTravelById(String id) {
                return Optional.ofNullable(travelsStore.get(id));
            }
        };

        CustomerRepository customerRepo = new CustomerRepository() {
            @Override
            public Optional<Customer> findByEmail(String email) {
                return Optional.ofNullable(customersByEmail.get(email.trim().toLowerCase()));
            }

            @Override
            public Optional<Customer> findById(String id) {
                return customersByEmail.values().stream().filter(c -> c.id().equals(id)).findFirst();
            }

            @Override
            public Customer upsertByEmail(String name, String email, String phone) {
                String norm = email.trim().toLowerCase();
                Customer existing = customersByEmail.get(norm);
                if (existing != null) {
                    Customer updated = new Customer(existing.id(), name.trim(), norm, phone.trim(), existing.createdAt());
                    customersByEmail.put(norm, updated);
                    return updated;
                }
                Customer created = new Customer("cust-" + (customersByEmail.size() + 1), name.trim(), norm, phone.trim(), Instant.now());
                customersByEmail.put(norm, created);
                return created;
            }
        };

        BookingRepository bookingRepo = new BookingRepository() {
            @Override
            public Optional<Booking> findBookingById(String id) {
                return Optional.ofNullable(bookingsStore.get(id));
            }

            @Override
            public PaginatedBookingsResult findBookingPaginated(PaginatedBookingsQuery query) {
                List<Booking> filtered = bookingsStore.values().stream()
                        .filter(b -> query.status() == null || b.status().equals(query.status()))
                        .filter(b -> {
                            if (query.q() == null) return true;
                            String q = query.q().toLowerCase();
                            return b.id().toLowerCase().contains(q)
                                    || b.customerName().toLowerCase().contains(q)
                                    || b.customerEmail().toLowerCase().contains(q);
                        })
                        .toList();
                int skip = (query.page() - 1) * query.limit();
                List<Booking> pageData = filtered.stream().skip(skip).limit(query.limit()).toList();
                return new PaginatedBookingsResult(pageData, filtered.size());
            }

            @Override
            public List<Booking> findConfirmedByDateRange(Instant startDate, Instant endDate) {
                return bookingsStore.values().stream()
                        .filter(b -> "confirmed".equals(b.status()))
                        .filter(b -> !b.eventDate().isBefore(startDate) && b.eventDate().isBefore(endDate))
                        .toList();
            }

            @Override
            public List<OccupiedSlotRecord> findOccupiedSlotsByChivaAndDate(String chivaId, Instant date) {
                String targetDate = Models.toUtcDateString(date);
                return bookingsStore.values().stream()
                        .filter(b -> b.chivaId().equals(chivaId))
                        .filter(b -> Set.of("pending", "confirmed").contains(b.status()))
                        .filter(b -> Models.toUtcDateString(b.eventDate()).equals(targetDate))
                        .map(b -> new OccupiedSlotRecord(b.startTime(), b.durationHours()))
                        .toList();
            }

            @Override
            public boolean hasConflict(String chivaId, Instant eventDate, String startTime, double durationHours) {
                List<OccupiedSlotRecord> occupied = findOccupiedSlotsByChivaAndDate(chivaId, eventDate);
                int newStart = TimeSlot.toMinutes(startTime);
                double newEnd = newStart + durationHours * 60.0;
                return occupied.stream().anyMatch(s -> {
                    int sStart = TimeSlot.toMinutes(s.startTime());
                    double sEnd = sStart + s.durationHours() * 60.0;
                    return newStart < sEnd && sStart < newEnd;
                });
            }

            @Override
            public boolean hasFutureActiveBookingsByChiva(String chivaId, Instant fromDate) {
                return bookingsStore.values().stream()
                        .filter(b -> b.chivaId().equals(chivaId))
                        .filter(b -> Set.of("pending", "confirmed").contains(b.status()))
                        .anyMatch(b -> !b.eventDate().isBefore(fromDate.truncatedTo(ChronoUnit.DAYS)));
            }

            @Override
            public Booking saveBooking(Booking booking) {
                bookingsStore.put(booking.id(), booking);
                return booking;
            }

            @Override
            public Booking updateBookingStatus(String id, String status, Double totalPrice) {
                Booking b = bookingsStore.get(id);
                Booking updated = new Booking(
                        b.id(), b.chivaId(), b.chivaName(), b.customerId(), b.customerName(), b.customerEmail(),
                        b.customerPhone(), b.guestCount(), b.travel(), b.travelName(), b.eventDate(), b.startTime(),
                        b.durationHours(), totalPrice != null ? totalPrice : b.totalPrice(), status, b.notes(),
                        b.googleEventId(), b.calendarSyncStatus(), b.createdAt(), Instant.now()
                );
                bookingsStore.put(id, updated);
                return updated;
            }

            @Override
            public Booking updateBookingCalendarSync(String id, String googleEventId, boolean updateGoogleEventId, String calendarSyncStatus) {
                Booking b = bookingsStore.get(id);
                Booking updated = new Booking(
                        b.id(), b.chivaId(), b.chivaName(), b.customerId(), b.customerName(), b.customerEmail(),
                        b.customerPhone(), b.guestCount(), b.travel(), b.travelName(), b.eventDate(), b.startTime(),
                        b.durationHours(), b.totalPrice(), b.status(), b.notes(),
                        updateGoogleEventId ? googleEventId : b.googleEventId(),
                        calendarSyncStatus, b.createdAt(), Instant.now()
                );
                bookingsStore.put(id, updated);
                return updated;
            }
        };

        UserRepository userRepo = new UserRepository() {
            @Override
            public Optional<User> findUserByEmail(String email) {
                return Optional.ofNullable(usersByEmail.get(email));
            }

            @Override
            public Optional<User> findUserById(String id) {
                return usersByEmail.values().stream().filter(u -> u.id().equals(id)).findFirst();
            }
        };

        CalendarPort calendarService = new CalendarPort() {
            @Override
            public String createEvent(CalendarEventInput input) {
                calendarCreateCalls++;
                if (forceCalendarFailure) {
                    throw new RuntimeException("Google API 503 Unavailable");
                }
                return "gcal-evt-" + input.bookingId();
            }

            @Override
            public void deleteEvent(String googleEventId) {
                lastDeletedCalendarEventId = googleEventId;
            }
        };

        NotificationPort notificationService = new ConfigurableNotificationService(0);
        ImageStoragePort imageStorage = new CloudinaryImageStorageService("demo-cloud", "12345", "secret-xyz");

        ChivasBusinessService businessService = new ChivasBusinessService(
                chivaRepo, travelRepo, customerRepo, bookingRepo, userRepo,
                jwtService, notificationService, calendarService, imageStorage
        );

        SecurityAndRateLimitHelper securityHelper = new SecurityAndRateLimitHelper(jwtService, false);
        MetricsAndObservability metricsConfig = new MetricsAndObservability();
        PrometheusMeterRegistry registry = metricsConfig.prometheusMeterRegistry();
        metricsConfig.onApplicationReady();

        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(businessService, securityHelper),
                        new ChivasController(businessService, securityHelper),
                        new TravelsController(businessService),
                        new BookingsController(businessService, securityHelper),
                        new UploadsController(businessService, securityHelper),
                        new ObservabilityController(registry)
                )
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new HttpMetricsFilter(registry))
                .build();
    }

    private Booking sampleBooking(String id, String chivaId, String chivaName, String status, String eventDateIso, String startTime, double durationHours) {
        return new Booking(
                id, chivaId, chivaName, "cust-1", "Carolina Montoya", "carolina@example.com", "3001234567",
                30, "trv_alumbrados", "Ruta Navideña y Alumbrados",
                Instant.parse(eventDateIso), startTime, durationHours, 1120000, status, "Cumpleaños",
                null, "pending", Instant.parse("2026-11-01T15:00:00Z"), Instant.parse("2026-11-01T15:00:00Z")
        );
    }

    @Test
    void healthAndMetricsEndpointsWork() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.timestamp").exists());

        String metricsBody = mockMvc.perform(get("/metrics"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(metricsBody.contains("chivas_rumberas_http_requests_total"));
        assertTrue(metricsBody.contains("chivas_rumberas_http_request_duration_seconds_bucket"));
        assertTrue(metricsBody.contains("chivas_rumberas_startup_duration_seconds"));
    }

    @Test
    void loginAuthenticatesAndRejectsInvalidCredentials() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@chivasrumberas.com\",\"password\":\"Admin123*Chiva\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.user.role").value("admin"));

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@chivasrumberas.com\",\"password\":\"WrongPassword\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.type").value("InvalidCredentialsError"));
    }

    @Test
    void getBookingsPaginationAndValidation() throws Exception {
        bookingsStore.put("CR-AAAA22", sampleBooking("CR-AAAA22", "la-coqueta-1954", "La Coqueta", "confirmed", "2026-11-20T00:00:00Z", "18:00", 4));
        bookingsStore.put("CR-BBBB33", sampleBooking("CR-BBBB33", "la-coqueta-1954", "La Coqueta", "pending", "2026-11-21T00:00:00Z", "18:00", 4));

        mockMvc.perform(get("/bookings").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.limit").value(10))
                .andExpect(jsonPath("$.meta.total").value(2));

        mockMvc.perform(get("/bookings?page=0").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("ValidationError"));

        mockMvc.perform(get("/bookings?limit=51").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("ValidationError"));

        mockMvc.perform(get("/bookings?status=invalid").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("ValidationError"));
    }

    @Test
    void createBookingReusesCustomerAndDetectsConflicts() throws Exception {
        String futureDate = Models.toUtcDateString(Instant.now().plus(5, ChronoUnit.DAYS));

        Map<String, Object> req1 = Map.of(
                "chivaId", "la-coqueta-1954",
                "travelId", "trv_alumbrados",
                "customerName", "Carolina Montoya",
                "customerEmail", "Carolina.Montoya@Example.com",
                "customerPhone", "3001112233",
                "guestCount", 25,
                "eventDate", futureDate,
                "startTime", "18:00",
                "durationHours", 3
        );

        mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerEmail").value("carolina.montoya@example.com"))
                .andExpect(jsonPath("$.status").value("pending"));

        // Conflicto de horario en la misma fecha y hora
        mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value("BookingConflictError"));

        // Segunda reserva en horario distinto reutiliza el mismo Customer y actualiza nombre/teléfono
        Map<String, Object> req2 = Map.of(
                "chivaId", "la-coqueta-1954",
                "travelId", "trv_alumbrados",
                "customerName", "Carolina Montoya Actualizada",
                "customerEmail", "carolina.montoya@example.com",
                "customerPhone", "3009998877",
                "guestCount", 30,
                "eventDate", futureDate,
                "startTime", "12:00",
                "durationHours", 3
        );

        mockMvc.perform(post("/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerName").value("Carolina Montoya Actualizada"));

        assertEquals(1, customersByEmail.size());
        assertEquals("Carolina Montoya Actualizada", customersByEmail.get("carolina.montoya@example.com").name());
    }

    @Test
    void trackBookingCaseInsensitiveAndGeneric404() throws Exception {
        bookingsStore.put("CR-8P2Q9M", sampleBooking("CR-8P2Q9M", "la-coqueta-1954", "La Coqueta", "confirmed", "2026-11-20T00:00:00Z", "18:00", 4));

        mockMvc.perform(get("/bookings/track?code=cr-8p2q9m&email=CAROLINA@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("CR-8P2Q9M"))
                .andExpect(jsonPath("$.code").value("CR-8P2Q9M"))
                .andExpect(jsonPath("$.endTime").value("22:00"))
                .andExpect(jsonPath("$.customerEmail").doesNotExist())
                .andExpect(jsonPath("$.customerPhone").doesNotExist());

        String missingRes = mockMvc.perform(get("/bookings/track?code=CR-NOEXST&email=carolina@example.com"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        String wrongEmailRes = mockMvc.perform(get("/bookings/track?code=CR-8P2Q9M&email=otro@example.com"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertEquals(missingRes, wrongEmailRes);
    }

    @Test
    void calendarSyncAndStatusTransitionsAndPriceUpdate() throws Exception {
        bookingsStore.put("CR-8P2Q9M", sampleBooking("CR-8P2Q9M", "la-coqueta-1954", "La Coqueta", "pending", "2026-11-20T00:00:00Z", "18:00", 4));

        // Confirmar + negociar precio sugerido
        mockMvc.perform(patch("/bookings/CR-8P2Q9M/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"confirmed\",\"totalPrice\":950000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"))
                .andExpect(jsonPath("$.totalPrice").value(950000.0))
                .andExpect(jsonPath("$.calendarSyncStatus").value("synced"));

        assertEquals(1, calendarCreateCalls);

        // Idempotencia en calendar-sync cuando ya tiene googleEventId
        mockMvc.perform(post("/bookings/CR-8P2Q9M/calendar-sync")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.calendarSyncStatus").value("synced"));
        assertEquals(1, calendarCreateCalls);

        // Cancelar elimina evento de Google Calendar
        mockMvc.perform(patch("/bookings/CR-8P2Q9M/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"cancelled\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));

        assertEquals("gcal-evt-CR-8P2Q9M", lastDeletedCalendarEventId);

        // Transición inválida desde cancelled -> confirmed devuelve 422
        mockMvc.perform(patch("/bookings/CR-8P2Q9M/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"confirmed\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.type").value("InvalidBookingStatusTransitionError"));

        // Fallo de Google Calendar no bloquea confirmación y deja calendarSyncStatus=failed
        bookingsStore.put("CR-FAIL01", sampleBooking("CR-FAIL01", "la-coqueta-1954", "La Coqueta", "pending", "2026-11-22T00:00:00Z", "18:00", 4));
        forceCalendarFailure = true;
        mockMvc.perform(patch("/bookings/CR-FAIL01/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"confirmed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirmed"))
                .andExpect(jsonPath("$.calendarSyncStatus").value("failed"));
    }

    @Test
    void adminEndpointsEnforce401And403() throws Exception {
        String[] getPaths = {"/bookings", "/bookings/calendar?month=2026-11", "/uploads/signature"};
        for (String path : getPaths) {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.type").value("InvalidTokenError"));

            mockMvc.perform(get(path).header("Authorization", "Bearer " + operatorToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.type").value("UnauthorizedError"));
        }
    }

    @Test
    void dashboardCalendarFunctionalGroupingAndMidnightCrossing() throws Exception {
        bookingsStore.put("CR-CONF01", sampleBooking("CR-CONF01", "la-coqueta-1954", "La Coqueta", "confirmed", "2026-11-20T00:00:00Z", "22:00", 3));
        bookingsStore.put("CR-CONF02", sampleBooking("CR-CONF02", "la-parrandera-1962", "La Parrandera", "confirmed", "2026-11-20T00:00:00Z", "18:00", 3));
        bookingsStore.put("CR-PEND01", sampleBooking("CR-PEND01", "la-coqueta-1954", "La Coqueta", "pending", "2026-11-22T00:00:00Z", "18:00", 3));

        mockMvc.perform(get("/bookings/calendar?month=2026-11")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-11"))
                .andExpect(jsonPath("$.chivas.length()").value(2))
                .andExpect(jsonPath("$.days.length()").value(1))
                .andExpect(jsonPath("$.days[0].date").value("2026-11-20"))
                .andExpect(jsonPath("$.days[0].chivasCount").value(2))
                .andExpect(jsonPath("$.days[0].chivas[0].slots[0].bookingId").value("CR-CONF01"))
                .andExpect(jsonPath("$.days[0].chivas[0].slots[0].endTime").value("01:00"))
                .andExpect(jsonPath("$.days[0].chivas[0].slots[0].endsNextDay").value(true))
                .andExpect(jsonPath("$.days[0].chivas[1].slots[0].bookingId").value("CR-CONF02"))
                .andExpect(jsonPath("$.days[0].chivas[1].slots[0].endsNextDay").doesNotExist());
    }

    @Test
    void availabilityFunctionalReductionAndLogicalDeleteChiva() throws Exception {
        String futureDate = Models.toUtcDateString(Instant.now().plus(10, ChronoUnit.DAYS));
        bookingsStore.put("CR-FULL01", sampleBooking("CR-FULL01", "la-coqueta-1954", "La Coqueta", "confirmed", futureDate + "T00:00:00Z", "08:00", 8));
        bookingsStore.put("CR-FULL02", sampleBooking("CR-FULL02", "la-coqueta-1954", "La Coqueta", "confirmed", futureDate + "T00:00:00Z", "16:00", 6));

        mockMvc.perform(get("/bookings/availability?chivaId=la-coqueta-1954&date=" + futureDate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occupiedSlots.length()").value(2))
                .andExpect(jsonPath("$.isFullyBooked").value(true));

        // DELETE /chivas/la-coqueta-1954 responde 409 porque tiene reservas futuras activas
        mockMvc.perform(delete("/chivas/la-coqueta-1954")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.type").value("ChivaHasActiveBookingsError"));

        // DELETE /chivas/la-parrandera-1962 sin reservas futuras pasa a retired
        mockMvc.perform(delete("/chivas/la-parrandera-1962")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("retired"));
    }

    @Test
    void hasBookingDateTimePassedEvaluatesBogotaTime() {
        Instant eventDate = Instant.parse("2026-11-15T00:00:00.000Z");
        String startTime = "18:00"; // 23:00 UTC

        assertFalse(Models.hasBookingDateTimePassed(eventDate, startTime, Instant.parse("2026-11-15T22:59:00Z")));
        assertTrue(Models.hasBookingDateTimePassed(eventDate, startTime, Instant.parse("2026-11-15T23:00:00Z")));
        assertTrue(Models.hasBookingDateTimePassed(eventDate, startTime, Instant.parse("2026-11-16T00:30:00Z")));
    }

    @Test
    void chivasCrudTravelsAndUploadsSignatureWork() throws Exception {
        // GET /chivas y GET /travels
        mockMvc.perform(get("/chivas?status=available"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/travels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("trv_alumbrados"));

        // POST /chivas con colisión de slug
        mockMvc.perform(post("/chivas")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"La Coqueta 1954\",\"description\":\"Nueva\",\"capacity\":40,\"pricePerHour\":300000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("la-coqueta-1954-2"));

        // PATCH /chivas/{id} y PATCH /chivas/{id}/status
        mockMvc.perform(patch("/chivas/la-coqueta-1954-2")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pricePerHour\":350000,\"capacity\":45}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricePerHour").value(350000.0))
                .andExpect(jsonPath("$.capacity").value(45));

        mockMvc.perform(patch("/chivas/la-coqueta-1954-2/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"maintenance\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("maintenance"));

        // GET /uploads/signature
        mockMvc.perform(get("/uploads/signature")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cloudName").value("demo-cloud"))
                .andExpect(jsonPath("$.folder").value("chivas-rumberas/chivas"))
                .andExpect(jsonPath("$.signature").exists());
    }
}
