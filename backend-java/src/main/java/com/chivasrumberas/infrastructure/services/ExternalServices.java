package com.chivasrumberas.infrastructure.services;

import com.chivasrumberas.domain.errors.DomainException;
import com.chivasrumberas.domain.models.Models.JwtPayload;
import com.chivasrumberas.web.ApiDtos.BookingResponseDto;
import com.chivasrumberas.web.ApiDtos.UploadSignatureDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Configuration
public class ExternalServices {

    public interface JwtTokenService {
        String sign(JwtPayload payload);
        JwtPayload verify(String token);
    }

    public record CalendarEventInput(
            String bookingId,
            String chivaName,
            String customerName,
            String customerPhone,
            String travelName,
            int guestCount,
            String eventDate,
            String startTime,
            double durationHours,
            String notes
    ) {}

    public interface CalendarPort {
        String createEvent(CalendarEventInput input);
        void deleteEvent(String googleEventId);
    }

    public interface NotificationPort {
        void sendBookingConfirmation(BookingResponseDto booking);
        void sendNewBookingAlert(BookingResponseDto booking);
        void sendBookingStatusUpdate(BookingResponseDto booking, String previousStatus);
    }

    public interface ImageStoragePort {
        UploadSignatureDto generateUploadSignature();
    }

    @Service
    public static class HmacJwtService implements JwtTokenService {
        private final byte[] secretBytes;
        private final long expiresInSeconds;
        private final ObjectMapper mapper = new ObjectMapper();

        public HmacJwtService(
                @Value("${app.jwt.secret:chivas_rumberas_super_secret_jwt_key_2026_benchmark}") String secret,
                @Value("${app.jwt.expires-in:8h}") String expiresIn
        ) {
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException("Error con la configuración JWT_SECRET");
            }
            this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
            this.expiresInSeconds = parseExpiresIn(expiresIn);
        }

        private static long parseExpiresIn(String raw) {
            if (raw == null || raw.isBlank()) return 4 * 3600L;
            String s = raw.trim().toLowerCase();
            if (s.endsWith("h")) {
                return Long.parseLong(s.substring(0, s.length() - 1)) * 3600L;
            }
            if (s.endsWith("m")) {
                return Long.parseLong(s.substring(0, s.length() - 1)) * 60L;
            }
            if (s.endsWith("d")) {
                return Long.parseLong(s.substring(0, s.length() - 1)) * 86400L;
            }
            if (s.endsWith("s")) {
                return Long.parseLong(s.substring(0, s.length() - 1));
            }
            return Long.parseLong(s);
        }

        @Override
        public String sign(JwtPayload payload) {
            try {
                Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
                long iat = Instant.now().getEpochSecond();
                long exp = iat + expiresInSeconds;
                Map<String, Object> claims = new LinkedHashMap<>();
                claims.put("sub", payload.sub());
                claims.put("email", payload.email());
                claims.put("role", payload.role());
                claims.put("iat", iat);
                claims.put("exp", exp);

                Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
                String headerB64 = enc.encodeToString(mapper.writeValueAsBytes(header));
                String payloadB64 = enc.encodeToString(mapper.writeValueAsBytes(claims));
                String signingInput = headerB64 + "." + payloadB64;
                String sigB64 = hmacSha256Base64Url(signingInput);
                return signingInput + "." + sigB64;
            } catch (Exception e) {
                throw new RuntimeException("Error firmando JWT", e);
            }
        }

        @Override
        public JwtPayload verify(String token) {
            try {
                if (token == null || token.isBlank()) {
                    throw DomainException.invalidToken();
                }
                String[] parts = token.split("\\.");
                if (parts.length != 3) {
                    throw DomainException.invalidToken();
                }
                String signingInput = parts[0] + "." + parts[1];
                String expectedSig = hmacSha256Base64Url(signingInput);
                if (!MessageDigest.isEqual(
                        expectedSig.getBytes(StandardCharsets.UTF_8),
                        parts[2].getBytes(StandardCharsets.UTF_8)
                )) {
                    throw DomainException.invalidToken();
                }

                byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
                Map<String, Object> claims = mapper.readValue(payloadBytes, new TypeReference<>() {});
                Object expObj = claims.get("exp");
                if (expObj instanceof Number expNum) {
                    if (Instant.now().getEpochSecond() >= expNum.longValue()) {
                        throw DomainException.invalidToken();
                    }
                }
                String sub = (String) claims.get("sub");
                String email = (String) claims.get("email");
                String role = (String) claims.get("role");
                if (sub == null || email == null || role == null) {
                    throw DomainException.invalidToken();
                }
                return new JwtPayload(sub, email, role);
            } catch (DomainException de) {
                throw de;
            } catch (Exception e) {
                throw DomainException.invalidToken();
            }
        }

