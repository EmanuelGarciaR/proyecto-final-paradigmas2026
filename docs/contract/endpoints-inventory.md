# Inventario de Endpoints Reales y Homologación de Observabilidad (Fase 1)

Este documento enumera la totalidad de los endpoints reales extraídos de `/backend-express` (sin inventar ninguno adicional), su matriz de permisos, sus códigos de error de dominio y el contrato de métricas Prometheus homologado para los tres backends.

---

## 1. Inventario Completo de Endpoints (16 Endpoints)

| # | Método | Ruta | Acceso / Rol | Rate Limit | Caso de Uso / Controlador | Códigos HTTP |
| :-: | :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | `GET` | `/health` | Público | No | Health check (`app.ts`) | `200` |
| 2 | `GET` | `/metrics` | Público | No | `metricsHandler` (`metrics.ts`) | `200` (`text/plain`) |
| 3 | `POST` | `/auth/login` | Público | Sí (10 req / 15 min) | `LoginUseCase` | `200`, `401`, `429` |
| 4 | `GET` | `/chivas` | Público | No | `GetChivasUseCase` (`?status=`) | `200` |
| 5 | `GET` | `/chivas/:id` | Público | No | `GetChivaByIdUseCase` | `200`, `404` |
| 6 | `POST` | `/chivas` | JWT (`admin`) | No | `CreateChivaUseCase` | `201`, `400`, `401`, `403` |
| 7 | `PATCH` | `/chivas/:id` | JWT (`admin`) | No | `UpdateChivaUseCase` | `200`, `400`, `401`, `403`, `404` |
| 8 | `PATCH` | `/chivas/:id/status` | JWT (`admin`) | No | `UpdateChivaStatusUseCase` | `200`, `400`, `401`, `403`, `404` |
| 9 | `DELETE` | `/chivas/:id` | JWT (`admin`) | No | `DeleteChivaUseCase` (borrado lógico a `retired`) | `200`, `400`, `401`, `403`, `404`, `409` |
| 10 | `GET` | `/travels` | Público | No | `GetTravelsUseCase` (`isActive = true`) | `200` |
| 11 | `GET` | `/bookings/availability` | Público | No | `GetAvailabilityUseCase` (`?chivaId=&date=`) | `200`, `400`, `404` |
| 12 | `GET` | `/bookings/track` | Público | Sí (15 req / 15 min) | `TrackBookingUseCase` (`?code=&email=`) | `200`, `400`, `404`, `429` |
| 13 | `POST` | `/bookings` | Público | No | `CreateBookingUseCase` | `201`, `400`, `404`, `409` |
| 14 | `GET` | `/bookings/calendar` | JWT (`admin`) | No | `GetBookingsCalendarUseCase` (`?month=YYYY-MM`) | `200`, `400`, `401`, `403` |
| 15 | `GET` | `/bookings` | JWT (`admin`) | No | `GetBookingsUseCase` (`?page=&limit=&status=&q=`) | `200`, `400`, `401`, `403` |
| 16 | `GET` | `/bookings/:id` | JWT (`admin`, `operator`) | No | `GetBookingByIdUseCase` | `200`, `401`, `404` |
| 17 | `PATCH` | `/bookings/:id/status` | JWT (`admin`, `operator`) | No | `UpdateBookingStatusUseCase` | `200`, `400`, `401`, `404`, `422` |
| 18 | `POST` | `/bookings/:id/calendar-sync` | JWT (`admin`) | No | `SyncBookingCalendarUseCase` | `200`, `400`, `401`, `403`, `404` |
| 19 | `GET` | `/uploads/signature` | JWT (`admin`) | No | `UploadsController.getSignature` | `200`, `401`, `403`, `500` |

*(Total: 19 rutas HTTP incluyendo `/health` y `/metrics`, agrupadas en 5 módulos de dominio + observabilidad).*

---

## 2. Contrato de Errores HTTP (`errorHandler`)

Todas las respuestas de error siguen la estructura JSON exacta:

```json
{
  "error": {
    "type": "NombreDelError",
    "message": "Descripción legible del error."
  }
}
```

### Tabla de Mapeo de Errores de Dominio a HTTP Status

