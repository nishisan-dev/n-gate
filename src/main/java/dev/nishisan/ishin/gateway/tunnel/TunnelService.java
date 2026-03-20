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

import dev.nishisan.ishin.gateway.cluster.ClusterService;
import dev.nishisan.ishin.gateway.configuration.ServerConfiguration;
import dev.nishisan.ishin.gateway.configuration.TunnelConfiguration;
import dev.nishisan.ishin.gateway.manager.ConfigurationManager;
import dev.nishisan.utils.ngrid.structures.DistributedMap;
import jakarta.annotation.PreDestroy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Orquestrador do Tunnel Mode — substitui o {@code EndpointManager} quando
 * {@code mode: tunnel}.
 * <p>
 * Inicializa o {@link TunnelRegistry} e o {@link TunnelEngine}, conecta
 * os callbacks de lifecycle (open/close listener) e gerencia o shutdown graceful.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
@Service
public class TunnelService {

    private static final Logger logger = LogManager.getLogger(TunnelService.class);

    @Autowired
    private ConfigurationManager configurationManager;

    @Autowired
    private ClusterService clusterService;

    @Autowired
    private TunnelMetrics tunnelMetrics;

    private TunnelRegistry tunnelRegistry;
    private TunnelEngine tunnelEngine;
    private volatile boolean running = false;

    // Bridge de eventos para o dashboard (set externamente pelo DashboardService)
    private dev.nishisan.ishin.gateway.dashboard.TunnelDashboardEventBridge eventBridge;

    @Order(30) // Mesmo slot do EndpointManager
    @EventListener(ApplicationReadyEvent.class)
    private void onStartup() {
        ServerConfiguration config = configurationManager.loadConfiguration();

        if (!config.isTunnelMode()) {
            logger.debug("TunnelService: skipping — mode={}", config.getMode());
            return;
        }

        logger.info("═══════════════════════════════════════════════════════");
        logger.info("  Ishin Gateway TUNNEL MODE — TCP L4 Load Balancer");
        logger.info("═══════════════════════════════════════════════════════");

        // Validar pré-requisitos
        if (!clusterService.isClusterMode()) {
            logger.error("Tunnel mode requires cluster mode (NGrid) — aborting");
            return;
        }

        TunnelConfiguration tunnelConfig = config.getTunnel();
        if (tunnelConfig == null) {
            tunnelConfig = new TunnelConfiguration(); // usar defaults
            logger.info("No tunnel config block — using defaults");
        }

        // Obter o DistributedMap para o registry
        DistributedMap<String, TunnelRegistryEntry> registryMap = clusterService.getDistributedMap(
                "ishin-tunnel-registry", String.class, TunnelRegistryEntry.class);

        if (registryMap == null) {
            logger.error("Failed to obtain tunnel registry DistributedMap — aborting");
            return;
        }

        // Inicializar TunnelRegistry com autodiscovery via ClusterService
        this.tunnelRegistry = new TunnelRegistry(tunnelConfig, tunnelMetrics, clusterService);
        this.tunnelRegistry.setRegistryMap(registryMap);

        // Inicializar TunnelEngine
        this.tunnelEngine = new TunnelEngine(tunnelRegistry, tunnelMetrics, tunnelConfig.getBindAddress());

        // Conectar event bridge se disponível
        if (eventBridge != null) {
            connectEventBridge();
        }

        // Conectar callbacks de lifecycle
        tunnelRegistry.setOnGroupCreated(vPort -> {
            logger.info("VirtualPortGroup created — opening listener on vPort:{}", vPort);
            tunnelEngine.openListener(vPort);
        });

        tunnelRegistry.setOnGroupRemoved(vPort -> {
            logger.info("VirtualPortGroup removed — closing listener on vPort:{}", vPort);
            tunnelEngine.closeListener(vPort);
        });

        // Iniciar componentes
        tunnelEngine.start();
        tunnelRegistry.start();

        this.running = true;

        logger.info("Tunnel Mode fully initialized — LB algorithm: {}, missedKeepalives: {}, drainTimeout: {}s",
                tunnelConfig.getLoadBalancing(), tunnelConfig.getMissedKeepalives(), tunnelConfig.getDrainTimeout());
    }

    @PreDestroy
    private void shutdown() {
        this.running = false;
        if (tunnelRegistry != null) {
            logger.info("Tunnel Mode: graceful shutdown...");
            tunnelRegistry.stop();
        }
        if (tunnelEngine != null) {
            tunnelEngine.stop();
            logger.info("Tunnel Mode: shutdown complete — {} connections were active",
                    tunnelEngine.getTotalActiveConnections());
        }
    }

    // ─── Runtime Access (read-only) ─────────────────────────────────────

    public boolean isRunning() {
        return running;
    }

    public TunnelRegistry getTunnelRegistry() {
        return tunnelRegistry;
    }

    public TunnelEngine getTunnelEngine() {
        return tunnelEngine;
    }

    /**
     * Define o event bridge para integração com o dashboard.
     * Chamado pelo DashboardService antes do startup do tunnel.
     */
    public void setEventBridge(dev.nishisan.ishin.gateway.dashboard.TunnelDashboardEventBridge eventBridge) {
        this.eventBridge = eventBridge;
        // Se tunnel já iniciou, conectar imediatamente
        if (tunnelRegistry != null && tunnelEngine != null) {
            connectEventBridge();
        }
    }

    private void connectEventBridge() {
        if (eventBridge == null) return;

        // Registry callbacks
        tunnelRegistry.setOnMemberAdded((vPort, memberKey) ->
                eventBridge.onMemberAdded(vPort, memberKey));
        tunnelRegistry.setOnMemberRemoved((vPort, memberKey) ->
                eventBridge.onMemberRemoved(vPort, memberKey));
        tunnelRegistry.setOnStandbyPromoted((vPort, memberKey) ->
                eventBridge.onStandbyPromoted(vPort, memberKey));
        tunnelRegistry.setOnKeepaliveTimeout((vPort, memberKey) ->
                eventBridge.onKeepaliveTimeout(vPort, memberKey));

        // Engine callbacks
        tunnelEngine.setOnListenerOpened(vPort ->
                eventBridge.onListenerOpened(vPort));
        tunnelEngine.setOnListenerClosed(vPort ->
                eventBridge.onListenerClosed(vPort));
        tunnelEngine.setOnConnectError((vPort, backend, errorType) ->
                eventBridge.onConnectError(vPort, backend, errorType));

        logger.info("TunnelService: event bridge connected to dashboard");
    }
}
