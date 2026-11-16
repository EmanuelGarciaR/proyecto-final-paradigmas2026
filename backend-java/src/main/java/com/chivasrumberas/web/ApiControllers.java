package com.chivasrumberas.web;

import com.chivasrumberas.application.services.ChivasBusinessService;
import com.chivasrumberas.domain.errors.DomainException;
import com.chivasrumberas.domain.models.Models.JwtPayload;
import com.chivasrumberas.infrastructure.services.ExternalServices.JwtTokenService;
import com.chivasrumberas.web.ApiDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Configuration
public class ApiControllers {

    public static class SecurityAndRateLimitHelper {
        private final JwtTokenService jwtService;
        private final boolean rateLimitDisabled;
        private final Map<String, Deque<Long>> loginAttempts = new ConcurrentHashMap<>();
        private final Map<String, Deque<Long>> trackAttempts = new ConcurrentHashMap<>();
        private static final long WINDOW_MS = 15L * 60L * 1000L;

        public SecurityAndRateLimitHelper(
                JwtTokenService jwtService,
                @Value("${app.rate-limit.disabled:false}") boolean rateLimitDisabled
        ) {
            this.jwtService = jwtService;
            this.rateLimitDisabled = rateLimitDisabled;
        }

        public JwtPayload authenticate(HttpServletRequest request) {
            String authHeader = request.getHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw DomainException.invalidToken();
            }
            String token = authHeader.substring("Bearer ".length()).trim();
            return jwtService.verify(token);
        }

        public JwtPayload requireAdmin(HttpServletRequest request) {
            JwtPayload payload = authenticate(request);
            if (!"admin".equals(payload.role())) {
                throw DomainException.unauthorized();
            }
            return payload;
        }

        public void checkLoginRateLimit(HttpServletRequest request) {
            if (rateLimitDisabled) return;
            checkRateLimit(
                    loginAttempts,
                    clientKey(request),
                    10,
                    "Demasiados intentos de inicio de sesión. Por favor intenta de nuevo en unos minutos."
            );
        }

        public void checkTrackRateLimit(HttpServletRequest request) {
            if (rateLimitDisabled) return;
            checkRateLimit(
                    trackAttempts,
                    clientKey(request),
                    15,
                    "Demasiados intentos de seguimiento. Por favor intenta de nuevo en unos minutos."
            );
        }

