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
import { execSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { restoreDatasetVolume } from "./generate-dataset.mjs";

const configPath = path.resolve("loadtest/config/scenarios.json");

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

const isMain =
  process.argv[1] &&
  path.resolve(process.argv[1]) === path.resolve("loadtest/run-k6-scenario.mjs");

if (isMain) {
  const args = process.argv.slice(2);
  const getArg = (flag, def) => {
    const idx = args.indexOf(flag);
    return idx !== -1 ? args[idx + 1] : def;
  };

  const backendName = getArg("--backend", "express");
  const volume = Number(getArg("--volume", "1000"));
  const scenarioName = getArg("--scenario", "low");
  const rep = Number(getArg("--rep", "1"));

  console.log(`🚀 Ejecutando escenario k6 '${scenarioName}' (vol=${volume}, backend=${backendName}, rep=${rep})...`);
  const summary = runK6Scenario({ backendName, volume, scenarioName, repetition: rep });
  const dur = summary.metrics?.http_req_duration || {};
  const reqs = summary.metrics?.http_reqs || {};
  console.log(
    `✅ Finalizado: reqs=${reqs.count} (${(reqs.rate || 0).toFixed(1)} req/s) | p50=${(dur["p(50)"] || 0).toFixed(2)}ms | p95=${(dur["p(95)"] || 0).toFixed(2)}ms | p99=${(dur["p(99)"] || 0).toFixed(2)}ms`
  );
}
