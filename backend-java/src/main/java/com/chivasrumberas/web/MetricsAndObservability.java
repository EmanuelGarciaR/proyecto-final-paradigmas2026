package com.chivasrumberas.web;

import com.chivasrumberas.ChivasRumberasApplication;
import com.chivasrumberas.web.ApiDtos.HealthResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Configuration
public class MetricsAndObservability {

    private final AtomicReference<Double> startupDurationSeconds = new AtomicReference<>(0.0);

    @Bean
    public PrometheusMeterRegistry prometheusMeterRegistry() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new JvmMemoryMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry);

        Gauge.builder("chivas_rumberas_startup_duration_seconds", startupDurationSeconds, AtomicReference::get)
                .description("Tiempo de arranque del backend en segundos.")
                .register(registry);

        Gauge.builder("chivas_rumberas_process_resident_memory_bytes", Runtime.getRuntime(),
                        rt -> (double) (rt.totalMemory() - rt.freeMemory()))
                .description("Memoria residente/usada por el proceso en bytes.")
                .register(registry);

        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean osBean) {
            FunctionCounter.builder("chivas_rumberas_process_cpu_seconds", osBean,
                            bean -> bean.getProcessCpuTime() / 1_000_000_000.0)
                    .description("Tiempo total de CPU consumido por el proceso en segundos.")
                    .register(registry);
        }

        return registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        double elapsed = (System.nanoTime() - ChivasRumberasApplication.PROCESS_START_NANOS) / 1_000_000_000.0;
        startupDurationSeconds.set(elapsed);
    }

    @Component
    public static class HttpMetricsFilter extends OncePerRequestFilter {
        private final PrometheusMeterRegistry registry;

        public HttpMetricsFilter(PrometheusMeterRegistry registry) {
            this.registry = registry;
        }

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain
        ) throws ServletException, IOException {
            long startNanos = System.nanoTime();
            try {
                filterChain.doFilter(request, response);
            } finally {
                long durationNanos = System.nanoTime() - startNanos;
                String method = request.getMethod();
                String route = resolveRoutePattern(request);
                String statusCode = String.valueOf(response.getStatus());

                Counter.builder("chivas_rumberas_http_requests")
                        .description("Total de solicitudes HTTP recibidas.")
                        .tag("method", method)
                        .tag("route", route)
                        .tag("status_code", statusCode)
                        .register(registry)
                        .increment();

                Timer.builder("chivas_rumberas_http_request_duration")
                        .description("Duración de las solicitudes HTTP en segundos.")
                        .tag("method", method)
                        .tag("route", route)
                        .serviceLevelObjectives(
                                Duration.ofMillis(5),
                                Duration.ofMillis(10),
                                Duration.ofMillis(50),
                                Duration.ofMillis(100),
                                Duration.ofMillis(500),
                                Duration.ofSeconds(1),
                                Duration.ofSeconds(2),
                                Duration.ofSeconds(5)
                        )
                        .register(registry)
                        .record(durationNanos, TimeUnit.NANOSECONDS);
            }
        }

        private static String resolveRoutePattern(HttpServletRequest request) {
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (pattern instanceof String s && !s.isBlank()) {
                return s.replace("{id}", ":id");
            }
            return request.getRequestURI();
        }
    }

    @RestController
    public static class ObservabilityController {
        private final PrometheusMeterRegistry registry;

        public ObservabilityController(PrometheusMeterRegistry registry) {
            this.registry = registry;
        }

        @GetMapping("/health")
        public HealthResponse health() {
            return new HealthResponse("ok", Instant.now().toString());
        }

        @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
        public ResponseEntity<String> metrics() {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/plain; version=0.0.4; charset=utf-8"))
                    .body(registry.scrape());
        }
    }
}
