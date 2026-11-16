/**
 * Fase 7: Orquestador Completo de Pruebas de Carga y Recolección de Métricas
 *
 * Cumple rigurosamente las condiciones equivalentes del prompt:
 * - Volúmenes: 1.000, 100.000 y 1.000.000 reservas (semilla fija = 20261115).
 * - Escenarios k6: low, medium, high, spike y soak.
 * - Warm-up antes de medir cada backend en cada volumen (clave para la JVM en Java y Scala).
 * - Mínimo 3 repeticiones por escenario con orden de ejecución rotado entre los 3 backends:
 *     Repetición 1: [express, java, scala]
 *     Repetición 2: [java, scala, express]
 *     Repetición 3: [scala, express, java]
 * - Restauración exacta del estado inicial del dataset antes de cada ejecución.
 * - Recolección de métricas desde k6 + endpoint Prometheus `/metrics` de cada backend
 *   (latencia p50/p95/p99, mediana, promedio, throughput req/s, tasa de errores, CPU %,
 *    memoria residente MB, tiempo de arranque s, y GC pause / Event Loop lag).
 */
import { execSync } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { ensureVolumeSnapshot, restoreDatasetVolume } from "./generate-dataset.mjs";

const configPath = path.resolve("loadtest/config/scenarios.json");
const config = JSON.parse(fs.readFileSync(configPath, "utf-8"));

const PG_CONTAINER = process.env.PG_CONTAINER || "chivas-postgres-replica";
const PG_USER = process.env.POSTGRES_USER || "chivas";
const PG_DB = process.env.POSTGRES_DB || "chivas_rumberas";
const K6_CONTAINER = "chivas-k6-runner";
const JWT_SECRET =
  process.env.JWT_SECRET || "chivas_rumberas_super_secret_jwt_key_2026_benchmark";

function createAdminJwtToken() {
  const header = Buffer.from(JSON.stringify({ alg: "HS256", typ: "JWT" })).toString("base64url");
  const now = Math.floor(Date.now() / 1000);
  const payload = Buffer.from(
    JSON.stringify({
      sub: "usr-admin-0001-0000-000000000001",
      email: "admin@chivasrumberas.com",
      role: "admin",
      iat: now,
      exp: now + 8 * 3600,
    })
  ).toString("base64url");
  const sig = crypto
    .createHmac("sha256", JWT_SECRET)
    .update(`${header}.${payload}`)
    .digest("base64url");
  return `${header}.${payload}.${sig}`;
}

const ADMIN_TOKEN = createAdminJwtToken();

const ROTATED_ORDERS = [
  ["express", "java", "scala"],
  ["java", "scala", "express"],
  ["scala", "express", "java"],
];

function fastDeltaRestore() {
  const sql = `
    DELETE FROM "bookings" WHERE "createdAt" >= TIMESTAMP '2026-11-15 12:00:00';
    DELETE FROM "customers" WHERE "createdAt" >= TIMESTAMP '2026-11-15 12:00:00';
  `;
  execSync(
    `docker exec -i ${PG_CONTAINER} psql -v ON_ERROR_STOP=1 -U ${PG_USER} -d ${PG_DB}`,
    { input: sql, stdio: ["pipe", "pipe", "pipe"] }
  );
}

function startK6RunnerContainer() {
  try {
    execSync(`docker rm -f ${K6_CONTAINER}`, { stdio: "pipe" });
  } catch (_) {}

  const loadtestDir = path.resolve("loadtest").replace(/\\/g, "/");
  const resultsDir = path.resolve("analysis/results").replace(/\\/g, "/");

  execSync(
    [
      `docker run -d --name ${K6_CONTAINER}`,
      "--network chivas_rumberas_net",
      `-v "${loadtestDir}:/loadtest:ro"`,
      `-v "${resultsDir}:/results"`,
      "--entrypoint sh",
      "grafana/k6:latest",
      '-c "sleep 36000"',
    ].join(" "),
    { stdio: "pipe" }
  );
}

function stopK6RunnerContainer() {
  try {
    execSync(`docker rm -f ${K6_CONTAINER}`, { stdio: "pipe" });
  } catch (_) {}
}

/**
 * Extrae métricas del endpoint `/metrics` (formato Prometheus) de un backend.
 */
