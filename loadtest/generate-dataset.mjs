/**
 * Fase 6: Generador y Restaurador Reproducible de Datasets (1.000, 100.000 y 1.000.000 reservas)
 *
 * Características:
 * - Semilla fija (`seed` leída de `loadtest/config/scenarios.json`, por defecto 20261115).
 * - Genera exactamente N reservas (`1000`, `100000` o `1000000`) de forma determinística
 *   y reproducible sobre la réplica PostgreSQL en Docker (`chivas-postgres-replica`),
 *   sin tocar jamás la base de datos original.
 * - Mantiene tablas snapshot pre-materializadas en la réplica (`_snapshot_bookings_<N>`)
 *   para que la restauración previa a cada escenario (`--restore`) tome pocos segundos.
 *
 * Uso:
 *   node loadtest/generate-dataset.mjs --volume 1000 --restore
 *   node loadtest/generate-dataset.mjs --volume 100000 --restore
 *   node loadtest/generate-dataset.mjs --volume 1000000 --restore
 *   node loadtest/generate-dataset.mjs --prepare-all
 */
import { execSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const configPath = path.resolve("loadtest/config/scenarios.json");
const scenariosConfig = JSON.parse(fs.readFileSync(configPath, "utf-8"));
const DEFAULT_SEED = scenariosConfig.seed || 20261115;
const PG_CONTAINER = process.env.PG_CONTAINER || "chivas-postgres-replica";
const PG_USER = process.env.POSTGRES_USER || "chivas";
const PG_DB = process.env.POSTGRES_DB || "chivas_rumberas";

function runPsql(sql) {
  return execSync(
    `docker exec -i ${PG_CONTAINER} psql -v ON_ERROR_STOP=1 -U ${PG_USER} -d ${PG_DB}`,
    { input: sql, encoding: "utf-8", stdio: ["pipe", "pipe", "pipe"] }
  );
}

/**
 * Construye o verifica el snapshot determinístico para un volumen dado (1000, 100000, 1000000)
 * usando aritmética pseudoaleatoria determinística con semilla fija `seed`.
 */
export function ensureVolumeSnapshot(volume, seed = DEFAULT_SEED) {
  const vol = Number(volume);
  if (![1000, 100000, 1000000].includes(vol)) {
    throw new Error(`Volumen no soportado: ${volume}. Use 1000, 100000 o 1000000.`);
  }

  const snapshotTable = `_snapshot_bookings_${vol}`;
  const existsOut = runPsql(`SELECT to_regclass('public.${snapshotTable}') IS NOT NULL AS exists_flag;`);
  const tableExists = /\bt\b/.test(existsOut);

  let existingCount = -1;
  if (tableExists) {
    const checkOut = runPsql(`SELECT COUNT(*) FROM "${snapshotTable}";`);
    const match = checkOut.match(/\n\s*(\d+)\s*\n/);
    existingCount = match ? Number(match[1]) : -1;
  }

  if (existingCount === vol) {
    console.log(`✔ Snapshot determinístico ${snapshotTable} (${vol.toLocaleString()} filas, seed=${seed}) ya está listo.`);
    return;
  }

  console.log(`⚙ Generando snapshot determinístico ${snapshotTable} (${vol.toLocaleString()} reservas, seed=${seed})...`);
  const t0 = Date.now();

  // Primero aseguramos el catálogo base (usuarios, chivas, travels, clientes semilla)
  execSync(
    `docker exec -i ${PG_CONTAINER} psql -v ON_ERROR_STOP=1 -U ${PG_USER} -d ${PG_DB} -f /opt/reset-db.sql`,
    { stdio: "pipe" }
  );

  // Generación determinística por lotes/vectorizada en PostgreSQL a partir de la semilla fija `seed`:
  // - Las primeras 3 filas son CR-SEED01, CR-SEED02, CR-SEED03 (para preservar compatibilidad con pruebas base)
  // - Las siguientes (vol - 3) filas se generan con hash determinístico md5(seed || ':' || gs)
  //   distribuyendo chivas, recorridos, clientes, estados y fechas futuras (>= 2026-11-18)
  //   para que las consultas de calendario mensual y disponibilidad operen sobre datos activos reales.
  const buildSnapshotSql = `
    DROP TABLE IF EXISTS "${snapshotTable}";

    CREATE UNLOGGED TABLE "${snapshotTable}" AS
    SELECT * FROM "bookings" WHERE 1 = 0;

    -- Insertar las 3 reservas semilla base
    INSERT INTO "${snapshotTable}"
    SELECT * FROM "bookings" WHERE "id" IN ('CR-SEED01', 'CR-SEED02', 'CR-SEED03');

    -- Insertar las ${vol - 3} reservas sintéticas determinísticas con semilla = ${seed}
    INSERT INTO "${snapshotTable}" (
      "id", "chivaId", "travelId", "customerId", "customerName", "customerEmail", "customerPhone",
      "guestCount", "eventDate", "startTime", "durationHours", "totalPrice", "status",
      "notes", "googleEventId", "calendarSyncStatus", "createdAt", "updatedAt"
    )
    SELECT
      'CR-' || LPAD(UPPER(TO_HEX(gs)), 6, '0') AS "id",
      (ARRAY['la-coqueta-1954', 'la-perla-1959', 'la-parrandera-1962', 'el-arriero-1957'])[1 + ((gs + ${seed}) % 4)] AS "chivaId",
      (ARRAY['trv_alumbrados', 'trv_miradores', 'trv_sabaneta'])[1 + ((gs * 3 + ${seed}) % 3)] AS "travelId",
      (ARRAY[
        'cust-0001-0000-0000-000000000001',
        'cust-0002-0000-0000-000000000002',
        'cust-0003-0000-0000-000000000003'
      ])[1 + ((gs + ${seed}) % 3)] AS "customerId",
      'Cliente Sintético ' || gs AS "customerName",
      'cliente' || gs || '@benchmark.chivas.co' AS "customerEmail",
      '300' || LPAD(((1000000 + ((gs * 7919 + ${seed}) % 8999999)))::text, 7, '0') AS "customerPhone",
      15 + ((gs * 13 + ${seed}) % 25) AS "guestCount",
      (TIMESTAMP '2026-11-18 00:00:00' + (((gs * 17 + ${seed}) % 7300) || ' days')::interval) AS "eventDate",
      LPAD((8 + ((gs * 7 + ${seed}) % 12))::text, 2, '0') || ':00' AS "startTime",
      (3 + ((gs + ${seed}) % 2))::double precision AS "durationHours",
      ((280000 + ((gs % 4) * 20000)) * (3 + ((gs + ${seed}) % 2)))::double precision AS "totalPrice",
      (ARRAY[
        'confirmed'::"BookingStatus",
        'pending'::"BookingStatus",
        'completed'::"BookingStatus",
        'cancelled'::"BookingStatus"
      ])[1 + ((gs * 11 + ${seed}) % 4)] AS "status",
      'Dataset sintético reproducible seed=${seed} #' || gs AS "notes",
      CASE WHEN ((gs * 11 + ${seed}) % 4) = 0 THEN 'gcal-seed-' || gs ELSE NULL END AS "googleEventId",
      CASE WHEN ((gs * 11 + ${seed}) % 4) = 0 THEN 'synced'::"CalendarSyncStatus" ELSE 'pending'::"CalendarSyncStatus" END AS "calendarSyncStatus",
      (TIMESTAMP '2026-10-01 00:00:00' + ((gs % 3888000) || ' seconds')::interval) AS "createdAt",
      (TIMESTAMP '2026-10-01 00:00:00' + ((gs % 3888000) || ' seconds')::interval) AS "updatedAt"
    FROM generate_series(1::bigint, ${vol - 3}::bigint) AS gs;
  `;

  runPsql(buildSnapshotSql);
  const elapsed = ((Date.now() - t0) / 1000).toFixed(2);
  console.log(`✅ Snapshot ${snapshotTable} creado en ${elapsed}s.`);
}

/**
 * Restaura la tabla "bookings" y el catálogo base exactamente al estado inicial del volumen indicado.
 */
export function restoreDatasetVolume(volume, seed = DEFAULT_SEED) {
  const vol = Number(volume);
  ensureVolumeSnapshot(vol, seed);

  console.log(`🔄 Restaurando base de datos al estado inicial del volumen ${vol.toLocaleString()} (seed=${seed})...`);
  const t0 = Date.now();

  execSync(
    `docker exec -i ${PG_CONTAINER} psql -v ON_ERROR_STOP=1 -U ${PG_USER} -d ${PG_DB} -f /opt/reset-db.sql`,
    { stdio: "pipe" }
  );

  const restoreSql = `
    BEGIN;
    TRUNCATE TABLE "bookings";
    INSERT INTO "bookings" SELECT * FROM "_snapshot_bookings_${vol}";
    COMMIT;
    ANALYZE "bookings";
  `;
  runPsql(restoreSql);

  const countOut = runPsql(`SELECT COUNT(*) FROM "bookings";`);
  const match = countOut.match(/\n\s*(\d+)\s*\n/);
  const actualCount = match ? Number(match[1]) : 0;
  const elapsed = ((Date.now() - t0) / 1000).toFixed(2);

  console.log(`✅ Base de datos restaurada con exactamente ${actualCount.toLocaleString()} reservas en ${elapsed}s.`);
  return actualCount;
}

// CLI Execution
const isMain = process.argv[1] && path.resolve(process.argv[1]) === path.resolve("loadtest/generate-dataset.mjs");
if (isMain) {
  const args = process.argv.slice(2);
  const prepareAll = args.includes("--prepare-all");
  const doRestore = args.includes("--restore");
  const volIdx = args.indexOf("--volume");
  const seedIdx = args.indexOf("--seed");

  const seed = seedIdx !== -1 ? Number(args[seedIdx + 1]) : DEFAULT_SEED;

  if (prepareAll) {
    for (const v of [1000, 100000, 1000000]) {
      ensureVolumeSnapshot(v, seed);
    }
    restoreDatasetVolume(1000, seed);
  } else if (volIdx !== -1) {
    const vol = Number(args[volIdx + 1]);
    if (doRestore) {
      restoreDatasetVolume(vol, seed);
    } else {
      ensureVolumeSnapshot(vol, seed);
    }
  } else {
    console.log("Uso: node loadtest/generate-dataset.mjs [--prepare-all | --volume <1000|100000|1000000> [--restore]] [--seed <num>]");
  }
}
