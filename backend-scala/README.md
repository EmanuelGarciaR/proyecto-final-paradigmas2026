# Backend Scala (Scala 3.3.4 + sbt + http4s + Cats Effect)

Implementación completa y homologada del backend de **Chivas Rumberas** en **Scala 3 + sbt + http4s (Ember Server)**, sin frameworks pesados y cumpliendo el 100 % del contrato OpenAPI 3.0.3 extraído de `/backend-express`.

---

## Arquitectura

- `com.chivasrumberas.domain`:
  - `Domain.scala`: `case class`es inmutables (`Chiva`, `Travel`, `Customer`, `Booking`, `User`, `JwtPayload`), Value Objects (`TimeSlot`, `EventDate`), funciones puras (`hasBookingDateTimePassed`, `formatEventDateBogota`, `generateBookingCode`, `slugify`) y `DomainException` para los 13 errores de dominio.
  - `Repositories.scala`: Traits de repositorios.
- `com.chivasrumberas.application`:
  - `ChivasBusinessService.scala`: Casos de uso en **Scala 3 funcional puro** (funciones de orden superior `Booking => Boolean`, `Booking => CalendarSlotDto`, `Chiva => ChivaResponseDto`, recursión de cola `@tailrec`, colecciones inmutables `List`/`Vector`/`Map`, `filter`, `groupBy`, `foldLeft`, `map` y `sortBy`).
- `com.chivasrumberas.infrastructure`:
  - `PostgresRepositories.scala`: Repositorios JDBC + HikariCP sobre PostgreSQL con soporte nativo para `TEXT[]` y enumerados (`"ChivaStatus"`, `"BookingStatus"`, `"CalendarSyncStatus"`, `"UserRole"`).
  - `ExternalServices.scala`: `HmacJwtService`, `CloudinaryImageStorageService` y servicios configurables (`INTEGRATIONS_MODE=mock|real`, `MOCK_LATENCY_MS=50`) para Google Calendar y correo electrónico.
- `com.chivasrumberas.web`:
  - `ApiDtos.scala`: DTOs y codecs JSON con `Circe`.
  - `HttpAppBuilder.scala` y `Main.scala`: Rutas `http4s`, `SecurityAndRateLimiter` y exposición de `/health` y `/metrics` con Micrometer Prometheus (`chivas_rumberas_http_requests_total`, `chivas_rumberas_http_request_duration_seconds`, `chivas_rumberas_startup_duration_seconds`, memoria, CPU y GC).

---

## Comandos

```powershell
# Ejecutar suite de pruebas de contrato y cobertura scoverage
sbt coverage test coverageReport

# Empaquetar fat-JAR ejecutable
sbt assembly
```
