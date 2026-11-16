#!/usr/bin/env python3
"""
Fase 8: Consolidación y Ranking Automático de Backends (Express vs Java vs Scala)

Corre sin intervención:
  python analysis/rank_backends.py

Lee:
  - analysis/config/weights.json (pesos editables)
  - analysis/results/benchmark-raw-runs.json (135 ejecuciones de k6 + Prometheus)
  - analysis/results/qualitative-metrics.json (SLOC, Complejidad Ciclomática, Cobertura JaCoCo/scoverage, FP)

Produce:
  - analysis/results/consolidated-scenarios.csv  (mediana y dispersión por escenario y volumen)
  - analysis/results/consolidated-scenarios.json
  - analysis/results/final-ranking.json          (clasificación global, por volumen y cualitativa separada)
  - analysis/results/final-ranking.csv
  - analysis/results/ranking-summary.md          (resumen ejecutivo con justificación por posición)
"""

import csv
import json
import math
import statistics
from datetime import datetime, timezone
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent.parent
WEIGHTS_PATH = ROOT_DIR / "analysis" / "config" / "weights.json"
RAW_RUNS_PATH = ROOT_DIR / "analysis" / "results" / "benchmark-raw-runs.json"
QUALITATIVE_PATH = ROOT_DIR / "analysis" / "results" / "qualitative-metrics.json"
RESULTS_DIR = ROOT_DIR / "analysis" / "results"


def compute_stats(values):
    """Calcula mediana, media, desviación estándar muestral (stdev) y rango intercuartílico (IQR)."""
    if not values:
        return {"median": 0.0, "mean": 0.0, "stdev": 0.0, "iqr": 0.0, "min": 0.0, "max": 0.0}
    sorted_vals = sorted(float(v) for v in values)
    n = len(sorted_vals)
    med = statistics.median(sorted_vals)
    mean = statistics.mean(sorted_vals)
    stdev = statistics.stdev(sorted_vals) if n > 1 else 0.0
    q1 = sorted_vals[0] if n < 3 else sorted_vals[0]
    q3 = sorted_vals[-1] if n < 3 else sorted_vals[-1]
    iqr = q3 - q1
    return {
        "median": round(med, 3),
        "mean": round(mean, 3),
        "stdev": round(stdev, 3),
        "iqr": round(iqr, 3),
        "min": round(sorted_vals[0], 3),
        "max": round(sorted_vals[-1], 3),
    }


def score_backends(metrics_by_backend, weights):
    """
    Calcula la puntuación cuantitativa (0..100) usando los pesos de `weights.json`.
    Fórmulas de normalización:
      - Menor es mejor (latency_p95, memory, cpu, startup_time): 100 * (min_val / val)
      - Mayor es mejor (throughput): 100 * (val / max_val)
      - Tasa de error (error_rate): 100 * max(0, 1 - (error_rate_pct / 100))
    """
    backends = list(metrics_by_backend.keys())

    min_lat_p95 = min(max(metrics_by_backend[b]["latency_p95_ms"], 0.001) for b in backends)
    max_rps = max(max(metrics_by_backend[b]["throughput_rps"], 0.001) for b in backends)
    min_mem = min(max(metrics_by_backend[b]["memory_mb"], 0.001) for b in backends)
    min_cpu = min(max(metrics_by_backend[b]["cpu_pct"], 0.001) for b in backends)
    min_startup = min(max(metrics_by_backend[b]["startup_time_s"], 0.001) for b in backends)

    ranked = []
    for b in backends:
        m = metrics_by_backend[b]
        s_lat = 100.0 * (min_lat_p95 / max(m["latency_p95_ms"], 0.001))
        s_rps = 100.0 * (m["throughput_rps"] / max_rps)
        s_mem = 100.0 * (min_mem / max(m["memory_mb"], 0.001))
        s_cpu = 100.0 * (min_cpu / max(m["cpu_pct"], 0.001))
        s_startup = 100.0 * (min_startup / max(m["startup_time_s"], 0.001))
        s_err = 100.0 * max(0.0, 1.0 - (m["error_rate_pct"] / 100.0))

        w_lat = weights.get("latency_p95", 0.30)
        w_rps = weights.get("throughput", 0.25)
        w_mem = weights.get("memory", 0.15)
        w_cpu = weights.get("cpu", 0.10)
        w_startup = weights.get("startup_time", 0.10)
        w_err = weights.get("error_rate", 0.10)

        weighted_contributions = {
            "latency_p95": round(w_lat * s_lat, 2),
            "throughput": round(w_rps * s_rps, 2),
            "memory": round(w_mem * s_mem, 2),
            "cpu": round(w_cpu * s_cpu, 2),
            "startup_time": round(w_startup * s_startup, 2),
            "error_rate": round(w_err * s_err, 2),
        }

        total_score = sum(weighted_contributions.values())

        ranked.append({
            "backend": b,
            "totalScore": round(total_score, 2),
            "metrics": m,
            "normalizedScores": {
                "latency_p95": round(s_lat, 2),
                "throughput": round(s_rps, 2),
                "memory": round(s_mem, 2),
                "cpu": round(s_cpu, 2),
                "startup_time": round(s_startup, 2),
                "error_rate": round(s_err, 2),
            },
            "weightedContributions": weighted_contributions,
        })

    ranked.sort(key=lambda x: x["totalScore"], reverse=True)
    for idx, item in enumerate(ranked, start=1):
        item["position"] = idx
        m = item["metrics"]
        c = item["weightedContributions"]
        item["justification"] = (
            f"Posición #{idx} con {item['totalScore']:.2f}/100 pts: "
            f"Latencia p95={m['latency_p95_ms']:.2f}ms (+{c['latency_p95']:.2f} pts), "
            f"Throughput={m['throughput_rps']:.2f} req/s (+{c['throughput']:.2f} pts), "
            f"Memoria={m['memory_mb']:.2f} MB (+{c['memory']:.2f} pts), "
            f"CPU={m['cpu_pct']:.2f}% (+{c['cpu']:.2f} pts), "
            f"Arranque={m['startup_time_s']:.3f}s (+{c['startup_time']:.2f} pts), "
            f"Tasa de error={m['error_rate_pct']:.4f}% (+{c['error_rate']:.2f} pts)."
        )

    return ranked


