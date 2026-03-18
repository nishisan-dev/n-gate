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
package dev.nishisan.ishin.gateway.tunnel;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Instrumentação Prometheus do Tunnel Mode via Micrometer.
 * <p>
 * Expõe métricas de conexão, throughput, falha e saúde do pool
 * no endpoint {@code /actuator/prometheus}.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
@Component
public class TunnelMetrics {

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Counter> counterCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> timerCache = new ConcurrentHashMap<>();
    private final AtomicInteger activeListenerPorts = new AtomicInteger(0);
    private final Set<String> registeredPerBackendGauges = ConcurrentHashMap.newKeySet();

    public TunnelMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Gauge: total de listeners virtuais abertos
        Gauge.builder("ishin.tunnel.listener.ports.active", activeListenerPorts, AtomicInteger::get)
                .description("Total de listeners virtuais TCP abertos")
                .register(registry);
    }

    // ─── Connection Metrics ──────────────────────────────────────────────

    public void recordConnectionAccepted(int virtualPort, String backend) {
        String key = "conn:accept:" + virtualPort + ":" + backend;
        counterCache.computeIfAbsent(key, k ->
                Counter.builder("ishin.tunnel.connections.total")
                        .description("Total de conexões TCP aceitas")
                        .tag("virtual_port", String.valueOf(virtualPort))
                        .tag("backend", backend)
                        .register(registry)
        ).increment();
    }

    public void recordSessionDuration(int virtualPort, String backend, long durationMs) {
        String key = "session:" + virtualPort + ":" + backend;
        timerCache.computeIfAbsent(key, k ->
                Timer.builder("ishin.tunnel.session.duration.seconds")
                        .description("Duração da sessão TCP (accept → close)")
                        .tag("virtual_port", String.valueOf(virtualPort))
                        .tag("backend", backend)
                        .register(registry)
        ).record(Duration.ofMillis(durationMs));
    }

    public void recordConnectDuration(int virtualPort, String backend, long durationMs) {
        String key = "connect:" + virtualPort + ":" + backend;
        timerCache.computeIfAbsent(key, k ->
                Timer.builder("ishin.tunnel.connect.duration.seconds")
                        .description("Latência do handshake TCP com o backend")
                        .tag("virtual_port", String.valueOf(virtualPort))
                        .tag("backend", backend)
                        .register(registry)
        ).record(Duration.ofMillis(durationMs));
    }

    // ─── Throughput Metrics ──────────────────────────────────────────────

    public void recordBytesSent(int virtualPort, String backend, long bytes) {
        String key = "bytes:sent:" + virtualPort + ":" + backend;
        counterCache.computeIfAbsent(key, k ->
                Counter.builder("ishin.tunnel.bytes.sent.total")
                        .description("Bytes transferidos client → backend")
                        .tag("virtual_port", String.valueOf(virtualPort))
                        .tag("backend", backend)
                        .register(registry)
        ).increment(bytes);
    }

    public void recordBytesReceived(int virtualPort, String backend, long bytes) {
        String key = "bytes:recv:" + virtualPort + ":" + backend;
        counterCache.computeIfAbsent(key, k ->
                Counter.builder("ishin.tunnel.bytes.received.total")
                        .description("Bytes transferidos backend → client")
                        .tag("virtual_port", String.valueOf(virtualPort))
                        .tag("backend", backend)
                        .register(registry)
        ).increment(bytes);
    }

    // ─── Error Metrics ──────────────────────────────────────────────────

    public void recordConnectError(int virtualPort, String backend, String errorType) {
        String key = "err:" + virtualPort + ":" + backend + ":" + errorType;
        counterCache.computeIfAbsent(key, k ->
                Counter.builder("ishin.tunnel.connect.errors.total")
                        .description("Falhas de conexão com backend")
                        .tag("virtual_port", String.valueOf(virtualPort))
                        .tag("backend", backend)
                        .tag("error_type", errorType)
                        .register(registry)
        ).increment();
    }

    public void recordPoolRemoval(int virtualPort, String backend, String reason) {
        String key = "removal:" + virtualPort + ":" + backend + ":" + reason;
        counterCache.computeIfAbsent(key, k ->
                Counter.builder("ishin.tunnel.pool.removals.total")
                        .description("Remoções do pool de backends")
                        .tag("virtual_port", String.valueOf(virtualPort))
                        .tag("backend", backend)
                        .tag("reason", reason)
                        .register(registry)
        ).increment();
    }

    public void recordStandbyPromotion(int virtualPort) {
        String key = "promo:" + virtualPort;
        counterCache.computeIfAbsent(key, k ->
                Counter.builder("ishin.tunnel.standby.promotions.total")
                        .description("Promoções automáticas de STANDBY → ACTIVE")
                        .tag("virtual_port", String.valueOf(virtualPort))
                        .register(registry)
        ).increment();
    }

    // ─── Active Connection Gauges ────────────────────────────────────────

    /**
     * Registra gauge global de conexões TCP ativas no tunnel.
     * Vinculado ao {@code AtomicInteger} do {@link TunnelEngine}.
     */
    public void registerGlobalActiveConnections(AtomicInteger counter) {
        Gauge.builder("ishin.tunnel.connections.active", counter, AtomicInteger::get)
                .description("Conexões TCP ativas no tunnel (global)")
                .register(registry);
    }

    /**
     * Registra gauge de conexões ativas por backend+virtualPort.
     * Vinculado ao {@code AtomicInteger} do {@link BackendMember}.
     * Idempotente: registros duplicados são ignorados.
     */
    public void registerPerBackendActiveConnections(int virtualPort, String backend,
                                                     AtomicInteger counter) {
        String gaugeKey = virtualPort + ":" + backend;
        if (registeredPerBackendGauges.add(gaugeKey)) {
            Gauge.builder("ishin.tunnel.connections.active.per_backend", counter, AtomicInteger::get)
                    .description("Conexões TCP ativas por backend")
                    .tag("virtual_port", String.valueOf(virtualPort))
                    .tag("backend", backend)
                    .register(registry);
        }
    }

    // ─── Listener Tracking ──────────────────────────────────────────────

    public void listenerOpened() {
        activeListenerPorts.incrementAndGet();
    }

    public void listenerClosed() {
        activeListenerPorts.decrementAndGet();
    }

    // ─── Routing Duration ───────────────────────────────────────────────

    /**
     * Registra a duração do roteamento interno (lookup do grupo + seleção de membro)
     * antes do handshake TCP com o backend.
     */
    public void recordRoutingDuration(int virtualPort, long durationMs) {
        String key = "routing:" + virtualPort;
        timerCache.computeIfAbsent(key, k ->
                Timer.builder("ishin.tunnel.routing.duration.seconds")
                        .description("Duração do roteamento interno (lookup + seleção)")
                        .tag("virtual_port", String.valueOf(virtualPort))
                        .register(registry)
        ).record(Duration.ofMillis(durationMs));
    }
}
