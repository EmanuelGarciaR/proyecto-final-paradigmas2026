/**
 * Fase 6: Ejecutor de un Escenario k6 Individual con Restauración Previa de Dataset
 *
 * Lee la configuración desde `loadtest/config/scenarios.json`, restaura el volumen
 * indicado (1000, 100000 o 1000000) con semilla fija y ejecuta `grafana/k6` contra
 * el backend objetivo (`express`, `java` o `scala`).
 *
 * Uso:
 *   node loadtest/run-k6-scenario.mjs --backend express --volume 1000 --scenario low
 */
import { exec, execSync } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { promisify } from "node:util";
import { restoreDatasetVolume } from "./generate-dataset.mjs";

const execAsync = promisify(exec);

const configPath = path.resolve("loadtest/config/scenarios.json");
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

export function runK6Scenario({
  backendName,
  volume,
  scenarioName,
  repetition = 1,
  skipRestore = false,
  outputSummaryPath,
}) {
  const config = JSON.parse(fs.readFileSync(configPath, "utf-8"));
  const backendCfg = config.backends[backendName];
  if (!backendCfg) {
    throw new Error(`Backend desconocido: ${backendName}`);
  }

  if (!skipRestore) {
    restoreDatasetVolume(volume, config.seed);
  }

  const isWarmup = scenarioName === "warmup";
  const scenarioCfg = isWarmup
    ? { vus: config.warmupVus || 8, duration: `${config.warmupDurationSeconds || 5}s` }
    : config.scenarios[scenarioName];

  if (!scenarioCfg) {
    throw new Error(`Escenario desconocido: ${scenarioName}`);
  }

  const outDir = path.resolve("analysis/results/raw");
  fs.mkdirSync(outDir, { recursive: true });
  const summaryFile =
    outputSummaryPath ||
    path.join(outDir, `${backendName}_vol${volume}_${scenarioName}_rep${repetition}.json`);

  const containerSummaryPath = `/results/raw/${path.basename(summaryFile)}`;
  const loadtestDir = path.resolve("loadtest").replace(/\\/g, "/");
  const resultsDir = path.resolve("analysis/results").replace(/\\/g, "/");

  const envFlags = [
    `-e TARGET_URL=${backendCfg.dockerUrl}`,
    `-e SCENARIO_TYPE=${scenarioName}`,
    `-e VOLUME=${volume}`,
    `-e ADMIN_TOKEN=${createAdminJwtToken()}`,
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
    "docker run --rm",
    "--network chivas_rumberas_net",
    `-v "${loadtestDir}:/loadtest:ro"`,
    `-v "${resultsDir}:/results"`,
    ...envFlags,
    "grafana/k6:latest run",
    `--summary-export=${containerSummaryPath}`,
    "--quiet",
    "/loadtest/k6/workload.js",
  ].join(" ");

  execSync(cmd, { stdio: "pipe" });
  return JSON.parse(fs.readFileSync(summaryFile, "utf-8"));
}

export async function runK6ScenarioParallelAll({ volume, scenarioName, repetition = 1 }) {
  const config = JSON.parse(fs.readFileSync(configPath, "utf-8"));
  restoreDatasetVolume(volume, config.seed);

  const isWarmup = scenarioName === "warmup";
  const scenarioCfg = isWarmup
    ? { vus: config.warmupVus || 8, duration: `${config.warmupDurationSeconds || 5}s` }
    : config.scenarios[scenarioName];

  if (!scenarioCfg) {
    throw new Error(`Escenario desconocido: ${scenarioName}`);
  }

  const outDir = path.resolve("analysis/results/raw");
  fs.mkdirSync(outDir, { recursive: true });
  const loadtestDir = path.resolve("loadtest").replace(/\\/g, "/");
  const resultsDir = path.resolve("analysis/results").replace(/\\/g, "/");
  const token = createAdminJwtToken();

  const backends = ["express", "java", "scala"];
  const tasks = backends.map(async (backendName) => {
    const backendCfg = config.backends[backendName];
    const summaryFile = path.join(
      outDir,
      `${backendName}_vol${volume}_${scenarioName}_rep${repetition}.json`
    );
    const containerSummaryPath = `/results/raw/${path.basename(summaryFile)}`;

    const envFlags = [
      `-e TARGET_URL=${backendCfg.dockerUrl}`,
      `-e SCENARIO_TYPE=${scenarioName}`,
      `-e VOLUME=${volume}`,
      `-e ADMIN_TOKEN=${token}`,
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
      "docker run --rm",
      "--network chivas_rumberas_net",
      `-v "${loadtestDir}:/loadtest:ro"`,
      `-v "${resultsDir}:/results"`,
      ...envFlags,
      "grafana/k6:latest run",
      `--summary-export=${containerSummaryPath}`,
      "--quiet",
      "/loadtest/k6/workload.js",
    ].join(" ");

    await execAsync(cmd);
    return {
      backendName,
      summary: JSON.parse(fs.readFileSync(summaryFile, "utf-8")),
    };
  });

  return Promise.all(tasks);
}

