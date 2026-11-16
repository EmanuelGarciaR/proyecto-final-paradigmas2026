/**
 * Suite de Pruebas de Contrato HTTP (OpenAPI 3.0.3)
 * Ejecutable contra cualquiera de los 3 backends mediante:
 *   TARGET_URL=http://localhost:3001 node --test loadtest/contract-tests.mjs
 */
import assert from "node:assert/strict";
import { describe, it, before } from "node:test";

const TARGET_URL = (process.env.TARGET_URL || "http://localhost:3001").replace(/\/+$/, "");
const ADMIN_EMAIL = process.env.ADMIN_EMAIL || "admin@chivasrumberas.com";
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || "Admin123*Chiva";
const OPERATOR_EMAIL = process.env.OPERATOR_EMAIL || "operador@chivasrumberas.com";
const OPERATOR_PASSWORD = process.env.OPERATOR_PASSWORD || "Operador123*Chiva";

async function apiRequest(path, { method = "GET", token, body } = {}) {
  const headers = {};
  if (token) headers["Authorization"] = `Bearer ${token}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";

  const res = await fetch(`${TARGET_URL}${path}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  const contentType = res.headers.get("content-type") || "";
  const data = contentType.includes("application/json")
    ? await res.json()
    : await res.text();

  return { status: res.status, headers: res.headers, body: data };
}