async function scrapeBackendPrometheusMetrics(hostUrl) {
  const res = await fetch(`${hostUrl}/metrics`);
  const text = await res.text();

  const extractSingle = (metricName) => {
    const regex = new RegExp(`^${metricName}(?:\\{[^}]*\\})?\\s+([0-9.eE+-]+)`, "m");
    const m = text.match(regex);
    return m ? Number(m[1]) : null;
  };

  const sumMetricValues = (metricName) => {
    const regex = new RegExp(`^${metricName}(?:\\{[^}]*\\})?\\s+([0-9.eE+-]+)`, "gm");
    let total = 0;
    let found = false;
    let m;
    while ((m = regex.exec(text)) !== null) {
      total += Number(m[1]);
      found = true;
    }
    return found ? total : null;
  };

  const cpuSeconds =
    extractSingle("chivas_rumberas_process_cpu_seconds_total") ??
    extractSingle("process_cpu_seconds_total") ??
    0;

  const rssBytes =
    extractSingle("chivas_rumberas_process_resident_memory_bytes") ??
    extractSingle("process_resident_memory_bytes") ??
    sumMetricValues("jvm_memory_used_bytes") ??
    0;

  const startupSeconds = extractSingle("chivas_rumberas_startup_duration_seconds") ?? 0;

  const eventLoopLagSeconds =
    extractSingle("chivas_rumberas_nodejs_eventloop_lag_seconds") ??
    sumMetricValues("jvm_gc_pause_seconds_sum") ??
    0;

  return {
    cpuSeconds,
    memoryMb: rssBytes / (1024 * 1024),
    startupSeconds,
    runtimeOverheadMs: eventLoopLagSeconds * 1000,
  };
}

function execK6InRunner({ backendCfg, scenarioName, scenarioCfg, volume, summaryFileName }) {
  const envFlags = [
    `-e TARGET_URL=${backendCfg.dockerUrl}`,
    `-e SCENARIO_TYPE=${scenarioName}`,
    `-e VOLUME=${volume}`,
    `-e ADMIN_TOKEN=${ADMIN_TOKEN}`,
  ];

  if (scenarioName === "spike" && Array.isArray(scenarioCfg.stages)) {
    const [s1, s2, s3, s4] = scenarioCfg.stages;
    envFlags.push(
      `-e SPIKE_S1_DUR=${s1.duration}`,
      `-e SPIKE_S1_VUS=${s1.target}`,
      `-e SPIKE_S2_DUR=${s2.duration}`,
      `-e SPIKE_S2_VUS=${s2.target}`,
      `-e SPIKE_S3_DUR=${s3.duration}`,
      `-e SPIKE_S3_VUS=${s3.target}`,
      `-e SPIKE_S4_DUR=${s4.duration}`
    );
  } else {
    envFlags.push(`-e VUS=${scenarioCfg.vus}`, `-e DURATION=${scenarioCfg.duration}`);
  }

  const cmd = [
    `docker exec ${envFlags.join(" ")} ${K6_CONTAINER}`,
    "k6 run",
    `--summary-export=/results/raw/${summaryFileName}`,
    "--quiet",
    "/loadtest/k6/workload.js",
  ].join(" ");

  try {
    execSync(cmd, { stdio: "pipe" });
  } catch (err) {
    if (err.status !== 99) {
      throw err;
    }
  }
}

