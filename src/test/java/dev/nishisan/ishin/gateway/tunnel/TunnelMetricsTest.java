/*
 * Copyright (C) 2026 Lucas Nishimura <lucas.nishimura@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dev.nishisan.ishin.gateway.tunnel;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários do {@link TunnelMetrics} — gauges de conexões ativas.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-18
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TunnelMetricsTest {

    private SimpleMeterRegistry registry;
    private TunnelMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new TunnelMetrics(registry);
    }

    @Test
    @Order(1)
    @DisplayName("T1: Gauge global reflete valor do AtomicInteger")
    void testGlobalActiveConnectionsGauge() {
        AtomicInteger counter = new AtomicInteger(0);
        metrics.registerGlobalActiveConnections(counter);

        Gauge gauge = registry.find("ishin.tunnel.connections.active").gauge();
        assertNotNull(gauge, "Gauge global deve estar registrado");
        assertEquals(0.0, gauge.value(), "Valor inicial deve ser 0");

        counter.set(42);
        assertEquals(42.0, gauge.value(), "Gauge deve refletir valor atualizado");

        counter.decrementAndGet();
        assertEquals(41.0, gauge.value(), "Gauge deve refletir decremento");
    }

    @Test
    @Order(2)
    @DisplayName("T2: Gauge per-backend reflete valor com tags corretas")
    void testPerBackendActiveConnectionsGauge() {
        AtomicInteger counter = new AtomicInteger(5);
        metrics.registerPerBackendActiveConnections(9091, "nodeA:9091", counter);

        Gauge gauge = registry.find("ishin.tunnel.connections.active.per_backend")
                .tag("virtual_port", "9091")
                .tag("backend", "nodeA:9091")
                .gauge();

        assertNotNull(gauge, "Gauge per-backend deve estar registrado com tags");
        assertEquals(5.0, gauge.value(), "Valor deve refletir AtomicInteger");

        counter.set(0);
        assertEquals(0.0, gauge.value(), "Gauge deve refletir reset");
    }

    @Test
    @Order(3)
    @DisplayName("T3: Registro duplicado do per-backend é idempotente")
    void testPerBackendIdempotentRegistration() {
        AtomicInteger counter1 = new AtomicInteger(10);
        AtomicInteger counter2 = new AtomicInteger(99);

        metrics.registerPerBackendActiveConnections(9091, "nodeB:9091", counter1);
        // Segundo registro com mesmo vPort+backend — deve ser ignorado
        metrics.registerPerBackendActiveConnections(9091, "nodeB:9091", counter2);

        long gaugeCount = registry.find("ishin.tunnel.connections.active.per_backend")
                .tag("virtual_port", "9091")
                .tag("backend", "nodeB:9091")
                .gauges()
                .size();

        assertEquals(1, gaugeCount, "Deve existir exatamente 1 gauge (idempotente)");

        // Valor deve ser do primeiro registro (counter1)
        Gauge gauge = registry.find("ishin.tunnel.connections.active.per_backend")
                .tag("virtual_port", "9091")
                .tag("backend", "nodeB:9091")
                .gauge();
        assertNotNull(gauge);
        assertEquals(10.0, gauge.value(), "Gauge deve refletir o primeiro counter registrado");
    }

    @Test
    @Order(4)
    @DisplayName("T4: Múltiplos backends em portas virtuais distintas coexistem")
    void testMultipleBackendsDifferentPorts() {
        AtomicInteger counterA = new AtomicInteger(3);
        AtomicInteger counterB = new AtomicInteger(7);

        metrics.registerPerBackendActiveConnections(9091, "nodeA:9091", counterA);
        metrics.registerPerBackendActiveConnections(9092, "nodeA:9092", counterB);

        Gauge gaugeA = registry.find("ishin.tunnel.connections.active.per_backend")
                .tag("virtual_port", "9091")
                .tag("backend", "nodeA:9091")
                .gauge();
        Gauge gaugeB = registry.find("ishin.tunnel.connections.active.per_backend")
                .tag("virtual_port", "9092")
                .tag("backend", "nodeA:9092")
                .gauge();

        assertNotNull(gaugeA);
        assertNotNull(gaugeB);
        assertEquals(3.0, gaugeA.value());
        assertEquals(7.0, gaugeB.value());
    }
}