const isMain =
  process.argv[1] &&
  path.resolve(process.argv[1]) === path.resolve("loadtest/run-k6-scenario.mjs");

const SCENARIO_ALIASES = {
  s1_bajo: "low",
  bajo: "low",
  low: "low",
  s2_medio: "medium",
  medio: "medium",
  medium: "medium",
  s3_alto: "high",
  alto: "high",
  high: "high",
  s4_picos: "spike",
  picos: "spike",
  spike: "spike",
  s5_resistencia: "soak",
  resistencia: "soak",
  soak: "soak",
  warmup: "warmup",
};

if (isMain) {
  const args = process.argv.slice(2);
  const hasFlags = args.some((a) => a.startsWith("--"));
  const getArg = (flag, def) => {
    const idx = args.indexOf(flag);
    return idx !== -1 ? args[idx + 1] : def;
  };

  const rawBackend = hasFlags ? getArg("--backend", "express") : args[0] || "express";
  const rawScenario = hasFlags ? getArg("--scenario", "low") : args[1] || "low";
  const rawVolume = hasFlags ? getArg("--volume", "1000") : args[2] || "1000";
  const rawRep = hasFlags ? getArg("--rep", "1") : args[3] || "1";

  const backendName = rawBackend.toLowerCase();
  const scenarioName = SCENARIO_ALIASES[rawScenario.toLowerCase()] || rawScenario;
  const volume = Number(rawVolume);
  const rep = Number(rawRep);

  if (["all", "todos", "parallel", "simultaneo"].includes(backendName)) {
    console.log(
      `🚀 Ejecutando SIMULTÁNEAMENTE en paralelo (express + java + scala) el escenario '${scenarioName}' (vol=${volume}, rep=${rep})...`
    );
    runK6ScenarioParallelAll({ volume, scenarioName, repetition: rep })
      .then((results) => {
        for (const { backendName: bName, summary } of results) {
          const dur = summary.metrics?.http_req_duration || {};
          const reqs = summary.metrics?.http_reqs || {};
          console.log(
            `✅ [${bName.padEnd(7)}] reqs=${reqs.count} (${(reqs.rate || 0).toFixed(1)} req/s) | p50=${(dur["p(50)"] || 0).toFixed(2)}ms | p95=${(dur["p(95)"] || 0).toFixed(2)}ms | p99=${(dur["p(99)"] || 0).toFixed(2)}ms`
          );
        }
      })
      .catch((err) => {
        console.error("❌ Error en ejecución simultánea:", err);
        process.exit(1);
      });
  } else {
    console.log(`🚀 Ejecutando escenario k6 '${scenarioName}' (vol=${volume}, backend=${backendName}, rep=${rep})...`);
    const summary = runK6Scenario({ backendName, volume, scenarioName, repetition: rep });
    const dur = summary.metrics?.http_req_duration || {};
    const reqs = summary.metrics?.http_reqs || {};
    console.log(
      `✅ Finalizado: reqs=${reqs.count} (${(reqs.rate || 0).toFixed(1)} req/s) | p50=${(dur["p(50)"] || 0).toFixed(2)}ms | p95=${(dur["p(95)"] || 0).toFixed(2)}ms | p99=${(dur["p(99)"] || 0).toFixed(2)}ms`
    );
  }
}
