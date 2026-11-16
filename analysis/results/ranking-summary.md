# Consolidación y Ranking Automático de Backends (Fase 8)

- **Fecha de generación**: `2026-11-16T05:15:38.470147+00:00`
- **Total de ejecuciones consolidadas**: `135` (3 volúmenes × 5 escenarios × 3 backends × 3 repeticiones rotadas)
- **Backend Ganador Cuantitativo**: **`JAVA`** (89.33 / 100 pts)

## 1. Clasificación Cuantitativa Global (Pesos Configurados)

| Posición | Backend | Puntaje Total | Latencia p50 (ms) | Latencia p95 (ms) [30%] | Latencia p99 (ms) | Throughput (req/s) [25%] | Memoria (MB) [15%] | CPU (%) [10%] | Arranque (s) [10%] | Error (%) [10%] |
| :-: | :--- | :-: | --: | --: | --: | --: | --: | --: | --: | --: |
| **#1** | **JAVA** | **89.33** | 6.12 | 112.86 *(+30.00)* | 136.67 | 345.59 *(+25.00)* | 91.22 *(+13.51)* | 46.73% *(+10.00)* | 12.585s *(+0.82)* | 0.0000% *(+10.00)* |
| **#2** | **SCALA** | **84.00** | 8.43 | 121.50 *(+27.87)* | 157.62 | 302.42 *(+21.88)* | 82.18 *(+15.00)* | 64.54% *(+7.24)* | 5.162s *(+2.01)* | 0.0000% *(+10.00)* |
| **#3** | **EXPRESS** | **45.30** | 100.90 | 632.20 *(+5.36)* | 717.60 | 136.56 *(+9.88)* | 311.37 *(+3.96)* | 76.65% *(+6.10)* | 1.038s *(+10.00)* | 0.0000% *(+10.00)* |

### Justificación por Posición

- Posición #1 con 89.33/100 pts: Latencia p95=112.86ms (+30.00 pts), Throughput=345.59 req/s (+25.00 pts), Memoria=91.22 MB (+13.51 pts), CPU=46.73% (+10.00 pts), Arranque=12.585s (+0.82 pts), Tasa de error=0.0000% (+10.00 pts).
- Posición #2 con 84.00/100 pts: Latencia p95=121.50ms (+27.87 pts), Throughput=302.42 req/s (+21.88 pts), Memoria=82.18 MB (+15.00 pts), CPU=64.54% (+7.24 pts), Arranque=5.162s (+2.01 pts), Tasa de error=0.0000% (+10.00 pts).
- Posición #3 con 45.30/100 pts: Latencia p95=632.20ms (+5.36 pts), Throughput=136.56 req/s (+9.88 pts), Memoria=311.37 MB (+3.96 pts), CPU=76.65% (+6.10 pts), Arranque=1.038s (+10.00 pts), Tasa de error=0.0000% (+10.00 pts).

## 2. Clasificación Cuantitativa Desglosada por Volumen de Datos

### Volumen: 1,000 reservas

| Posición | Backend | Puntaje | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (req/s) | Memoria (MB) | CPU (%) |
| :-: | :--- | :-: | --: | --: | --: | --: | --: | --: |
| #1 | **JAVA** | **86.57** | 6.89 | 133.70 | 201.58 | 367.13 | 135.57 | 46.72% |
| #2 | **SCALA** | **85.81** | 13.64 | 142.94 | 202.47 | 331.16 | 97.20 | 57.07% |
| #3 | **EXPRESS** | **53.52** | 65.94 | 405.48 | 531.33 | 183.04 | 322.28 | 70.21% |

### Volumen: 100,000 reservas

| Posición | Backend | Puntaje | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (req/s) | Memoria (MB) | CPU (%) |
| :-: | :--- | :-: | --: | --: | --: | --: | --: | --: |
| #1 | **SCALA** | **87.99** | 20.25 | 147.66 | 204.36 | 312.45 | 73.54 | 58.21% |
| #2 | **JAVA** | **87.16** | 9.61 | 151.21 | 256.52 | 341.00 | 91.60 | 46.96% |
| #3 | **EXPRESS** | **49.70** | 107.24 | 447.72 | 698.51 | 134.24 | 312.53 | 72.88% |

### Volumen: 1,000,000 reservas

| Posición | Backend | Puntaje | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (req/s) | Memoria (MB) | CPU (%) |
| :-: | :--- | :-: | --: | --: | --: | --: | --: | --: |
| #1 | **JAVA** | **90.82** | 22.40 | 243.81 | 321.68 | 278.39 | 77.93 | 52.64% |
| #2 | **SCALA** | **75.05** | 47.50 | 332.45 | 431.89 | 217.87 | 86.86 | 65.71% |
| #3 | **EXPRESS** | **42.33** | 329.51 | 1134.53 | 1576.70 | 60.64 | 291.51 | 82.05% |

## 3. Evaluación Cualitativa (Reportada por Separado)

> **Regla explícita**: `Puntaje Cualitativo = 0.25 * (min_SLOC / SLOC) + 0.25 * (min_CC_density / CC_density) + 0.30 * (Cov / max_Cov) + 0.20 * (FP_density / max_FP_density)`

| Posición | Backend | Puntaje Cualitativo | SLOC | Archivos | Complejidad (CC/100 SLOC) | Cobertura (%) | Herramienta Cobertura | Densidad FP (FP/100 SLOC) |
| :-: | :--- | :-: | --: | :-: | --: | --: | :--- | --: |
| **#1** | **SCALA** | **93.11** | 2053 | 8 | 12.47 | 54.58% | sbt-scoverage 2.2.1 (scoverage.xml) | 6.09 |
| **#2** | **JAVA** | **78.45** | 2436 | 10 | 11.58 | 60.20% | JaCoCo 0.8.12 (jacoco.xml) | 1.48 |
| **#3** | **EXPRESS** | **71.78** | 3366 | 79 | 10.43 | 58.40% | node:test (42 assertions across 11 suites) | 0.74 |