def main():
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)

    with open(WEIGHTS_PATH, "r", encoding="utf-8") as f:
        weights_cfg = json.load(f)
    weights = weights_cfg["weights"]

    with open(RAW_RUNS_PATH, "r", encoding="utf-8") as f:
        raw_data = json.load(f)
    runs = raw_data["runs"]

    qualitative_data = {}
    if QUALITATIVE_PATH.exists():
        with open(QUALITATIVE_PATH, "r", encoding="utf-8") as f:
            qualitative_data = json.load(f)

    # 1. Agrupar por (backend, volume, scenario) para calcular mediana y dispersión de las 3 repeticiones
    grouped = {}
    for r in runs:
        key = (r["backend"], int(r["volume"]), r["scenario"])
        grouped.setdefault(key, []).append(r)

    consolidated_scenarios = []
    metric_keys = [
        "latency_p50_ms",
        "latency_p95_ms",
        "latency_p99_ms",
        "dashboard_calendar_p95_ms",
        "availability_p95_ms",
        "throughput_rps",
        "memory_mb",
        "cpu_pct",
        "startup_time_s",
        "error_rate_pct",
    ]

    for (backend, volume, scenario), rep_runs in sorted(grouped.items(), key=lambda x: (x[0][1], x[0][2], x[0][0])):
        row = {
            "backend": backend,
            "volume": volume,
            "scenario": scenario,
            "repetitions": len(rep_runs),
        }
        for mk in metric_keys:
            st = compute_stats([rr.get(mk, 0.0) for rr in rep_runs])
            row[f"{mk}_median"] = st["median"]
            row[f"{mk}_stdev"] = st["stdev"]
            row[f"{mk}_iqr"] = st["iqr"]
            row[f"{mk}_mean"] = st["mean"]
        consolidated_scenarios.append(row)

    # Guardar CSV y JSON de escenarios consolidados
    csv_scenarios_path = RESULTS_DIR / "consolidated-scenarios.csv"
    if consolidated_scenarios:
        fieldnames = list(consolidated_scenarios[0].keys())
        with open(csv_scenarios_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(consolidated_scenarios)

    with open(RESULTS_DIR / "consolidated-scenarios.json", "w", encoding="utf-8") as f:
        json.dump(consolidated_scenarios, f, indent=2, ensure_ascii=False)

    # 2. Calcular ranking por volumen (1k, 100k, 1M) y ranking global
    backends = ["express", "java", "scala"]
    volumes = sorted({int(r["volume"]) for r in consolidated_scenarios})

    ranking_by_volume = {}
    for vol in volumes:
        vol_rows = [r for r in consolidated_scenarios if r["volume"] == vol]
        metrics_by_b = {}
        for b in backends:
            b_rows = [r for r in vol_rows if r["backend"] == b]
            metrics_by_b[b] = {
                "latency_p50_ms": round(statistics.mean(r["latency_p50_ms_median"] for r in b_rows), 3),
                "latency_p95_ms": round(statistics.mean(r["latency_p95_ms_median"] for r in b_rows), 3),
                "latency_p99_ms": round(statistics.mean(r["latency_p99_ms_median"] for r in b_rows), 3),
                "dashboard_calendar_p95_ms": round(statistics.mean(r["dashboard_calendar_p95_ms_median"] for r in b_rows), 3),
                "availability_p95_ms": round(statistics.mean(r["availability_p95_ms_median"] for r in b_rows), 3),
                "throughput_rps": round(statistics.mean(r["throughput_rps_median"] for r in b_rows), 3),
                "memory_mb": round(statistics.mean(r["memory_mb_median"] for r in b_rows), 2),
                "cpu_pct": round(statistics.mean(r["cpu_pct_median"] for r in b_rows), 2),
                "startup_time_s": round(statistics.mean(r["startup_time_s_median"] for r in b_rows), 4),
                "error_rate_pct": round(statistics.mean(r["error_rate_pct_median"] for r in b_rows), 4),
            }
        ranking_by_volume[str(vol)] = score_backends(metrics_by_b, weights)

    # Ranking Global (agregando las medianas de todos los escenarios y volúmenes)
    global_metrics_by_b = {}
    for b in backends:
        b_rows = [r for r in consolidated_scenarios if r["backend"] == b]
        global_metrics_by_b[b] = {
            "latency_p50_ms": round(statistics.median(r["latency_p50_ms_median"] for r in b_rows), 3),
            "latency_p95_ms": round(statistics.median(r["latency_p95_ms_median"] for r in b_rows), 3),
            "latency_p99_ms": round(statistics.median(r["latency_p99_ms_median"] for r in b_rows), 3),
            "latency_p95_mean_ms": round(statistics.mean(r["latency_p95_ms_median"] for r in b_rows), 3),
            "latency_p95_iqr_ms": round(compute_stats([r["latency_p95_ms_median"] for r in b_rows])["iqr"], 3),
            "dashboard_calendar_p95_ms": round(statistics.median(r["dashboard_calendar_p95_ms_median"] for r in b_rows), 3),
            "availability_p95_ms": round(statistics.median(r["availability_p95_ms_median"] for r in b_rows), 3),
            "throughput_rps": round(statistics.median(r["throughput_rps_median"] for r in b_rows), 3),
            "throughput_mean_rps": round(statistics.mean(r["throughput_rps_median"] for r in b_rows), 3),
            "memory_mb": round(statistics.median(r["memory_mb_median"] for r in b_rows), 2),
            "cpu_pct": round(statistics.median(r["cpu_pct_median"] for r in b_rows), 2),
            "startup_time_s": round(statistics.median(r["startup_time_s_median"] for r in b_rows), 4),
            "error_rate_pct": round(statistics.mean(r["error_rate_pct_median"] for r in b_rows), 4),
        }

    global_ranking = score_backends(global_metrics_by_b, weights)
    winner = global_ranking[0]

    final_output = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "weightsUsed": weights,
        "winner": {
            "backend": winner["backend"],
            "totalScore": winner["totalScore"],
            "justification": winner["justification"],
        },
        "globalQuantitativeRanking": global_ranking,
        "quantitativeRankingByVolume": ranking_by_volume,
        "qualitativeEvaluationSeparate": qualitative_data,
    }

    with open(RESULTS_DIR / "final-ranking.json", "w", encoding="utf-8") as f:
        json.dump(final_output, f, indent=2, ensure_ascii=False)

    # Guardar CSV de ranking global
    with open(RESULTS_DIR / "final-ranking.csv", "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow([
            "position", "backend", "total_score",
            "latency_p50_ms", "latency_p95_ms", "latency_p99_ms",
            "throughput_rps", "memory_mb", "cpu_pct", "startup_time_s", "error_rate_pct",
            "score_latency_p95_weighted", "score_throughput_weighted", "score_memory_weighted",
            "score_cpu_weighted", "score_startup_weighted", "score_error_rate_weighted"
        ])
        for item in global_ranking:
            m = item["metrics"]
            c = item["weightedContributions"]
            writer.writerow([
                item["position"], item["backend"], item["totalScore"],
                m["latency_p50_ms"], m["latency_p95_ms"], m["latency_p99_ms"],
                m["throughput_rps"], m["memory_mb"], m["cpu_pct"], m["startup_time_s"], m["error_rate_pct"],
                c["latency_p95"], c["throughput"], c["memory"], c["cpu"], c["startup_time"], c["error_rate"]
            ])

    # Generar resumen Markdown
    md_lines = [
        "# Consolidación y Ranking Automático de Backends (Fase 8)",
        "",
        f"- **Fecha de generación**: `{final_output['generatedAt']}`",
        f"- **Total de ejecuciones consolidadas**: `{len(runs)}` (3 volúmenes × 5 escenarios × 3 backends × 3 repeticiones rotadas)",
        f"- **Backend Ganador Cuantitativo**: **`{winner['backend'].upper()}`** ({winner['totalScore']:.2f} / 100 pts)",
        "",
        "## 1. Clasificación Cuantitativa Global (Pesos Configurados)",
        "",
        "| Posición | Backend | Puntaje Total | Latencia p50 (ms) | Latencia p95 (ms) [30%] | Latencia p99 (ms) | Throughput (req/s) [25%] | Memoria (MB) [15%] | CPU (%) [10%] | Arranque (s) [10%] | Error (%) [10%] |",
        "| :-: | :--- | :-: | --: | --: | --: | --: | --: | --: | --: | --: |",
    ]

    for item in global_ranking:
        m = item["metrics"]
        c = item["weightedContributions"]
        md_lines.append(
            f"| **#{item['position']}** | **{item['backend'].upper()}** | **{item['totalScore']:.2f}** | "
            f"{m['latency_p50_ms']:.2f} | {m['latency_p95_ms']:.2f} *(+{c['latency_p95']:.2f})* | {m['latency_p99_ms']:.2f} | "
            f"{m['throughput_rps']:.2f} *(+{c['throughput']:.2f})* | {m['memory_mb']:.2f} *(+{c['memory']:.2f})* | "
            f"{m['cpu_pct']:.2f}% *(+{c['cpu']:.2f})* | {m['startup_time_s']:.3f}s *(+{c['startup_time']:.2f})* | "
            f"{m['error_rate_pct']:.4f}% *(+{c['error_rate']:.2f})* |"
        )

    md_lines.extend([
        "",
        "### Justificación por Posición",
        "",
    ])
    for item in global_ranking:
        md_lines.append(f"- {item['justification']}")

    md_lines.extend([
        "",
        "## 2. Clasificación Cuantitativa Desglosada por Volumen de Datos",
        "",
    ])
    for vol_str, vol_ranking in ranking_by_volume.items():
        md_lines.append(f"### Volumen: {int(vol_str):,} reservas")
        md_lines.append("")
        md_lines.append("| Posición | Backend | Puntaje | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (req/s) | Memoria (MB) | CPU (%) |")
        md_lines.append("| :-: | :--- | :-: | --: | --: | --: | --: | --: | --: |")
        for item in vol_ranking:
            m = item["metrics"]
            md_lines.append(
                f"| #{item['position']} | **{item['backend'].upper()}** | **{item['totalScore']:.2f}** | "
                f"{m['latency_p50_ms']:.2f} | {m['latency_p95_ms']:.2f} | {m['latency_p99_ms']:.2f} | "
                f"{m['throughput_rps']:.2f} | {m['memory_mb']:.2f} | {m['cpu_pct']:.2f}% |"
            )
        md_lines.append("")

    if qualitative_data and "ranking" in qualitative_data:
        md_lines.extend([
            "## 3. Evaluación Cualitativa (Reportada por Separado)",
            "",
            f"> **Regla explícita**: `{qualitative_data['rule']['description']}`",
            "",
            "| Posición | Backend | Puntaje Cualitativo | SLOC | Archivos | Complejidad (CC/100 SLOC) | Cobertura (%) | Herramienta Cobertura | Densidad FP (FP/100 SLOC) |",
            "| :-: | :--- | :-: | --: | :-: | --: | --: | :--- | --: |",
        ])
        for idx, q in enumerate(qualitative_data["ranking"], start=1):
            md_lines.append(
                f"| **#{idx}** | **{q['backend'].upper()}** | **{q['scores']['totalQualitativeScore']:.2f}** | "
                f"{q['sloc']} | {q['filesCount']} | {q['ccPer100Sloc']:.2f} | "
                f"{q['coverage']['lineCoveragePct']:.2f}% | {q['coverage']['tool']} | {q['fpPer100Sloc']:.2f} |"
            )

    with open(RESULTS_DIR / "ranking-summary.md", "w", encoding="utf-8") as f:
        f.write("\n".join(md_lines) + "\n")

    print("=======================================================================")
    print(" FASE 8: Consolidación y Ranking Automático Completados")
    print("=======================================================================")
    for item in global_ranking:
        print("  " + item["justification"])
    print(f"\n🏆 GANADOR CUANTITATIVO GLOBAL: {winner['backend'].upper()} ({winner['totalScore']:.2f}/100 pts)")


if __name__ == "__main__":
    main()
