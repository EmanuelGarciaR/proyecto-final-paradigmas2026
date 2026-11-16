/**
 * Fase 6: Script k6 de Pruebas de Carga bajo Condiciones Equivalentes
 *
 * Escenarios soportados (configurados desde `loadtest/config/scenarios.json`):
 *   - warmup : Calentamiento previo (obligatorio para JIT de la JVM en Java y Scala)
 *   - low    : Carga baja
 *   - medium : Carga media
 *   - high   : Carga alta
 *   - spike  : Pico súbito de tráfico (rampa rápida)
 *   - soak   : Resistencia sostenida
 *
 * Reporta percentiles completos: p(50), p(90), p(95), p(99), mediana, promedio y tasa de error.
 */
import http from "k6/http";
import { check, sleep } from "k6";
import { Rate, Trend } from "k6/metrics";

const TARGET_URL = (__ENV.TARGET_URL || "http://host.docker.internal:3001").replace(/\/+$/, "");
const SCENARIO_TYPE = (__ENV.SCENARIO_TYPE || "low").toLowerCase();
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || "admin@chivasrumberas.com";
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || "Admin123*Chiva";

const errorRate = new Rate("chivas_error_rate");
const dashboardCalendarTrend = new Trend("chivas_dashboard_calendar_duration", true);
const availabilityTrend = new Trend("chivas_availability_duration", true);
const paginatedBookingsTrend = new Trend("chivas_paginated_bookings_duration", true);

function buildScenarioOptions() {
  const vus = Number(__ENV.VUS || 10);
  const duration = __ENV.DURATION || "20s";

  if (SCENARIO_TYPE === "spike") {
    const s1d = __ENV.SPIKE_S1_DUR || "5s";
    const s1t = Number(__ENV.SPIKE_S1_VUS || 10);
    const s2d = __ENV.SPIKE_S2_DUR || "10s";
    const s2t = Number(__ENV.SPIKE_S2_VUS || 80);
    const s3d = __ENV.SPIKE_S3_DUR || "10s";
    const s3t = Number(__ENV.SPIKE_S3_VUS || 10);
    const s4d = __ENV.SPIKE_S4_DUR || "5s";
    return {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: s1d, target: s1t },
        { duration: s2d, target: s2t },
        { duration: s3d, target: s3t },
        { duration: s4d, target: 0 },
      ],
      gracefulRampDown: "5s",
    };
  }

  return {
    executor: "constant-vus",
    vus,
    duration,
  };
}

export const options = {
  scenarios: {
    benchmark: buildScenarioOptions(),
  },
  summaryTrendStats: ["avg", "min", "med", "max", "p(50)", "p(90)", "p(95)", "p(99)"],
};

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409));

export function setup() {
  const loginRes = http.post(
    `${TARGET_URL}/auth/login`,
    JSON.stringify({ email: ADMIN_EMAIL, password: ADMIN_PASSWORD }),
    { headers: { "Content-Type": "application/json" } }
  );

  let token = __ENV.ADMIN_TOKEN || "";
  if (loginRes.status === 200) {
    try {
      token = JSON.parse(loginRes.body).accessToken;
    } catch (_) {
      // fallback
    }
  }
  return { token };
}

const CHIVA_IDS = [
  "la-coqueta-1954",
  "la-perla-1959",
  "la-parrandera-1962",
  "el-arriero-1957",
];

const MONTHS = ["2026-11", "2026-12", "2027-01"];

export default function (data) {
  const authHeaders = {
    headers: {
      Authorization: `Bearer ${data.token}`,
      "Content-Type": "application/json",
    },
  };

  const iter = __ITER;
  const vu = __VU;
  const selector = (iter + vu) % 10;

  if (selector < 3) {
    // 1. Dashboard Admin: Calendario mensual (Programación Funcional: filter + groupBy + map + sortBy)
    const month = MONTHS[(iter + vu) % MONTHS.length];
    const res = http.get(`${TARGET_URL}/bookings/calendar?month=${month}`, authHeaders);
    dashboardCalendarTrend.add(res.timings.duration);
    const ok = check(res, {
      "calendar 200": (r) => r.status === 200,
    });
    errorRate.add(!ok);
  } else if (selector < 5) {
    // 2. Dashboard Admin: Listado paginado y filtrado de reservas
    const page = 1 + ((iter + vu) % 5);
    const res = http.get(
      `${TARGET_URL}/bookings?page=${page}&limit=20&status=confirmed`,
      authHeaders
    );
    paginatedBookingsTrend.add(res.timings.duration);
    const ok = check(res, {
      "bookings paginated 200": (r) => r.status === 200,
    });
    errorRate.add(!ok);
  } else if (selector < 7) {
    // 3. Disponibilidad por Chiva y Fecha (Programación Funcional: map + reduce/foldLeft)
    const chivaId = CHIVA_IDS[(iter + vu) % CHIVA_IDS.length];
    const day = String(18 + ((iter + vu) % 10)).padStart(2, "0");
    const res = http.get(
      `${TARGET_URL}/bookings/availability?chivaId=${chivaId}&date=2026-11-${day}`
    );
    availabilityTrend.add(res.timings.duration);
    const ok = check(res, {
      "availability 200": (r) => r.status === 200,
    });
    errorRate.add(!ok);
  } else if (selector === 7 || selector === 8) {
    // 4. Catálogo público de Chivas y Recorridos + Seguimiento
    const resChivas = http.get(`${TARGET_URL}/chivas?status=available`);
    const resTravels = http.get(`${TARGET_URL}/travels`);
    const ok = check(resChivas, { "chivas 200": (r) => r.status === 200 }) &&
               check(resTravels, { "travels 200": (r) => r.status === 200 });
    errorRate.add(!ok);
  } else {
    // 5. Creación de reserva + firma de subida (ejercita escritura en BD y mock de latencia fija 50ms)
    const chivaId = CHIVA_IDS[(iter + vu) % CHIVA_IDS.length];
    const uniqueDayOffset = ((vu * 997 + iter * 31) % 3000) + 800; // Fechas >= 2029 para no colisionar
    const baseMs = Date.UTC(2026, 10, 15) + uniqueDayOffset * 86400000;
    const eventDate = new Date(baseMs).toISOString().split("T")[0];
    const hour = String(8 + ((iter + vu) % 12)).padStart(2, "0");

    const createRes = http.post(
      `${TARGET_URL}/bookings`,
      JSON.stringify({
        chivaId,
        travelId: "trv_alumbrados",
        customerName: `VU ${vu} Iter ${iter}`,
        customerEmail: `vu${vu}.iter${iter}@loadtest.chivas.co`,
        customerPhone: "3001234567",
        guestCount: 20,
        eventDate,
        startTime: `${hour}:00`,
        durationHours: 2,
        notes: "Loadtest k6",
      }),
      { headers: { "Content-Type": "application/json" } }
    );

    const ok = check(createRes, {
      "create booking 201 or 409": (r) => r.status === 201 || r.status === 409,
    });
    errorRate.add(!ok);
  }

  sleep(0.05);
}
