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
import dev.nishisan.ishin.gateway.configuration.TunnelRegistrationConfiguration;
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

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Serviço de registro do proxy no túnel via NGrid.
 * <p>
 * Ativo apenas quando {@code mode=proxy} e {@code tunnel.registration.enabled=true}.
 * Publica um {@link TunnelRegistryEntry} no NGrid {@code DistributedMap} e mantém
 * heartbeat periódico conforme {@code keepaliveInterval}.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
@Service
public class TunnelRegistrationService {

    private static final Logger logger = LogManager.getLogger(TunnelRegistrationService.class);
    private static final String REGISTRY_KEY_PREFIX = "tunnel:registry:";

    @Autowired
    private ConfigurationManager configurationManager;

    @Autowired
    private ClusterService clusterService;

    private DistributedMap<String, TunnelRegistryEntry> registryMap;
    private TunnelRegistryEntry localEntry;
    private Thread keepaliveThread;
    private volatile boolean running = false;

    @Order(25) // Entre ClusterService (20) e EndpointManager (30)
    @EventListener(ApplicationReadyEvent.class)
    private void onStartup() {
        ServerConfiguration config = configurationManager.loadConfiguration();

        // Só ativa em modo proxy com registration habilitado
        if (config.isTunnelMode()) {
            logger.debug("TunnelRegistrationService: skipping — mode=tunnel");
            return;
        }

        TunnelConfiguration tunnelConfig = config.getTunnel();
        if (tunnelConfig == null || tunnelConfig.getRegistration() == null
                || !tunnelConfig.getRegistration().isEnabled()) {
            logger.debug("TunnelRegistrationService: skipping — tunnel registration not enabled");
            return;
        }

        if (!clusterService.isClusterMode()) {
            logger.error("Tunnel registration requires cluster mode — aborting");
            return;
        }

        TunnelRegistrationConfiguration regConfig = tunnelConfig.getRegistration();

        // Obter o DistributedMap para o registry
        this.registryMap = clusterService.getDistributedMap(
                "ishin-tunnel-registry", String.class, TunnelRegistryEntry.class);

        if (this.registryMap == null) {
            logger.error("Failed to obtain tunnel registry DistributedMap — aborting");
            return;
        }

        // Montar o registry entry a partir dos listeners configurados
        String nodeId = clusterService.getLocalNodeId();
        String host = resolveHost();

        this.localEntry = new TunnelRegistryEntry(
                nodeId, host, regConfig.getStatus(), regConfig.getWeight(),
                regConfig.getKeepaliveInterval());

        // Coletar listeners com virtualPort
        List<TunnelRegistryEntry.ListenerRegistration> listenerRegs = new ArrayList<>();
        config.getEndpoints().forEach((epName, epConfig) -> {
            epConfig.getListeners().forEach((listenerName, listenerConfig) -> {
                int virtualPort = listenerConfig.getEffectiveVirtualPort();
                int realPort = listenerConfig.getListenPort();
                listenerRegs.add(new TunnelRegistryEntry.ListenerRegistration(virtualPort, realPort));
                logger.info("Registering listener '{}' — realPort:{} → vPort:{}",
                        listenerName, realPort, virtualPort);
            });
        });
        localEntry.setListeners(listenerRegs);

        // Publicar no NMap — usa retry com backoff para aguardar estabilização do leader
        String registryKey = REGISTRY_KEY_PREFIX + nodeId;
        publishWithRetry(registryKey, localEntry, 5, 2000);

        // Iniciar keepalive thread
        this.running = true;
        this.keepaliveThread = Thread.ofVirtual()
                .name("tunnel-registration-keepalive")
                .start(() -> keepaliveLoop(regConfig.getKeepaliveInterval(), registryKey));
    }

    /**
     * Publica a entrada no registry com retry e backoff exponencial.
     * Necessário porque o leader NGrid pode ainda não ter estabilizado
     * quando os nós proxy fazem o startup.
     */
    private void publishWithRetry(String registryKey, TunnelRegistryEntry entry,
                                  int maxRetries, long initialDelayMs) {
        long delay = initialDelayMs;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                registryMap.put(registryKey, entry);
                logger.info("Published tunnel registry entry (attempt {}): {} → {}", attempt, registryKey, entry);
                return;
            } catch (IllegalStateException e) {
                logger.warn("Tunnel registration attempt {}/{} failed: {} — retrying in {}ms",
                        attempt, maxRetries, e.getMessage(), delay);
                if (attempt == maxRetries) {
                    logger.error("Tunnel registration failed after {} attempts — continuing without registration. "
                            + "The keepalive loop will retry automatically.", maxRetries);
                    return;
                }
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    logger.warn("Tunnel registration interrupted during retry backoff");
                    return;
                }
                delay = Math.min(delay * 2, 30_000); // backoff exponencial, max 30s
            }
        }
    }
    private void keepaliveLoop(int intervalSeconds, String registryKey) {
        while (running) {
            try {
                Thread.sleep(intervalSeconds * 1000L);
                if (running && localEntry != null && registryMap != null) {
                    localEntry.setLastKeepAlive(System.currentTimeMillis());
                    registryMap.put(registryKey, localEntry);
                    logger.trace("Keepalive updated for {}", registryKey);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (IllegalStateException e) {
                // Leader instável ou não disponível — retry no próximo ciclo
                logger.warn("Keepalive write failed ({}), will retry in {}s", e.getMessage(), intervalSeconds);
            } catch (Exception e) {
                logger.error("Unexpected error in keepalive loop", e);
            }
        }
    }

    @PreDestroy
    private void shutdown() {
        if (localEntry == null || registryMap == null) {
            return;
        }

        logger.info("Tunnel registration: graceful shutdown — setting DRAINING...");

        // Atualizar status para DRAINING
        localEntry.setStatus("DRAINING");
        String registryKey = REGISTRY_KEY_PREFIX + localEntry.getNodeId();
        try {
            registryMap.put(registryKey, localEntry);
        } catch (Exception e) {
            logger.error("Failed to update registry to DRAINING", e);
        }

        // Parar keepalive
        this.running = false;
        if (keepaliveThread != null) {
            keepaliveThread.interrupt();
        }

        // Aguardar drain timeout
        ServerConfiguration config = configurationManager.loadConfiguration();
        int drainTimeout = 30; // default
        if (config.getTunnel() != null) {
            drainTimeout = config.getTunnel().getDrainTimeout();
        }

        try {
            logger.info("Waiting {}s for connections to drain...", drainTimeout);
            Thread.sleep(drainTimeout * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Remover registro
        try {
            registryMap.remove(registryKey);
            logger.info("Tunnel registry entry removed: {}", registryKey);
        } catch (Exception e) {
            logger.error("Failed to remove registry entry", e);
        }
    }

    /**
     * Resolve o host para registro no tunnel.
     * Usa o IP configurado no bloco cluster (o mesmo IP usado para comunicação NGrid),
     * que é o IP real acessível pelos outros nós na rede.
     */
    private String resolveHost() {
        // Preferir o host do cluster — é o IP real da rede usado pelo NGrid
        ServerConfiguration config = configurationManager.loadConfiguration();
        if (config.getCluster() != null && config.getCluster().getHost() != null
                && !config.getCluster().getHost().isBlank()
                && !"0.0.0.0".equals(config.getCluster().getHost())) {
            return config.getCluster().getHost();
        }
        // Fallback para hostname resolution
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            logger.warn("Could not resolve local host address — using 0.0.0.0");
            return "0.0.0.0";
        }
    }
}
