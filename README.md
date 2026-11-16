# Chivas Rumberas: comparación Express vs Java vs Scala

Proyecto final de Paradigmas. Se implementa el mismo backend de reservas de chivas en tres lenguajes y se comparan con mediciones reproducibles.

| Directorio | Contenido |
| :-- | :-- |
| `backend-express/` | Backend original (Node.js/TypeScript). Único cambio: se montó `/metrics` en `interface/app.ts`. |
| `backend-java/` | Spring Boot 3 + Java 21 |
| `backend-scala/` | Scala 3 + sbt + http4s |
| `chivas-rumberas-frontend/` | Frontend Next.js |
| `infra/` | PostgreSQL réplica, Prometheus, Grafana (provisionado), cAdvisor, Dockerfiles |
| `loadtest/` | Generador de datos con semilla fija, escenarios k6, pruebas de contrato |
| `analysis/` | Consolidación, ranking automático y resultados |
| `report/` | **[Informe técnico](report/informe-tecnico.md)** |
| `docs/contract/` | OpenAPI, modelo de datos e inventario de endpoints |

## Resultado

Ranking cuantitativo con los pesos del prompt: **Java 89.33**, Scala 84.00, Express 45.30. Ranking cualitativo (aparte): Scala 85.23, Express 72.68, Java 69.75. El informe incluye las limitaciones de la medición (sección 7), en especial que los escenarios de carga son cortos.

## Uso

```powershell
docker compose up -d                              # PostgreSQL réplica + 3 backends + frontend + Prometheus + Grafana + cAdvisor
node loadtest/run-contract-all.mjs                # pruebas de contrato y equivalencia entre los 3 backends
node loadtest/generate-dataset.mjs --prepare-all  # datasets de 1k / 100k / 1M (semilla 20261115)
node loadtest/run-all-benchmarks.mjs              # matriz k6 completa
python analysis/rank_backends.py                  # ranking automático sin intervención
```

- Frontend: http://localhost:3000. Express: 3001. Java: 3002. Scala: 3003.
- Grafana: http://localhost:3004 (`admin`/`admin`). Prometheus: http://localhost:9090.
- Configuración editable: `.env` (límites de recursos, puertos, modo mock), `analysis/config/weights.json` (pesos), `loadtest/config/scenarios.json` (escenarios, duraciones, repeticiones).

## Notas

- La base de datos original (Neon) no se usó ni se modificó. Todo corre sobre una réplica en un volumen Docker propio.
- `docker compose up` construye imágenes desde los JAR ya empaquetados de Java y Scala (`mvn package`, `backend-scala\sbt.cmd assembly`).
- Las imágenes están nombradas para Docker Hub (`emagarciar/*`), pero **no se han subido**.