        private static String clientKey(HttpServletRequest request) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            return request.getRemoteAddr() != null ? request.getRemoteAddr() : "local";
        }

        private static void checkRateLimit(
                Map<String, Deque<Long>> store,
                String key,
                int maxRequests,
                String message
        ) {
            long now = System.currentTimeMillis();
            Deque<Long> timestamps = store.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
            synchronized (timestamps) {
                while (!timestamps.isEmpty() && now - timestamps.peekFirst() > WINDOW_MS) {
                    timestamps.pollFirst();
                }
                if (timestamps.size() >= maxRequests) {
                    throw DomainException.tooManyRequests(message);
                }
                timestamps.addLast(now);
            }
        }
    }

    @org.springframework.context.annotation.Bean
    public SecurityAndRateLimitHelper securityAndRateLimitHelper(
            JwtTokenService jwtService,
            @Value("${app.rate-limit.disabled:false}") boolean rateLimitDisabled
    ) {
        return new SecurityAndRateLimitHelper(jwtService, rateLimitDisabled);
    }

    @RestController
    @RequestMapping("/auth")
    public static class AuthController {
        private final ChivasBusinessService service;
        private final SecurityAndRateLimitHelper security;

        public AuthController(ChivasBusinessService service, SecurityAndRateLimitHelper security) {
            this.service = service;
            this.security = security;
        }

        @PostMapping("/login")
        public ResponseEntity<AuthResponseDto> login(
                HttpServletRequest request,
                @RequestBody(required = false) LoginDto dto
        ) {
            security.checkLoginRateLimit(request);
            return ResponseEntity.ok(service.login(dto));
        }
    }

    @RestController
    @RequestMapping("/chivas")
    public static class ChivasController {
        private final ChivasBusinessService service;
        private final SecurityAndRateLimitHelper security;

        public ChivasController(ChivasBusinessService service, SecurityAndRateLimitHelper security) {
            this.service = service;
            this.security = security;
        }

        @GetMapping
        public ResponseEntity<List<ChivaResponseDto>> getAll(@RequestParam(required = false) String status) {
            return ResponseEntity.ok(service.getChivas(status));
        }

        @GetMapping("/{id}")
        public ResponseEntity<ChivaResponseDto> getById(@PathVariable String id) {
            return ResponseEntity.ok(service.getChivaById(id));
        }

        @PostMapping
        public ResponseEntity<ChivaResponseDto> create(
                HttpServletRequest request,
                @RequestBody(required = false) Map<String, Object> body
        ) {
            security.requireAdmin(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(service.createChiva(body != null ? body : Map.of()));
        }

        @PatchMapping("/{id}/status")
        public ResponseEntity<ChivaResponseDto> updateStatus(
                HttpServletRequest request,
                @PathVariable String id,
                @RequestBody(required = false) Map<String, Object> body
        ) {
            security.requireAdmin(request);
            return ResponseEntity.ok(service.updateChivaStatus(id, body != null ? body : Map.of()));
        }

        @PatchMapping("/{id}")
        public ResponseEntity<ChivaResponseDto> update(
                HttpServletRequest request,
                @PathVariable String id,
                @RequestBody(required = false) Map<String, Object> body
        ) {
            security.requireAdmin(request);
            return ResponseEntity.ok(service.updateChiva(id, body != null ? body : Map.of()));
        }

        @DeleteMapping("/{id}")
        public ResponseEntity<ChivaResponseDto> delete(
                HttpServletRequest request,
                @PathVariable String id
        ) {
            security.requireAdmin(request);
            return ResponseEntity.ok(service.deleteChiva(id));
        }
    }

    @RestController
    @RequestMapping("/travels")
    public static class TravelsController {
        private final ChivasBusinessService service;

        public TravelsController(ChivasBusinessService service) {
            this.service = service;
        }

        @GetMapping
        public ResponseEntity<List<TravelResponseDto>> getAll() {
            return ResponseEntity.ok(service.getTravels());
        }
    }

    @RestController
    @RequestMapping("/bookings")
    public static class BookingsController {
        private final ChivasBusinessService service;
        private final SecurityAndRateLimitHelper security;

        public BookingsController(ChivasBusinessService service, SecurityAndRateLimitHelper security) {
            this.service = service;
            this.security = security;
        }

        @GetMapping("/availability")
        public ResponseEntity<AvailabilityResponseDto> getAvailability(
                @RequestParam(required = false) String chivaId,
                @RequestParam(required = false) String date
        ) {
            return ResponseEntity.ok(service.getAvailability(chivaId, date));
        }

        @GetMapping("/track")
        public ResponseEntity<TrackBookingResponseDto> track(
                HttpServletRequest request,
                @RequestParam(required = false) String code,
                @RequestParam(required = false) String email
        ) {
            security.checkTrackRateLimit(request);
            return ResponseEntity.ok(service.trackBooking(code, email));
        }

        @PostMapping
        public ResponseEntity<BookingResponseDto> create(@RequestBody(required = false) CreateBookingDto dto) {
            return ResponseEntity.status(HttpStatus.CREATED).body(service.createBooking(dto));
        }

        @GetMapping("/calendar")
        public ResponseEntity<BookingsCalendarResponseDto> getCalendar(
                HttpServletRequest request,
                @RequestParam(required = false) String month
        ) {
            security.requireAdmin(request);
            return ResponseEntity.ok(service.getBookingsCalendar(month));
        }

        @GetMapping
        public ResponseEntity<PaginatedBookingsResponseDto> getAll(
                HttpServletRequest request,
                @RequestParam(required = false) String page,
                @RequestParam(required = false) String limit,
                @RequestParam(required = false) String status,
                @RequestParam(required = false) String q
        ) {
            security.requireAdmin(request);
            return ResponseEntity.ok(service.getBookings(page, limit, status, q));
        }

        @GetMapping("/{id}")
        public ResponseEntity<BookingResponseDto> getById(
                HttpServletRequest request,
                @PathVariable String id
        ) {
            security.authenticate(request);
            return ResponseEntity.ok(service.getBookingById(id));
        }

        @PatchMapping("/{id}/status")
        public ResponseEntity<BookingResponseDto> updateStatus(
                HttpServletRequest request,
                @PathVariable String id,
                @RequestBody(required = false) Map<String, Object> body
        ) {
            security.authenticate(request);
            return ResponseEntity.ok(service.updateBookingStatus(id, body != null ? body : Map.of()));
        }

        @PostMapping("/{id}/calendar-sync")
        public ResponseEntity<BookingResponseDto> syncCalendar(
                HttpServletRequest request,
                @PathVariable String id
        ) {
            security.requireAdmin(request);
            return ResponseEntity.ok(service.syncBookingCalendar(id));
        }
    }

    @RestController
    @RequestMapping("/uploads")
    public static class UploadsController {
        private final ChivasBusinessService service;
        private final SecurityAndRateLimitHelper security;

        public UploadsController(ChivasBusinessService service, SecurityAndRateLimitHelper security) {
            this.service = service;
            this.security = security;
        }

        @GetMapping("/signature")
        public ResponseEntity<UploadSignatureDto> getSignature(HttpServletRequest request) {
            security.requireAdmin(request);
            return ResponseEntity.ok(service.getUploadSignature());
        }
    }

    @RestControllerAdvice
    public static class GlobalExceptionHandler {

        @ExceptionHandler(DomainException.class)
        public ResponseEntity<ErrorResponse> handleDomainException(DomainException ex) {
            return ResponseEntity
                    .status(ex.getHttpStatus())
                    .body(ErrorResponse.of(ex.getErrorType(), ex.getMessage()));
        }

        @ExceptionHandler(HttpMessageNotReadableException.class)
        public ResponseEntity<ErrorResponse> handleBadJson(HttpMessageNotReadableException ex) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(ErrorResponse.of("ValidationError", "Cuerpo de solicitud JSON inválido."));
        }

        @ExceptionHandler(Exception.class)
        public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ErrorResponse.of("InternalServerError", "Ocurrió un error interno en el servidor."));
        }
    }
}
