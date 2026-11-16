/**
 * Bootstrap / Preload de observabilidad y mocks para backend-express.
 * Se carga mediante NODE_OPTIONS="--require /opt/mock-integrations.cjs"
 * sin modificar ni una sola línea del código fuente dentro de /backend-express.
 */
const processStartHr = process.hrtime.bigint();
const Module = require("node:module");
const http = require("node:http");

const integrationsMode = (process.env.INTEGRATIONS_MODE || "mock").toLowerCase();
const mockLatencyMs = Number(process.env.MOCK_LATENCY_MS ?? 50);
const disableRateLimit = (process.env.DISABLE_RATE_LIMIT || "false").toLowerCase() === "true";

// Asegurar valores por defecto en modo mock para que los constructores no fallen si no hay API keys reales
if (integrationsMode === "mock") {
  process.env.RESEND_API_KEY = process.env.RESEND_API_KEY || "re_mock_key_for_loadtest";
  process.env.GOOGLE_SERVICE_ACCOUNT_EMAIL =
    process.env.GOOGLE_SERVICE_ACCOUNT_EMAIL || "mock@chivas-rumberas.iam.gserviceaccount.com";
  process.env.GOOGLE_PRIVATE_KEY =
    process.env.GOOGLE_PRIVATE_KEY || "-----BEGIN PRIVATE KEY-----\\nMOCK\\n-----END PRIVATE KEY-----\\n";
  process.env.GOOGLE_CALENDAR_ID =
    process.env.GOOGLE_CALENDAR_ID || "mock-calendar@group.calendar.google.com";
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

let startupGauge = null;
let startupRecorded = false;

// Interceptamos http.Server.prototype.listen para medir el tiempo exacto de arranque del servidor HTTP
const originalListen = http.Server.prototype.listen;
http.Server.prototype.listen = function (...args) {
  this.once("listening", () => {
    if (!startupRecorded && startupGauge) {
      const elapsedSeconds = Number(process.hrtime.bigint() - processStartHr) / 1e9;
      startupGauge.set(elapsedSeconds);
      startupRecorded = true;
    }
  });
  return originalListen.apply(this, args);
};

const originalLoad = Module._load;
Module._load = function (request, parent, isMain) {
  const exported = originalLoad.apply(this, arguments);

  // 1. Registrar métrica homologada de tiempo de arranque en prom-client
  if (request === "prom-client" && exported && exported.Gauge && !startupGauge) {
    const existing = exported.register.getSingleMetric("chivas_rumberas_startup_duration_seconds");
    if (existing) {
      startupGauge = existing;
    } else {
      startupGauge = new exported.Gauge({
        name: "chivas_rumberas_startup_duration_seconds",
        help: "Tiempo de arranque del backend en segundos.",
      });
    }
  }

  // 2. Desactivar rate-limit únicamente cuando DISABLE_RATE_LIMIT=true (ej. pruebas de carga k6)
  if (disableRateLimit && request === "express-rate-limit") {
    return () => (_req, _res, next) => next();
  }

  // 3. Interceptar servicios externos en modo mock con latencia fija simulada
  if (integrationsMode === "mock" && typeof request === "string") {
    if (
      request.includes("email.resend.service") &&
      exported &&
      exported.EmailResendService
    ) {
      const proto = exported.EmailResendService.prototype;
      proto.sendBookingConfirmation = async function (_booking) {
        await sleep(mockLatencyMs);
      };
      proto.sendNewBookingAlert = async function (_booking) {
        await sleep(mockLatencyMs);
      };
      proto.sendBookingStatusUpdate = async function (_booking, _previousStatus) {
        await sleep(mockLatencyMs);
      };
    }

    if (
      request.includes("google-calendar.service") &&
      exported &&
      exported.GoogleCalendarService
    ) {
      const proto = exported.GoogleCalendarService.prototype;
      proto.createEvent = async function (event) {
        await sleep(mockLatencyMs);
        if (event && event.notes && String(event.notes).includes("[FORCE_CALENDAR_ERROR]")) {
          throw new Error("Simulated Google Calendar failure");
        }
        return `gcal-mock-${event?.bookingId || Date.now()}`;
      };
      proto.deleteEvent = async function (_googleEventId) {
        await sleep(mockLatencyMs);
      };
    }
  }

  return exported;
};