| Tipo de Error (`error.type`) | HTTP Status | Cuándo ocurre |
| :--- | :-: | :--- |
| `ValidationError` | `400` | Parámetros de consulta/body inválidos, fecha `< 24h`, hora inválida, `guestCount > capacity`, o `calendar-sync` sobre reserva no confirmada. |
| `InvalidCredentialsError` | `401` | Email no registrado o contraseña incorrecta en `POST /auth/login`. |
| `InvalidTokenError` | `401` | Header `Authorization: Bearer <token>` ausente, malformado, expirado o con firma inválida. |
| `UnauthorizedError` | `403` | Usuario autenticado cuyo `role` no está autorizado (p. ej. `operator` intentando acceder a rutas exclusivas de `admin`). |
| `ChivaNotFoundError` | `404` | No existe chiva con el `id` solicitado. |
| `BookingNotFoundError` | `404` | No existe reserva con el `id`/`code` solicitado, o el `email` no coincide en `/bookings/track`. |
| `TravelNotFoundError` | `404` | No existe recorrido con el `travelId` solicitado al crear una reserva. |
| `ChivaNotAvailableError` | `409` | La chiva existe pero su `status !== "available"` al intentar crear una reserva. |
| `BookingConflictError` | `409` | Solapamiento de horario con otra reserva `pending` o `confirmed` de la misma chiva en la misma fecha. |
| `ChivaHasActiveBookingsError` | `409` | Intento de eliminar (`DELETE /chivas/:id`) una chiva con reservas futuras `pending` o `confirmed`. |
| `InvalidBookingStatusTransitionError` | `422` | Transición de estado no permitida en `PATCH /bookings/:id/status`. |
| `TooManyRequestsError` | `429` | Superado el rate limit en `/auth/login` o `/bookings/track`. |
| `InternalServerError` | `500` | Error no controlado o credenciales de infraestructura faltantes (p. ej. Cloudinary sin configurar). |

---

## 3. Integraciones Externas y Modo Mock / Real

1. **Correo Electrónico (Resend — `IEmailService`)**:
   - Invocado en `POST /bookings` (`sendBookingConfirmation` + `sendNewBookingAlert`) y en `PATCH /bookings/:id/status` cuando cambia el estado (`sendBookingStatusUpdate`).
   - `NotificationService` usa `Promise.allSettled` para que un fallo en un canal no aborte la respuesta HTTP.
2. **Google Calendar (`CalendarService`)**:
   - Invocado en `PATCH /bookings/:id/status`:
     - Al pasar a `confirmed`: si no tiene `googleEventId`, llama a `createEvent` y actualiza `calendarSyncStatus = "synced"` (o `"failed"` si lanza excepción, sin abortar el cambio de estado). Si ya tenía `googleEventId`, es idempotente.
     - Al pasar a `cancelled`: si tenía `googleEventId`, llama a `deleteEvent` y limpia `googleEventId = null, calendarSyncStatus = "synced"` (o `"failed"` si falla).
   - Invocado en `POST /bookings/:id/calendar-sync`: reintenta `createEvent` solo si `status === "confirmed"`.
3. **Cloudinary (`ImageStorage`)**:
   - Operación puramente criptográfica local (firma SHA-1 de `allowed_formats`, `folder`, `timestamp` + `CLOUDINARY_API_SECRET`) en `GET /uploads/signature`.
4. **Comportamiento en Pruebas de Carga (`INTEGRATIONS_MODE=mock`)**:
   - Tanto Google Calendar como el envío de correos aplican una latencia fija simulada (`MOCK_LATENCY_MS`, por defecto `50 ms`) sin realizar peticiones de red externas, garantizando condiciones idénticas entre Express, Java y Scala.

---

## 4. Contrato Homologado de Métricas Prometheus (`/metrics`)

Definido en `backend-express/interface/observability/metrics.ts` y replicado en Java y Scala mediante Micrometer Prometheus Registry:

| Métrica | Tipo | Labels | Buckets / Detalle |
| :--- | :--- | :--- | :--- |
| `chivas_rumberas_http_requests_total` | `Counter` | `method`, `route`, `status_code` | Total de solicitudes HTTP procesadas por método, ruta parametrizada (p. ej. `/bookings/:id/status`) y código HTTP. |
| `chivas_rumberas_http_request_duration_seconds` | `Histogram` | `method`, `route` | Duración de solicitudes HTTP en segundos. Buckets: `[0.005, 0.01, 0.05, 0.1, 0.5, 1, 2, 5]`. |
| `chivas_rumberas_process_*` / `jvm_*` | `Gauge` / `Counter` | Estándar runtime | Métricas de proceso (CPU, memoria residente/heap, GC en JVM, event loop lag en Node.js). |