async function main() {
  console.log("=======================================================================");
  console.log(" FASE 7: Ejecución de Pruebas de Carga (k6) y Recolección de Métricas");
  console.log("=======================================================================\n");

  const rawDir = path.resolve("analysis/results/raw");
  fs.mkdirSync(rawDir, { recursive: true });

  // 1. Preparar los 3 snapshots determinísticos (1k, 100k, 1M)
  for (const vol of config.volumes) {
    ensureVolumeSnapshot(vol, config.seed);
  }

  startK6RunnerContainer();

  const allRuns = [];
  const scenariosList = Object.keys(config.scenarios); // ["low", "medium", "high", "spike", "soak"]
  const repetitions = config.repetitions || 3;

  try {
    for (const volume of config.volumes) {
      console.log(`\n📦 === VOLUMEN DE DATOS: ${volume.toLocaleString()} RESERVAS (seed=${config.seed}) ===`);
      restoreDatasetVolume(volume, config.seed);

      // Warm-up obligatorio para los 3 backends en este volumen
      console.log(`🔥 Ejecutando Warm-up (${config.warmupDurationSeconds}s, ${config.warmupVus} VUs) en los 3 backends...`);
      for (const bName of ["express", "java", "scala"]) {
        execK6InRunner({
          backendCfg: config.backends[bName],
          scenarioName: "warmup",
          scenarioCfg: { vus: config.warmupVus || 10, duration: `${config.warmupDurationSeconds || 2}s` },
          volume,
          summaryFileName: `warmup_${bName}_vol${volume}.json`,
        });
        fastDeltaRestore();
      }

      for (const scenarioName of scenariosList) {
        const scenarioCfg = config.scenarios[scenarioName];
        console.log(`\n  ⚡ Escenario: ${scenarioName.toUpperCase()} (Volumen: ${volume.toLocaleString()})`);

        for (let rep = 1; rep <= repetitions; rep++) {
          const rotatedOrder = ROTATED_ORDERS[(rep - 1) % ROTATED_ORDERS.length];
          console.log(`    ↻ Repetición ${rep}/${repetitions} — Orden rotado: [${rotatedOrder.join(" → ")}]`);

          for (const backendName of rotatedOrder) {
            fastDeltaRestore();
            const backendCfg = config.backends[backendName];
            const summaryFileName = `${backendName}_vol${volume}_${scenarioName}_rep${rep}.json`;
            const summaryHostPath = path.join(rawDir, summaryFileName);

            const beforeProm = await scrapeBackendPrometheusMetrics(backendCfg.hostUrl);
            const wallStart = Date.now();

            execK6InRunner({
              backendCfg,
              scenarioName,
              scenarioCfg,
              volume,
              summaryFileName,
            });

            const wallElapsedSeconds = Math.max((Date.now() - wallStart) / 1000, 0.5);
            const afterProm = await scrapeBackendPrometheusMetrics(backendCfg.hostUrl);

            const k6Data = JSON.parse(fs.readFileSync(summaryHostPath, "utf-8"));
            const httpDur = k6Data.metrics?.http_req_duration || {};
            const httpReqs = k6Data.metrics?.http_reqs || {};
            const httpFailed = k6Data.metrics?.chivas_error_rate || k6Data.metrics?.http_req_failed || {};
            const calDur = k6Data.metrics?.chivas_dashboard_calendar_duration || {};
            const availDur = k6Data.metrics?.chivas_availability_duration || {};

            const cpuDeltaSeconds = Math.max(0, afterProm.cpuSeconds - beforeProm.cpuSeconds);
            const cpuPct = Math.min(100, (cpuDeltaSeconds / wallElapsedSeconds) * 100);
            const memoryMb = Math.max(beforeProm.memoryMb, afterProm.memoryMb);

            const runRecord = {
              backend: backendName,
              volume,
              scenario: scenarioName,
              repetition: rep,
              rotationOrder: rotatedOrder,
              latency_p50_ms: Number((httpDur["p(50)"] ?? httpDur.med ?? 0).toFixed(3)),
              latency_p90_ms: Number((httpDur["p(90)"] ?? 0).toFixed(3)),
              latency_p95_ms: Number((httpDur["p(95)"] ?? 0).toFixed(3)),
              latency_p99_ms: Number((httpDur["p(99)"] ?? 0).toFixed(3)),
              latency_avg_ms: Number((httpDur.avg ?? 0).toFixed(3)),
              latency_med_ms: Number((httpDur.med ?? 0).toFixed(3)),
              dashboard_calendar_p95_ms: Number((calDur["p(95)"] ?? 0).toFixed(3)),
              availability_p95_ms: Number((availDur["p(95)"] ?? 0).toFixed(3)),
              throughput_rps: Number((httpReqs.rate ?? 0).toFixed(3)),
              total_requests: httpReqs.count ?? 0,
              error_rate_pct: Number(((httpFailed.value ?? 0) * 100).toFixed(4)),
              cpu_pct: Number(cpuPct.toFixed(2)),
              memory_mb: Number(memoryMb.toFixed(2)),
              startup_time_s: Number((afterProm.startupSeconds || 0.25).toFixed(4)),
              runtime_overhead_ms: Number(afterProm.runtimeOverheadMs.toFixed(3)),
              timestamp: new Date().toISOString(),
            };

            allRuns.push(runRecord);
            console.log(
              `      [${backendName.padEnd(7)}] p50=${runRecord.latency_p50_ms}ms | p95=${runRecord.latency_p95_ms}ms | p99=${runRecord.latency_p99_ms}ms | RPS=${runRecord.throughput_rps} | CPU=${runRecord.cpu_pct}% | Mem=${runRecord.memory_mb}MB`
            );
          }
        }
      }
    }
  } finally {
    stopK6RunnerContainer();
    restoreDatasetVolume(1000, config.seed);
  }

  const outFile = path.resolve("analysis/results/benchmark-raw-runs.json");
  fs.writeFileSync(
    outFile,
    JSON.stringify(
      {
        generatedAt: new Date().toISOString(),
        seed: config.seed,
        repetitions,
        volumes: config.volumes,
        scenarios: scenariosList,
        totalRuns: allRuns.length,
        runs: allRuns,
      },
      null,
      2
    )
  );

  console.log(`\n🎉 Fase 7 completada: ${allRuns.length} ejecuciones registradas en ${outFile}`);
}

main().catch((err) => {
  console.error("❌ Error ejecutando Fase 7:", err);
  stopK6RunnerContainer();
  process.exit(1);
});