        private String hmacSha256Base64Url(String input) throws Exception {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            byte[] rawHmac = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
        }
    }

    @Service
    public static class ConfigurableCalendarService implements CalendarPort {
        private final long mockLatencyMs;

        public ConfigurableCalendarService(@Value("${app.integrations.mock-latency-ms:50}") long mockLatencyMs) {
            this.mockLatencyMs = mockLatencyMs;
        }

        private void simulateFixedLatency() {
            if (mockLatencyMs > 0) {
                try {
                    Thread.sleep(mockLatencyMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public String createEvent(CalendarEventInput input) {
            simulateFixedLatency();
            if (input.notes() != null && input.notes().contains("[FORCE_CALENDAR_ERROR]")) {
                throw new RuntimeException("Simulated Google Calendar 503 Unavailable");
            }
            return "gcal-mock-" + input.bookingId();
        }

        @Override
        public void deleteEvent(String googleEventId) {
            simulateFixedLatency();
        }
    }

    @Service
    public static class ConfigurableNotificationService implements NotificationPort {
        private final long mockLatencyMs;

        public ConfigurableNotificationService(@Value("${app.integrations.mock-latency-ms:50}") long mockLatencyMs) {
            this.mockLatencyMs = mockLatencyMs;
        }

        private void simulateFixedLatency() {
            if (mockLatencyMs > 0) {
                try {
                    Thread.sleep(mockLatencyMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void sendBookingConfirmation(BookingResponseDto booking) {
            simulateFixedLatency();
        }

        @Override
        public void sendNewBookingAlert(BookingResponseDto booking) {
            simulateFixedLatency();
        }

        @Override
        public void sendBookingStatusUpdate(BookingResponseDto booking, String previousStatus) {
            simulateFixedLatency();
        }
    }

    @Service
    public static class CloudinaryImageStorageService implements ImageStoragePort {
        private final String cloudName;
        private final String apiKey;
        private final String apiSecret;

        public CloudinaryImageStorageService(
                @Value("${app.cloudinary.cloud-name:}") String cloudName,
                @Value("${app.cloudinary.api-key:}") String apiKey,
                @Value("${app.cloudinary.api-secret:}") String apiSecret
        ) {
            this.cloudName = stripQuotes(cloudName);
            this.apiKey = stripQuotes(apiKey);
            this.apiSecret = stripQuotes(apiSecret);
        }

        private static String stripQuotes(String raw) {
            if (raw == null) return "";
            return raw.trim().replaceAll("^[\"']+|[\"']+$", "");
        }

        @Override
        public UploadSignatureDto generateUploadSignature() {
            if (cloudName.isEmpty() || apiKey.isEmpty() || apiSecret.isEmpty()) {
                throw DomainException.internalError(
                        "Las credenciales de Cloudinary (CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY, CLOUDINARY_API_SECRET) no están configuradas."
                );
            }

            long timestamp = Instant.now().getEpochSecond();
            String folder = "chivas-rumberas/chivas";
            List<String> allowedFormats = List.of("jpg", "png", "webp");
            long maxFileSize = 5L * 1024L * 1024L;

            Map<String, String> paramsToSign = new TreeMap<>();
            paramsToSign.put("allowed_formats", String.join(",", allowedFormats));
            paramsToSign.put("folder", folder);
            paramsToSign.put("timestamp", String.valueOf(timestamp));

            String sortedParams = paramsToSign.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + "&" + b)
                    .orElse("");

            try {
                MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                byte[] digest = sha1.digest((sortedParams + apiSecret).getBytes(StandardCharsets.UTF_8));
                String signature = HexFormat.of().formatHex(digest);
                return new UploadSignatureDto(
                        cloudName,
                        apiKey,
                        timestamp,
                        signature,
                        folder,
                        allowedFormats,
                        maxFileSize
                );
            } catch (Exception e) {
                throw DomainException.internalError("Error generando firma de Cloudinary.");
            }
        }
    }
}