describe(`Contrato OpenAPI Homologado — Target: ${TARGET_URL}`, () => {
  let adminToken = "";
  let operatorToken = "";
  let createdBookingId = "";
  let createdChivaId = "";

  before(async () => {
    const adminRes = await apiRequest("/auth/login", {
      method: "POST",
      body: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
    });
    assert.equal(adminRes.status, 200, `Login admin falló: ${JSON.stringify(adminRes.body)}`);
    adminToken = adminRes.body.accessToken;

    const opRes = await apiRequest("/auth/login", {
      method: "POST",
      body: { email: OPERATOR_EMAIL, password: OPERATOR_PASSWORD },
    });
    assert.equal(opRes.status, 200, `Login operador falló: ${JSON.stringify(opRes.body)}`);
    operatorToken = opRes.body.accessToken;
  });

  it("1. GET /health y GET /metrics exponen estado y métricas Prometheus homologadas", async () => {
    const health = await apiRequest("/health");
    assert.equal(health.status, 200);
    assert.equal(health.body.status, "ok");
    assert.equal(typeof health.body.timestamp, "string");

    const metrics = await apiRequest("/metrics");
    assert.equal(metrics.status, 200);
    assert.equal(typeof metrics.body, "string");
    assert.equal(metrics.body.includes("chivas_rumberas_http_requests_total"), true);
    assert.equal(metrics.body.includes("chivas_rumberas_http_request_duration_seconds"), true);
  });

  it("2. POST /auth/login rechaza credenciales inválidas con 401 InvalidCredentialsError", async () => {
    const res = await apiRequest("/auth/login", {
      method: "POST",
      body: { email: ADMIN_EMAIL, password: "WrongPassword123*" },
    });
    assert.equal(res.status, 401);
    assert.equal(res.body.error.type, "InvalidCredentialsError");
  });

  it("3. GET /chivas y GET /travels devuelven catálogos públicos con esquema válido", async () => {
    const chivas = await apiRequest("/chivas");
    assert.equal(chivas.status, 200);
    assert.equal(Array.isArray(chivas.body), true);
    assert.equal(chivas.body.length >= 1, true);
    assert.equal(typeof chivas.body[0].id, "string");
    assert.equal(typeof chivas.body[0].capacity, "number");

    const travels = await apiRequest("/travels");
    assert.equal(travels.status, 200);
    assert.equal(Array.isArray(travels.body), true);
    assert.equal(travels.body.length >= 1, true);
    assert.equal(typeof travels.body[0].id, "string");
    assert.equal(typeof travels.body[0].durationHours, "number");
  });

  it("4. Permisos 401 y 403 en endpoints exclusivos de administrador", async () => {
    const protectedEndpoints = [
      { method: "GET", path: "/bookings" },
      { method: "GET", path: "/bookings/calendar?month=2026-11" },
      { method: "GET", path: "/uploads/signature" },
    ];

    for (const ep of protectedEndpoints) {
      const noToken = await apiRequest(ep.path, { method: ep.method });
      assert.equal(noToken.status, 401);
      assert.equal(noToken.body.error.type, "InvalidTokenError");

      const operatorRes = await apiRequest(ep.path, {
        method: ep.method,
        token: operatorToken,
      });
      assert.equal(operatorRes.status, 403);
      assert.equal(operatorRes.body.error.type, "UnauthorizedError");
    }
  });

  it("5. Ciclo de vida completo de Chiva (POST, GET/:id, PATCH, PATCH/status, DELETE)", async () => {
    const uniqueSuffix = Date.now().toString().slice(-5);
    const createRes = await apiRequest("/chivas", {
      method: "POST",
      token: adminToken,
      body: {
        name: `La Exploradora ${uniqueSuffix}`,
        description: "Chiva de prueba de contrato",
        capacity: 35,
        pricePerHour: 290000,
        features: ["Sonido 3000W"],
      },
    });
    assert.equal(createRes.status, 201);
    createdChivaId = createRes.body.id;

    const getRes = await apiRequest(`/chivas/${createdChivaId}`);
    assert.equal(getRes.status, 200);
    assert.equal(getRes.body.id, createdChivaId);

    const patchRes = await apiRequest(`/chivas/${createdChivaId}`, {
      method: "PATCH",
      token: adminToken,
      body: { pricePerHour: 310000 },
    });
    assert.equal(patchRes.status, 200);
    assert.equal(patchRes.body.pricePerHour, 310000);

    const statusRes = await apiRequest(`/chivas/${createdChivaId}/status`, {
      method: "PATCH",
      token: adminToken,
      body: { status: "maintenance" },
    });
    assert.equal(statusRes.status, 200);
    assert.equal(statusRes.body.status, "maintenance");

    const availAgain = await apiRequest(`/chivas/${createdChivaId}/status`, {
      method: "PATCH",
      token: adminToken,
      body: { status: "available" },
    });
    assert.equal(availAgain.status, 200);
    assert.equal(availAgain.body.status, "available");
  });

  it("6. Ciclo de vida de Reserva, Disponibilidad Funcional, Seguimiento y Calendario Dashboard", async () => {
    const futureDate = new Date(Date.now() + 14 * 24 * 60 * 60 * 1000)
      .toISOString()
      .split("T")[0];
    const futureMonth = futureDate.slice(0, 7);

    const createBookingRes = await apiRequest("/bookings", {
      method: "POST",
      body: {
        chivaId: createdChivaId,
        travelId: "trv_alumbrados",
        customerName: "Cliente Contrato",
        customerEmail: "Contrato.Test@Example.com",
        customerPhone: "3005556677",
        guestCount: 20,
        eventDate: futureDate,
        startTime: "19:00",
        durationHours: 3,
        notes: "Reserva de prueba de contrato",
      },
    });
    assert.equal(createBookingRes.status, 201);
    createdBookingId = createBookingRes.body.id;
    assert.equal(createBookingRes.body.status, "pending");
    assert.equal(createBookingRes.body.endTime, "22:00");

    // Disponibilidad funcional
    const availRes = await apiRequest(
      `/bookings/availability?chivaId=${createdChivaId}&date=${futureDate}`
    );
    assert.equal(availRes.status, 200);
    assert.equal(availRes.body.occupiedSlots.length, 1);
    assert.equal(availRes.body.isFullyBooked, false);

    // Tracking público case-insensitive
    const trackRes = await apiRequest(
      `/bookings/track?code=${createdBookingId.toLowerCase()}&email=CONTRATO.TEST@example.com`
    );
    assert.equal(trackRes.status, 200);
    assert.equal(trackRes.body.id, createdBookingId);
    assert.equal(trackRes.body.customerEmail, undefined);

    // Confirmar reserva y negociar precio
    const confirmRes = await apiRequest(`/bookings/${createdBookingId}/status`, {
      method: "PATCH",
      token: adminToken,
      body: { status: "confirmed", totalPrice: 880000 },
    });
    assert.equal(confirmRes.status, 200);
    assert.equal(confirmRes.body.status, "confirmed");
    assert.equal(confirmRes.body.totalPrice, 880000);

    // Calendario mensual del dashboard (programación funcional)
    const calRes = await apiRequest(`/bookings/calendar?month=${futureMonth}`, {
      token: adminToken,
    });
    assert.equal(calRes.status, 200);
    assert.equal(calRes.body.month, futureMonth);
    const matchingDay = calRes.body.days.find((d) => d.date === futureDate);
    assert.ok(matchingDay, "El día de la reserva confirmada debe aparecer en el calendario");

    // Cancelar reserva para permitir el borrado lógico de la chiva
    const cancelRes = await apiRequest(`/bookings/${createdBookingId}/status`, {
      method: "PATCH",
      token: adminToken,
      body: { status: "cancelled" },
    });
    assert.equal(cancelRes.status, 200);
    assert.equal(cancelRes.body.status, "cancelled");

    // Borrado lógico de la chiva
    const delChivaRes = await apiRequest(`/chivas/${createdChivaId}`, {
      method: "DELETE",
      token: adminToken,
    });
    assert.equal(delChivaRes.status, 200);
    assert.equal(delChivaRes.body.status, "retired");
  });
});
