# Backend Java (Spring Boot 3 + Java 21)

Implementación completa y homologada del backend de **Chivas Rumberas** en **Java 21 + Spring Boot 3.3.5**, cumpliendo el 100 % del contrato OpenAPI 3.0.3 extraído de `/backend-express`.

---

## Arquitectura

- `com.chivasrumberas.domain`:
  - `models/Models.java`: `record`s inmutables (`Chiva`, `Travel`, `Customer`, `Booking`, `User`, `JwtPayload`) y Value Objects (`TimeSlot`, `EventDate`, `validateGuestCount`, `hasBookingDateTimePassed`, `formatEventDateBogota`, `generateBookingCode`, `slugify`).
  - `errors/DomainException.java`: Jerarquía y factorías para los 13 errores de dominio homologados (`400`, `401`, `403`, `404`, `409`, `422`, `429`, `500`).
  - `repositories/Repositories.java`: Puertos de persistencia.
- `com.chivasrumberas.application.services`:
  - `ChivasBusinessService.java`: Casos de uso y pipelines de **programación funcional** (`Stream`, `Predicate`, `Function`, `filter`, `map`, `Collectors.groupingBy` de doble nivel, `reduce` y `sorted`) aplicados en el calendario del dashboard (`GET /bookings/calendar`), cálculo de disponibilidad (`GET /bookings/availability`) y proyecciones inmutables.
- `com.chivasrumberas.infrastructure`:
  - `repositories/PostgresRepositories.java`: Adaptadores `JdbcTemplate` sobre PostgreSQL con soporte nativo para arreglos `TEXT[]`, enumerados (`"ChivaStatus"`, `"BookingStatus"`, `"CalendarSyncStatus"`, `"UserRole"`) y autocompletado automático de reservas cumplidas.
  - `services/ExternalServices.java`: Firma/verificación JWT (`HmacSHA256`), firma SHA-1 de Cloudinary y servicios configurables (`INTEGRATIONS_MODE=mock|real`, `MOCK_LATENCY_MS=50`) para Google Calendar y correo electrónico.
- `com.chivasrumberas.web`:
  - `ApiControllers.java`: Controladores REST, `SecurityAndRateLimitHelper` y `GlobalExceptionHandler`.
  - `MetricsAndObservability.java`: Exposición de `/health` y `/metrics` con Micrometer Prometheus (`chivas_rumberas_http_requests_total`, `chivas_rumberas_http_request_duration_seconds`, `chivas_rumberas_startup_duration_seconds`, memoria, CPU y GC).

---

## Comandos

```powershell
# Ejecutar pruebas de contrato y generar reporte de cobertura JaCoCo
mvn.cmd -f backend-java/pom.xml test

# Empaquetar JAR ejecutable
mvn.cmd -f backend-java/pom.xml package -DskipTests
```
