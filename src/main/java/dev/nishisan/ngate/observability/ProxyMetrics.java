/*
 * Copyright (C) 2026 Lucas Nishimura <lucas.nishimura@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.nishisan.ngate.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Instrumentação centralizada do hot path do n-gate via Micrometer.
 * <p>
 * Expõe métricas de requests inbound (por listener/status), upstream (por backend/status)
 * e erros — todas disponíveis via {@code /actuator/prometheus}.
 * <p>
 * Thread-safe: usa {@link ConcurrentHashMap} para cache de meters e
 * {@link MeterRegistry} para registro thread-safe.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-09
 */
@Component
public class ProxyMetrics {

    private final MeterRegistry registry;

    // Cache de Timers e Counters para evitar lookup repetido no registry
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();

    public ProxyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * @return o MeterRegistry global (útil para registrar métricas custom fora desta classe)
     */
    public MeterRegistry getRegistry() {
        return registry;
    }

    // ─── Inbound Request Metrics ─────────────────────────────────────────

    /**
     * Registra a conclusão de um request inbound no listener.
     *
     * @param listener  nome do listener (ex: "http-noauth")
     * @param method    método HTTP (GET, POST, etc.)
     * @param status    código de status HTTP da resposta
     * @param durationMs duração total do request em milissegundos
     */
    public void recordInboundRequest(String listener, String method, int status, long durationMs) {
        // Counter: total de requests
        String counterKey = "inbound:" + listener + ":" + method + ":" + status;
        counterCache.computeIfAbsent(counterKey, k ->
                Counter.builder("ngate.requests.total")
                        .description("Total inbound requests processed")
                        .tag("listener", listener)
                        .tag("method", method)
                        .tag("status", String.valueOf(status))
                        .register(registry)
        ).increment();

        // Timer: duração do request
        String timerKey = "inbound:" + listener + ":" + method;
        timerCache.computeIfAbsent(timerKey, k ->
                Timer.builder("ngate.request.duration")
                        .description("Inbound request duration")
                        .tag("listener", listener)
                        .tag("method", method)
                        .register(registry)
        ).record(Duration.ofMillis(durationMs));
    }

    /**
     * Registra um erro no processamento inbound.
     */
    public void recordInboundError(String listener, String method) {
        String key = "inbound-error:" + listener + ":" + method;
        counterCache.computeIfAbsent(key, k ->
                Counter.builder("ngate.request.errors")
                        .description("Total inbound request errors")
                        .tag("listener", listener)
                        .tag("method", method)
                        .register(registry)
        ).increment();
    }

    // ─── Upstream Request Metrics ────────────────────────────────────────

    /**
     * Registra a conclusão de um request upstream para o backend.
     *
     * @param backend    nome do backend
     * @param method     método HTTP
     * @param status     código de status HTTP do upstream
     * @param durationMs duração do upstream call em milissegundos
     */
    public void recordUpstreamRequest(String backend, String method, int status, long durationMs) {
        // Counter
        String counterKey = "upstream:" + backend + ":" + method + ":" + status;
        counterCache.computeIfAbsent(counterKey, k ->
                Counter.builder("ngate.upstream.requests")
                        .description("Total upstream requests to backends")
                        .tag("backend", backend)
                        .tag("method", method)
                        .tag("status", String.valueOf(status))
                        .register(registry)
        ).increment();

        // Timer
        String timerKey = "upstream:" + backend + ":" + method;
        timerCache.computeIfAbsent(timerKey, k ->
                Timer.builder("ngate.upstream.duration")
                        .description("Upstream request duration")
                        .tag("backend", backend)
                        .tag("method", method)
                        .register(registry)
        ).record(Duration.ofMillis(durationMs));
    }

    /**
     * Registra um erro de upstream (timeout, connection refused, etc.).
     */
    public void recordUpstreamError(String backend, String method) {
        String key = "upstream-error:" + backend + ":" + method;
        counterCache.computeIfAbsent(key, k ->
                Counter.builder("ngate.upstream.errors")
                        .description("Total upstream request errors")
                        .tag("backend", backend)
                        .tag("method", method)
                        .register(registry)
        ).increment();
    }

    // ─── Rate Limit Metrics ─────────────────────────────────────────────

    /**
     * Registra um evento de rate limiting.
     *
     * @param scope  escopo da avaliação (ex: "listener", "route", "backend")
     * @param zone   nome da zona de rate limiting
     * @param result resultado: "ALLOWED", "REJECTED" ou "DELAYED"
     */
    public void recordRateLimitEvent(String scope, String zone, String result) {
        String key = "ratelimit:" + scope + ":" + zone + ":" + result;
        counterCache.computeIfAbsent(key, k ->
                Counter.builder("ngate.ratelimit.total")
                        .description("Total rate limiting events")
                        .tag("scope", scope)
                        .tag("zone", zone)
                        .tag("result", result)
                        .register(registry)
        ).increment();
    }
}

