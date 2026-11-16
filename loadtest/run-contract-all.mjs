/**
 * Fase 5: Ejecutor de Pruebas de Contrato y Equivalencia Funcional en los Tres Backends
 *
 * 1. Restaura la réplica PostgreSQL antes de probar cada backend.
 * 2. Ejecuta la suite `loadtest/contract-tests.mjs` contra Express, Java y Scala.
 * 3. Ejecuta una batería de peticiones determinísticas sobre el estado semilla y
 *    verifica con `assert.deepStrictEqual` que los 3 backends devuelvan exactamente
 *    los mismos códigos HTTP y los mismos payloads JSON (incluyendo la lógica funcional
 *    del dashboard en `/bookings/calendar` y `/bookings/availability`).
 */
import assert from "node:assert/strict";
import { execSync, spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const BACKENDS = [
  { name: "express", url: process.env.EXPRESS_URL || "http://localhost:3001" },
  { name: "java",    url: process.env.JAVA_URL    || "http://localhost:3002" },
  { name: "scala",   url: process.env.SCALA_URL   || "http://localhost:3003" },
];

const ADMIN_EMAIL = process.env.ADMIN_EMAIL || "admin@chivasrumberas.com";
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || "Admin123*Chiva";

function resetDatabaseReplica() {
  execSync(
    "docker exec -i chivas-postgres-replica psql -U chivas -d chivas_rumberas -f /opt/reset-db.sql",
    { stdio: "pipe" }
  );
}

async function fetchJson(baseUrl, endpoint, { method = "GET", token, body } = {}) {
  const headers = {};
  if (token) headers["Authorization"] = `Bearer ${token}`;
  if (body !== undefined) headers["Content-Type"] = "application/json";

  const res = await fetch(`${baseUrl}${endpoint}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  const text = await res.text();
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    parsed = text;
  }
  return { status: res.status, body: parsed };
}

async function collectDeterministicSnapshot(backend) {
  resetDatabaseReplica();

  const loginOk = await fetchJson(backend.url, "/auth/login", {
    method: "POST",
    body: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
  });
  assert.equal(loginOk.status, 200, `[${backend.name}] Login admin falló`);
  const token = loginOk.body.accessToken;

  const loginBad = await fetchJson(backend.url, "/auth/login", {
    method: "POST",
    body: { email: ADMIN_EMAIL, password: "bad_password" },
  });

  const chivasAll = await fetchJson(backend.url, "/chivas");
  const chivaById = await fetchJson(backend.url, "/chivas/la-coqueta-1954");
  const chivaMissing = await fetchJson(backend.url, "/chivas/no-existe-999");
  const travels = await fetchJson(backend.url, "/travels");

  const calendarNov = await fetchJson(backend.url, "/bookings/calendar?month=2026-11", { token });
  const calendarEmpty = await fetchJson(backend.url, "/bookings/calendar?month=2026-12", { token });
  const calendarInvalid = await fetchJson(backend.url, "/bookings/calendar?month=2026-13", { token });

  const availDay20 = await fetchJson(
    backend.url,
    "/bookings/availability?chivaId=la-coqueta-1954&date=2026-11-20"
  );
  const availMissingParam = await fetchJson(
    backend.url,
    "/bookings/availability?chivaId=la-coqueta-1954"
  );

  const bookingsPage1 = await fetchJson(backend.url, "/bookings?page=1&limit=10", { token });
  const bookingsFiltered = await fetchJson(
    backend.url,
    "/bookings?page=1&limit=5&status=confirmed&q=carolina",
    { token }
  );

  const trackOk = await fetchJson(
    backend.url,
    "/bookings/track?code=cr-seed01&email=CAROLINA.MONTOYA@ejemplo.com"
  );
  const trackNotFound = await fetchJson(
    backend.url,
    "/bookings/track?code=CR-SEED01&email=otro@ejemplo.com"
  );

  const metricsRes = await fetchJson(backend.url, "/metrics");

  return {
    loginUser: loginOk.body.user,
    loginBad,
    chivasAll,
    chivaById,
    chivaMissing,
    travels,
    calendarNov,
    calendarEmpty,
    calendarInvalid,
    availDay20,
    availMissingParam,
    bookingsPage1,
    bookingsFiltered,
    trackOk,
    trackNotFound,
    hasHttpCounterMetric:
      typeof metricsRes.body === "string" &&
      metricsRes.body.includes("chivas_rumberas_http_requests_total"),
    hasHttpHistogramMetric:
      typeof metricsRes.body === "string" &&
      metricsRes.body.includes("chivas_rumberas_http_request_duration_seconds_bucket"),
    hasStartupMetric:
      typeof metricsRes.body === "string" &&
      metricsRes.body.includes("chivas_rumberas_startup_duration_seconds"),
  };
}

async function main() {
  console.log("=======================================================================");
  console.log(" FASE 5: Pruebas de Contrato y Equivalencia contra los 3 Backends");
  console.log("=======================================================================\n");

  const suiteSummary = {};

  for (const backend of BACKENDS) {
    console.log(`▶ [1/2] Restaurando BD y ejecutando contract-tests.mjs contra ${backend.name.toUpperCase()} (${backend.url})...`);
    resetDatabaseReplica();

    const proc = spawnSync(
      process.execPath,
      ["--test", path.resolve("loadtest/contract-tests.mjs")],
      {
        env: { ...process.env, TARGET_URL: backend.url },
        encoding: "utf-8",
      }
    );

    if (proc.status !== 0) {
      console.error(`❌ Falló la suite de contrato en ${backend.name}:\n${proc.stdout}\n${proc.stderr}`);
      process.exit(1);
    }
    console.log(`✅ Suite de contrato aprobada en ${backend.name.toUpperCase()}.`);
    suiteSummary[backend.name] = { contractSuitePassed: true };
  }

  console.log("\n▶ [2/2] Recolectando snapshots determinísticos para verificar equivalencia 1:1...");
  const snapshots = {};
  for (const backend of BACKENDS) {
    snapshots[backend.name] = await collectDeterministicSnapshot(backend);
  }

  const keysToCompare = [
    "loginUser",
    "loginBad",
    "chivasAll",
    "chivaById",
    "chivaMissing",
    "travels",
    "calendarNov",
    "calendarEmpty",
    "calendarInvalid",
    "availDay20",
    "availMissingParam",
    "bookingsPage1",
    "bookingsFiltered",
    "trackOk",
    "trackNotFound",
    "hasHttpCounterMetric",
    "hasHttpHistogramMetric",
    "hasStartupMetric",
  ];

  const comparisons = [];
  for (const key of keysToCompare) {
    assert.deepStrictEqual(
      snapshots.java[key],
      snapshots.express[key],
      `Diferencia detectada entre Java y Express en '${key}'`
    );
    assert.deepStrictEqual(
      snapshots.scala[key],
      snapshots.express[key],
      `Diferencia detectada entre Scala y Express en '${key}'`
    );
    comparisons.push({ check: key, expressEqualsJava: true, expressEqualsScala: true });
    console.log(`  ✔ Equivalencia exacta verificada en: ${key}`);
  }

  resetDatabaseReplica();

  const outDir = path.resolve("analysis/results");
  fs.mkdirSync(outDir, { recursive: true });
  const reportPath = path.join(outDir, "contract-equivalence-report.json");
  fs.writeFileSync(
    reportPath,
    JSON.stringify(
      {
        timestamp: new Date().toISOString(),
        status: "PASSED",
        backends: BACKENDS,
        suiteSummary,
        comparisons,
      },
      null,
      2
    )
  );

  console.log(`\n🎉 ÉXITO TOTAL: Los 3 backends (Express, Java, Scala) pasaron todas las pruebas de contrato con resultados 100% idénticos.`);
  console.log(`📄 Reporte guardado en: ${reportPath}`);
}

main().catch((err) => {
  console.error("❌ Error en verificación de contrato:", err);
  process.exit(1);
});
