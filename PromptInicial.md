# Rol
Actúa como un ingeniero arquitecto de software y un DevOps experimentado.

# Contexto
Tengo un proyecto de booking de chivas hecho con Express (backend) y Next.js (frontend). Incluye dashboard para el administrador, envío de correos y uso de la API de Google Calendar. El backend Express ya está implementado, funcional y ya expone `/metrics` con `prom-client`.

Necesito implementar el mismo backend en dos lenguajes más y compararlos objetivamente:
- **Java** con **Spring Boot**
- **Scala base** (Scala 3 + sbt, con una librería HTTP ligera como http4s; sin frameworks pesados)

# Reglas de trabajo
1. Trabaja **por fases**. Al terminar cada una, resume qué hiciste, cómo verificarlo y **espera mi confirmación** antes de pasar a la siguiente.
2. **No inventes endpoints**. Solo existen los que ya están en el backend Express. Analiza su código y extrae el contrato real.
3. **No modifiques nada** dentro de `/backend-express`.
4. Si algo es ambiguo, pregunta antes de asumir.
5. Mantén la configuración (pesos, escenarios, límites de recursos) en archivos editables, no hardcodeada.
6. Ir haciendo commits pequeños y hacerles push. Podemos trabajar en el main, no hay problema

# Estructura mínima del repositorio
/backend-express
/backend-java
/backend-scala
/infra          (Prometheus, Grafana, provisioning, dashboards)
/loadtest       (escenarios, generador de datos, scripts)
/analysis       (consolidación, ranking, resultados)
/report         (informe técnico)
docker-compose.yml

# Requisitos (aplican a los tres backends)
1. Mismos requisitos funcionales y resultados equivalentes en las tres implementaciones.
2. Uso real de programación funcional: funciones de orden superior, lambdas, `map`, `filter`, `groupBy`, `reduce/fold`, etc. Aplícalo en la lógica del dashboard del administrador (por ejemplo: reservas por día, ingresos por chiva, ocupación, agrupación por estado). Los tres backends deben implementar la misma lógica con resultados idénticos.
3. Escenarios de prueba para distintos volúmenes y cargas.
4. Mecanismos para medir y registrar el comportamiento de cada solución.
5. Pruebas bajo condiciones equivalentes.
6. Resultados consolidados en una estructura comparable.
7. Mecanismo automático que determine el mejor backend.
8. Clasificación con las métricas que justifican cada posición.
9. Conclusión técnica con ventajas y limitaciones.

Las métricas cuantitativas se obtienen con instrumentos de medición; los criterios cualitativos con reglas explícitas y verificables.

# Datos y base de datos
- La base de datos original (postgresql) **no se debe tocar**. Detecta el motor que usa el proyecto y crea una **réplica en un volumen Docker propio** que usarán los tres backends (misma instancia lógica de datos, mismo esquema).
- Genera datasets sintéticos de **1.000, 100.000 y 1.000.000 de reservas**, con **semilla fija** para que sean reproducibles. El generador debe ser un script versionado en `/loadtest`.
- Cada escenario debe poder restaurar el dataset a su estado inicial antes de ejecutarse.

# Observabilidad
- Usa **Prometheus + Grafana** (más cAdvisor para CPU/memoria por contenedor). No uses Istio.
- Cada backend expone `/metrics` en formato Prometheus: Micrometer en Java y Scala, `prom-client` en Express (ya implementado). Usa nombres de métricas homologados entre los tres para que un mismo dashboard sirva para todos.
- Grafana provisionado automáticamente (datasources y dashboards como código) con paneles comparativos de: latencia p50/p95/p99, throughput, tasa de errores, CPU, memoria, tiempo de arranque y GC/event loop cuando aplique.

# Pruebas de carga (condiciones equivalentes)
- Herramienta: **k6**.
- Escenarios: carga baja, media, alta, pico (spike) y resistencia (soak), ejecutados contra cada volumen de datos (1k, 100k, 1M).
- Mismos límites de recursos en Docker para los tres backends (`cpus`, `mem_limit`), mismo hardware, misma base de datos, mismo orden de ejecución aleatorizado o rotado.
- **Warm-up** antes de medir (la JVM lo necesita) y **mínimo 3 repeticiones** por escenario; reporta mediana y dispersión.
- Reporta percentiles (p50/p95/p99), no solo promedios.
- Las integraciones externas (Google Calendar y correo) deben ser **mock/stub con latencia fija simulada** durante las pruebas de carga, para que la red no contamine la comparación. Debe existir un modo "real" para pruebas funcionales.

# Ranking automático
- Script en `/analysis` (Python o similar) que corre **sin intervención**: lee los resultados de Prometheus/k6, consolida en una estructura comparable (CSV/JSON), calcula el puntaje y genera la clasificación.
- Pesos por defecto, editables en un archivo de configuración:
  - Latencia p95: 30 %
  - Throughput: 25 %
  - Memoria: 15 %
  - CPU: 10 %
  - Tiempo de arranque: 10 %
  - Tasa de errores: 10 %
- Criterios cualitativos medidos con reglas verificables y herramientas (líneas de código con cloc, complejidad ciclomática, cobertura de pruebas con JaCoCo/scoverage, etc.), reportados por separado de la puntuación cuantitativa, con la regla de cálculo explícita.
- La clasificación debe mostrar, por cada posición, las métricas que la justifican.

# Entregables
1. Tres backends funcionales con código fuente (Express intacto).
2. Escenarios de prueba y datos utilizados (generador con semilla).
3. Evidencias de rendimiento y consumo de recursos en Grafana/Prometheus.
4. Dockerfiles y `docker-compose.yml` para: frontend, backend Express, backend Java, backend Scala, volumen de datos (más Prometheus, Grafana, cAdvisor).
5. Mecanismo de consolidación, comparación y clasificación automática.
6. **Informe técnico** en `/report` que explique los resultados, el ranking y justifique el backend seleccionado según los criterios definidos, con ventajas y limitaciones de cada lenguaje.

# Fases
1. Analizar Express y producir el contrato (OpenAPI), el modelo de datos y el inventario de endpoints reales.
2. Infra base: volumen con réplica de la BD, docker-compose, Prometheus, Grafana, cAdvisor.
3. Backend Java (Spring Boot) + pruebas de contrato.
4. Backend Scala + pruebas de contrato.
5. Pruebas de contrato ejecutadas contra los tres backends y corrección de diferencias.
6. Generador de datos (1k / 100k / 1M) y escenarios k6.
7. Ejecución de pruebas y recolección de métricas.
8. Consolidación, ranking automático y dashboards comparativos.
9. Informe técnico final.

# Criterios de aceptación
- `docker compose up` levanta todo.
- Las pruebas de contrato pasan en los tres backends.
- El script de ranking corre sin intervención y produce la clasificación.
- Express no tiene ningún cambio en su código.
- La base de datos original no fue modificada.

# Tener en cuenta

- User de docker hub para subir el compose:

emagarciar