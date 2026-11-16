# Informe Técnico: Comparación de Backends para Chivas Rumberas
**Express (Node.js/TypeScript) vs Java (Spring Boot) vs Scala 3 (http4s)**

> Todas las cifras de este informe provienen de [analysis/results/final-ranking.json](../analysis/results/final-ranking.json), [consolidated-scenarios.csv](../analysis/results/consolidated-scenarios.csv) y [qualitative-metrics.json](../analysis/results/qualitative-metrics.json), generados por scripts versionados. Se pueden regenerar con `python analysis/rank_backends.py`.

---

## 1. Resumen ejecutivo

| | Java | Scala | Express |
| :-- | :-: | :-: | :-: |
| **Puntaje cuantitativo** (pesos del prompt) | **89.33** (#1) | 84.00 (#2) | 45.30 (#3) |
| **Puntaje cualitativo** (reportado aparte) | 69.75 (#3) | **85.23** (#1) | 72.68 (#2) |

- **Backend seleccionado por los criterios definidos (cuantitativos): Java (Spring Boot).**
- Scala queda muy cerca de Java (5.3 puntos) y gana en el criterio cualitativo. En el volumen de 100.000 reservas supera a Java en puntaje cuantitativo (87.99 frente a 87.16).
- Express es claramente el más lento bajo carga y el que más memoria consume en este entorno, con 1 CPU y 512 MB por contenedor. Es el más rápido en arrancar.

---

## 2. Metodología

### 2.1 Condiciones equivalentes
- Los tres backends corren en Docker con los mismos límites: `cpus: 1.0`, `mem_limit: 512m` (editables en `.env`).
- Los tres usan la misma instancia PostgreSQL 16, una réplica en volumen Docker propio. **La base de datos original (Neon) no se tocó.**
- Las integraciones externas (Google Calendar y correo) están simuladas con **latencia fija de 50 ms** (`INTEGRATIONS_MODE=mock`, `MOCK_LATENCY_MS=50`). Para Express se inyectan con un script de preload externo (`infra/docker/express/mock-integrations.cjs`), sin modificar su código.
- Datasets sintéticos de **1.000, 100.000 y 1.000.000** de reservas, con **semilla fija `20261115`** (`loadtest/generate-dataset.mjs`). Cada ejecución restaura el dataset a su estado inicial.
- **Equivalencia funcional verificada**: `loadtest/run-contract-all.mjs` compara las respuestas de los tres backends con `deepStrictEqual` en 18 comprobaciones (calendario, disponibilidad, paginación, seguimiento, errores, etc.). Todas coinciden. La verificación halló una diferencia real de zona horaria y formato de fechas en Java y Scala, que se corrigió.

### 2.2 Carga
- Herramienta: **k6**. Escenarios: `low`, `medium`, `high`, `spike`, `soak`.
- Volúmenes: 3. Repeticiones: **3 por escenario** con **orden rotado** entre backends. Total: **135 ejecuciones** más 9 de warm-up.
- Mezcla de tráfico: 30 % calendario del dashboard, 20 % listado paginado, 20 % disponibilidad, 20 % catálogo público y 10 % creación de reservas.
- Percentiles reportados: p50, p90, p95, p99. El ranking usa la **mediana** de las 3 repeticiones. La desviación estándar y el IQR se guardan en `consolidated-scenarios.csv`.

### 2.3 Fórmula del ranking cuantitativo
Cada métrica se normaliza a 0–100 frente al mejor backend y se pondera con `analysis/config/weights.json`:

| Métrica | Peso | Normalización |
| :-- | :-: | :-- |
| Latencia p95 | 30 % | `100 · min / valor` |
| Throughput | 25 % | `100 · valor / max` |
| Memoria | 15 % | `100 · min / valor` |
| CPU | 10 % | `100 · min / valor` |
| Tiempo de arranque | 10 % | `100 · min / valor` |
| Tasa de errores | 10 % | `100 · (1 − error)` |

---

## 3. Resultados cuantitativos

### 3.1 Clasificación global (mediana sobre todos los escenarios y volúmenes)

| # | Backend | Puntaje | p50 (ms) | p95 (ms) | p99 (ms) | Throughput (req/s) | Memoria (MB) | CPU (%) | Arranque (s) | Error (%) |
| :-: | :-- | :-: | --: | --: | --: | --: | --: | --: | --: | --: |
| 1 | **Java** | **89.33** | 6.12 | 112.86 | 136.67 | 345.59 | 91.22 | 46.73 | 12.585 | 0.00 |
| 2 | **Scala** | 84.00 | 8.43 | 121.50 | 157.62 | 302.42 | 82.18 | 64.54 | 5.162 | 0.00 |
| 3 | **Express** | 45.30 | 100.90 | 632.20 | 717.60 | 136.56 | 311.37 | 76.65 | 1.038 | 0.00 |

**Qué justifica cada posición**
- **Java (#1)**: gana latencia p95 (30/30 pts), throughput (25/25) y CPU (10/10). Pierde casi todos los puntos de arranque (0.82/10), que es su punto débil.
- **Scala (#2)**: usa la menor memoria (15/15 pts) y queda cerca de Java en latencia y throughput. Su CPU es más alta (7.24/10).
- **Express (#3)**: es el más rápido en arrancar (10/10), pero su latencia p95 es unas 5.6 veces la de Java, su throughput es menos de la mitad y su memoria es unas 3.4 veces la de Java.

### 3.2 Por volumen de datos

| Volumen | #1 | #2 | #3 |
| :-- | :-- | :-- | :-- |
| 1.000 | Java 86.57 | Scala 85.81 | Express 53.52 |
| 100.000 | **Scala 87.99** | Java 87.16 | Express 49.70 |
| 1.000.000 | Java 90.82 | Scala 75.05 | Express 42.33 |

A 1.000.000 de reservas Express se degrada mucho: p95 de 1134 ms frente a 244 ms de Java, y throughput de 60.6 frente a 278.4 req/s. Los valores completos de cada escenario están en `analysis/results/ranking-summary.md`.

---

## 4. Resultados cualitativos (reportados por separado)

Regla explícita: `0.25·(min SLOC/SLOC) + 0.25·(min CC/CC) + 0.30·(Cobertura/max Cobertura) + 0.20·(FP/max FP)`.

| # | Backend | Puntaje | SLOC | Archivos | CC/100 SLOC | Cobertura de líneas | Operadores funcionales/100 SLOC |
| :-: | :-- | :-: | --: | --: | --: | --: | --: |
| 1 | **Scala** | **85.23** | 2053 | 8 | 12.47 | 54.58 % (scoverage) | 6.09 |
| 2 | **Express** | 72.68 | 3366 | 79 | 10.43 | 84.77 % (node:test)* | 0.74 |
| 3 | **Java** | 69.75 | 2436 | 10 | 11.58 | 60.20 % (JaCoCo) | 1.48 |

\* Ver limitación L3. La cobertura de Express no es estrictamente comparable con las otras dos.

Scala es el más conciso y el más funcional (6.09 operadores `map`/`filter`/`groupBy`/`foldLeft`/`sortBy` por cada 100 líneas). Su complejidad por línea es algo mayor, porque el código es más denso.

---

## 5. Programación funcional aplicada

La lógica del dashboard está implementada con colecciones inmutables y funciones de orden superior en los tres backends, con resultados idénticos. Ninguno usa endpoints nuevos.

- `GET /bookings/calendar`:
  - `filter` para quedarse con las reservas confirmadas del mes.
  - `groupBy`/`groupingBy` en dos niveles (día y chiva).
  - `map` para proyectar cada reserva a un slot y calcular `endsNextDay`.
  - `sortBy`/`sorted` para ordenar los slots.
- `GET /bookings/availability`: `map` para proyectar los slots ocupados y `reduce`/`foldLeft` para acumular los minutos reservados dentro de la franja 08:00–22:00.
- **Java**: `Stream`, `Predicate`, `Function`, `Collectors.groupingBy`.
- **Scala**: funciones de orden superior (`Booking => Boolean`), `groupBy`, `foldLeft` y `@tailrec`.
- **Express**: `filter`/`map`/`sort`, pero con bucles `for` y `Map` mutables para agrupar. Es el menos funcional de los tres.

---

## 6. Ventajas y limitaciones de cada lenguaje

### Java (Spring Boot)
- **Ventajas**: mejor latencia p95 y mayor throughput globales. Se mantiene estable al subir el volumen. Ecosistema maduro, con JaCoCo y Micrometer.
- **Limitaciones**: arranque lento (12.6 s medidos). Más verboso que Scala (2436 SLOC frente a 2053). La programación funcional con `Stream` es más ceremoniosa.

### Scala 3 (http4s)
- **Ventajas**: menor memoria, menos código, la mayor expresividad funcional y rendimiento cercano a Java. En 100.000 reservas fue el primero.
- **Limitaciones**: mayor consumo de CPU, arranque de 5.2 s y tiempos de compilación largos (sbt). El ecosistema de herramientas es más pequeño. Hubo que adaptar el empaquetado del fat-JAR.

### Express (Node.js/TypeScript)
- **Ventajas**: arranque casi inmediato (1.0 s) y la mayor cobertura de pruebas medida. Es el más simple de desarrollar y desplegar.
- **Limitaciones**: con 1 CPU, el único hilo de ejecución se satura. El rendimiento cae mucho con cargas altas y volúmenes grandes, y consume mucha más memoria. La estructura es más dispersa (79 archivos).

---

## 7. Limitaciones y amenazas a la validez

Estas cifras describen **este entorno concreto** y no deben extrapolarse sin cuidado.

- **L1. Duraciones de prueba muy cortas.** Los escenarios duran 2–3 s (el prompt pide un soak real). Se usaron para que los 135 runs cupieran en tiempo razonable. El warm-up de 2 s puede no bastar para que la JVM llegue a su régimen estable. Los resultados de `soak` **no representan resistencia real**. Con duraciones de varios minutos los resultados podrían cambiar, y se pueden ajustar en `loadtest/config/scenarios.json`.
- **L2. Medición de memoria y CPU no homogénea.** La memoria viene del endpoint `/metrics` de cada backend: RSS en Express y `jvm_memory_used` en Java y Scala. No son la misma magnitud. La CPU se calcula como delta de tiempo de CPU del proceso entre dos lecturas. cAdvisor está desplegado y Prometheus lo scrapea, pero **no se usó para el ranking**. Usar `container_memory_working_set_bytes` sería más justo.
- **L3. Cobertura de Express sobrestimada.** El runner de Node solo reporta archivos que las pruebas cargan. JaCoCo y scoverage analizan todas las clases. En una corrección previa había un valor fijo en el código (58.4 %). Se reemplazó por la medición real, y eso cambió el orden cualitativo (Java pasó del puesto 2 al 3).
- **L4. Tiempo de arranque.** Java 12.6 s y Scala 5.2 s son medianas de la métrica que cada proceso reporta sobre sí mismo (desde el inicio del proceso hasta que el servidor escucha). Pueden inflarse por el límite de 1 CPU. Se pondera al 10 %.
- **L5. Una sola máquina.** El generador de carga, la base de datos y los tres backends comparten el mismo hardware. Los backends se ejecutaron en serie, con orden rotado, para reducir sesgos de orden, pero no se eliminan.
- **L6. Complejidad ciclomática aproximada.** Se estima contando puntos de decisión con expresiones regulares, no con una herramienta de análisis sintáctico. Sirve para comparar, pero no equivale a una medición con herramienta dedicada.
- **L7. Tasa de error de 0 %.** Una primera ejecución dio ~42 % de errores porque el login de `setup()` agotaba el rate limit de `/auth/login`. Se corrigió firmando el token fuera de k6. La métrica de error del ranking cuenta checks fallidos, y 409 en la creación de reservas se considera respuesta esperada.
- **L8. Dashboards de Grafana.** Se provisionan automáticamente, pero no se verificó visualmente que todos los paneles muestren datos. Algunas métricas (por ejemplo `chivas_rumberas_nodejs_eventloop_lag_seconds`) dependen de los nombres que exponga cada runtime.

---

## 8. Conclusión

Con los pesos definidos en el prompt, **Java (Spring Boot) es el backend seleccionado** con 89.33 puntos, por su latencia p95, su throughput y su comportamiento estable con 1.000.000 de reservas. **Scala es una alternativa muy cercana** (84.00): usa menos memoria, es mucho más conciso y expresivo, y fue el primero en 100.000 reservas. **Express queda en tercer lugar** (45.30) por rendimiento y memoria, aunque arranca más rápido.

La diferencia entre Java y Scala es pequeña frente a las limitaciones L1 y L2. Con pruebas más largas y mediciones de recursos más homogéneas podría invertirse. La distancia de ambos frente a Express es amplia y probablemente se mantenga.

---

## 9. Reproducción

```powershell
docker compose up -d                                  # infraestructura completa
node loadtest/run-contract-all.mjs                    # equivalencia funcional (3 backends)
node loadtest/generate-dataset.mjs --prepare-all      # datasets con semilla fija
node loadtest/run-all-benchmarks.mjs                  # 135 ejecuciones k6
node analysis/collect-qualitative-metrics.mjs         # métricas cualitativas
python analysis/rank_backends.py                      # ranking automático
```
