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
package dev.nishisan.ishin.gateway.dashboard.api;

import dev.nishisan.ishin.gateway.tunnel.BackendMember;
import dev.nishisan.ishin.gateway.tunnel.TunnelEngine;
import dev.nishisan.ishin.gateway.tunnel.TunnelMetrics;
import dev.nishisan.ishin.gateway.tunnel.TunnelRegistry;
import dev.nishisan.ishin.gateway.tunnel.TunnelService;
import dev.nishisan.ishin.gateway.tunnel.VirtualPortGroup;
import dev.nishisan.ishin.gateway.tunnel.lb.RoundRobinBalancer;
import dev.nishisan.ishin.gateway.configuration.TunnelConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários de {@link TunnelRuntimeSnapshotFactory}.
 * <p>
 * Verifica que snapshots são montados corretamente e de forma imutável,
 * refletindo o estado real dos registros e listeners.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-18
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TunnelRuntimeSnapshotFactoryTest {

    @Test
    @Order(1)
    @DisplayName("Snapshot vazio quando TunnelService é null")
    void testSnapshotWithNullService() {
        TunnelRuntimeSnapshot snapshot = TunnelRuntimeSnapshotFactory.create(null);
        assertNotNull(snapshot);
        assertEquals("tunnel", snapshot.mode());
        assertEquals(0, snapshot.listeners());
        assertEquals(0, snapshot.groups());
        assertEquals(0, snapshot.members());
        assertEquals(0, snapshot.activeConnections());
        assertTrue(snapshot.virtualPorts().isEmpty());
    }

    @Test
    @Order(2)
    @DisplayName("Snapshot contém dados corretos de VirtualPortGroup com members")
    void testSnapshotReflectsGroupsAndMembers() {
        // Criar registry mínimo com grupo e member
        TunnelConfiguration tunnelConfig = new TunnelConfiguration();
        tunnelConfig.setLoadBalancing("round-robin");
        tunnelConfig.setMissedKeepalives(3);
        tunnelConfig.setDrainTimeout(30);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TunnelMetrics metrics = new TunnelMetrics(meterRegistry);

        TunnelRegistry registry = new TunnelRegistry(tunnelConfig, metrics);

        // Criar grupo manualmente
        VirtualPortGroup group = new VirtualPortGroup(9091, new RoundRobinBalancer());

        // Adicionar member ativo
        BackendMember memberActive = new BackendMember(
                "node-A", "192.168.1.10", 9091,
                "ACTIVE", 100, System.currentTimeMillis(), 15
        );
        group.addOrUpdateMember(memberActive);

        // Adicionar member standby
        BackendMember memberStandby = new BackendMember(
                "node-B", "192.168.1.11", 9091,
                "STANDBY", 50, System.currentTimeMillis(), 15
        );
        group.addOrUpdateMember(memberStandby);

        // Inserir grupo no registry via reflection
        try {
            var groupsField = TunnelRegistry.class.getDeclaredField("groups");
            groupsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var groups = (java.util.concurrent.ConcurrentHashMap<Integer, VirtualPortGroup>) groupsField.get(registry);
            groups.put(9091, group);
        } catch (Exception e) {
            fail("Falha ao injetar grupo via reflection: " + e.getMessage());
        }

        // Criar engine mock (sem listeners reais)
        TunnelEngine engine = new TunnelEngine(registry, metrics, "0.0.0.0");

        // Criar TunnelService mock via reflection
        TunnelService tunnelService = createMockTunnelService(registry, engine);

        // Criar snapshot
        TunnelRuntimeSnapshot snapshot = TunnelRuntimeSnapshotFactory.create(tunnelService);

        assertNotNull(snapshot);
        assertEquals("tunnel", snapshot.mode());
        assertEquals(0, snapshot.listeners()); // Engine não tem listeners abertos
        assertEquals(1, snapshot.groups());
        assertEquals(2, snapshot.members());
        assertEquals(0, snapshot.activeConnections());

        // Verificar virtual ports
        assertEquals(1, snapshot.virtualPorts().size());
        TunnelRuntimeSnapshot.VirtualPortSnapshot vpSnapshot = snapshot.virtualPorts().get(0);
        assertEquals(9091, vpSnapshot.virtualPort());
        assertFalse(vpSnapshot.listenerOpen()); // Não há listener real
        assertEquals(1, vpSnapshot.activeMembers());
        assertEquals(1, vpSnapshot.standbyMembers());
        assertEquals(0, vpSnapshot.drainingMembers());

        // Verificar members
        assertEquals(2, vpSnapshot.members().size());

        TunnelRuntimeSnapshot.TunnelMemberSnapshot activeMember = vpSnapshot.members().stream()
                .filter(m -> "ACTIVE".equals(m.status()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Member ACTIVE não encontrado"));

        assertEquals("node-A:9091", activeMember.backendKey());
        assertEquals("node-A", activeMember.nodeId());
        assertEquals("192.168.1.10", activeMember.host());
        assertEquals(9091, activeMember.realPort());
        assertEquals(100, activeMember.weight());
        assertEquals(0, activeMember.activeConnections());
        assertTrue(activeMember.keepaliveAgeSeconds() >= 0);
    }

    @Test
    @Order(3)
    @DisplayName("Snapshot é imutável (virtualPorts list)")
    void testSnapshotImmutability() {
        TunnelRuntimeSnapshot snapshot = TunnelRuntimeSnapshotFactory.create(null);
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.virtualPorts().add(null),
                "Lista de virtualPorts deve ser imutável");
    }

    // ─── Helper ─────────────────────────────────────────────────────────

    private TunnelService createMockTunnelService(TunnelRegistry registry, TunnelEngine engine) {
        try {
            // TunnelService é um Spring component, instanciamos via reflection
            var constructor = TunnelService.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            TunnelService service = constructor.newInstance();

            // Set registry
            var registryField = TunnelService.class.getDeclaredField("tunnelRegistry");
            registryField.setAccessible(true);
            registryField.set(service, registry);

            // Set engine
            var engineField = TunnelService.class.getDeclaredField("tunnelEngine");
            engineField.setAccessible(true);
            engineField.set(service, engine);

            // Set running
            var runningField = TunnelService.class.getDeclaredField("running");
            runningField.setAccessible(true);
            runningField.set(service, true);

            return service;
        } catch (Exception e) {
            fail("Falha ao criar mock TunnelService: " + e.getMessage());
            return null;
        }
    }
}
