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
package dev.nishisan.ishin.gateway.cluster;

import dev.nishisan.ishin.gateway.configuration.ClusterConfiguration;
import dev.nishisan.ishin.gateway.manager.ConfigurationManager;
import dev.nishisan.utils.ngrid.common.NodeId;
import dev.nishisan.utils.ngrid.common.NodeInfo;
import dev.nishisan.utils.ngrid.structures.DistributedMap;
import dev.nishisan.utils.ngrid.structures.NGridConfig;
import dev.nishisan.utils.ngrid.structures.NGridNode;

import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Serializable;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Gerencia o ciclo de vida do NGridNode embarcado no Ishin Gateway.
 * <p>
 * Se o bloco {@code cluster:} estiver habilitado no {@code adapter.yaml},
 * este serviço inicializa um {@link NGridNode} com mesh TCP, leader election
 * e distributed structures. Caso contrário, opera em modo standalone transparente.
 * <p>
 * Expõe APIs para outros componentes verificarem leadership e obterem
 * {@link DistributedMap} instances.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-08
 */
@Service
public class ClusterService {

    private static final Logger logger = LogManager.getLogger(ClusterService.class);

    @Autowired
    private ConfigurationManager configurationManager;

    @Autowired
    private MeterRegistry meterRegistry;

    private NGridNode gridNode;
    private boolean clusterMode = false;
    private String localNodeId;

    /**
     * Listeners notificados quando o status de liderança muda.
     * Consumer args: (isLeader, leaderNodeId)
     */
    private final List<BiConsumer<Boolean, String>> leadershipListeners = new CopyOnWriteArrayList<>();

    @Order(20)
    @EventListener(ApplicationReadyEvent.class)
    private void onStartup() {
        var config = configurationManager.loadConfiguration();
        ClusterConfiguration clusterConfig = config.getCluster();

        if (clusterConfig == null || !clusterConfig.isEnabled()) {
            logger.info("Cluster mode: DISABLED (standalone)");
            this.clusterMode = false;
            this.localNodeId = resolveNodeId(null);
            return;
        }

        logger.info("Cluster mode: ENABLED — initializing NGrid mesh...");
        this.clusterMode = true;

        try {
            this.localNodeId = resolveNodeId(clusterConfig.getNodeId());
            NGridConfig gridConfig = buildNGridConfig(clusterConfig);
            this.gridNode = new NGridNode(gridConfig);
            this.gridNode.start();

            // Registrar listener de leadership
            this.gridNode.coordinator().addLeadershipListener(newLeader -> {
                boolean isLeader = newLeader != null &&
                        newLeader.equals(gridNode.transport().local().nodeId());
                String leaderId = newLeader != null ? newLeader.value() : "unknown";
                logger.info("Leadership change — isLeader: [{}], leader: [{}]", isLeader, leaderId);
                leadershipListeners.forEach(l -> {
                    try {
                        l.accept(isLeader, leaderId);
                    } catch (Exception e) {
                        logger.error("Error notifying leadership listener", e);
                    }
                });
            });

            logger.info("NGrid cluster started — nodeId: [{}], cluster: [{}], port: [{}]",
                    localNodeId, clusterConfig.getClusterName(), clusterConfig.getPort());

            // Registrar Gauges de cluster no Micrometer
            Gauge.builder("ishin.cluster.active.members", this, ClusterService::getActiveMembersCount)
                    .description("Number of active members in the NGrid cluster")
                    .register(meterRegistry);
            Gauge.builder("ishin.cluster.is.leader", this, cs -> cs.isLeader() ? 1.0 : 0.0)
                    .description("Whether this instance is the NGrid cluster leader (1=leader, 0=follower)")
                    .register(meterRegistry);
        } catch (Exception e) {
            logger.error("Failed to start NGrid cluster — falling back to standalone", e);
            this.clusterMode = false;
            this.gridNode = null;
        }
    }

    @PreDestroy
    private void shutdown() {
        if (gridNode != null) {
            logger.info("Shutting down NGrid cluster node...");
            try {
                gridNode.close();
                logger.info("NGrid cluster node shut down successfully.");
            } catch (IOException e) {
                logger.error("Error during NGrid shutdown", e);
            }
        }
    }

    /**
     * @return true se o cluster está habilitado e o NGridNode está ativo
     */
    public boolean isClusterMode() {
        return clusterMode && gridNode != null;
    }

    /**
     * @return true se esta instância é o líder do cluster, ou true se standalone
     */
    public boolean isLeader() {
        if (!isClusterMode()) {
            return true; // standalone é sempre "líder"
        }
        return gridNode.coordinator().isLeader();
    }

    /**
     * @return o ID do nó local
     */
    public String getLocalNodeId() {
        return localNodeId;
    }

    /**
     * @return contagem de membros ativos no cluster, ou 1 se standalone
     */
    public int getActiveMembersCount() {
        if (!isClusterMode()) {
            return 1;
        }
        return gridNode.coordinator().getActiveMembersCount();
    }

    /**
     * Obtém ou cria um DistributedMap com o nome dado.
     *
     * @return o mapa distribuído, ou null se standalone
     */
    public <K extends Serializable, V extends Serializable> DistributedMap<K, V> getDistributedMap(
            String name, Class<K> keyType, Class<V> valueType) {
        if (!isClusterMode()) {
            return null;
        }
        return gridNode.getMap(name, keyType, valueType);
    }

    /**
     * Registra um listener para mudanças de liderança.
     *
     * @param listener consumer (isLeader, leaderNodeId)
     */
    public void addLeadershipListener(BiConsumer<Boolean, String> listener) {
        leadershipListeners.add(listener);
    }

    /**
     * Retorna os nodeIds dos peers do cluster (excluindo o nó local).
     * Derivados do bloco {@code cluster.seeds} do {@code adapter.yaml}.
     * <p>
     * Usado pelo {@code TunnelRegistry} para autodiscovery dinâmico
     * de registry keys no DistributedMap.
     *
     * @return lista de nodeIds dos peers, ou lista vazia se standalone
     */
    public List<String> getClusterPeerNodeIds() {
        if (!isClusterMode()) return List.of();
        ClusterConfiguration clusterConfig = configurationManager.loadConfiguration().getCluster();
        if (clusterConfig == null || clusterConfig.getSeeds() == null) return List.of();

        List<String> peerIds = new ArrayList<>();
        for (String seed : clusterConfig.getSeeds()) {
            String[] parts = seed.split(":");
            if (parts.length >= 1) {
                String seedNodeId = parts[0];
                if (!seedNodeId.equals(localNodeId)) {
                    peerIds.add(seedNodeId);
                }
            }
        }
        return peerIds;
    }

    /**
     * @return a instância do NGridNode, ou null se standalone
     */
    public NGridNode getNGridNode() {
        return gridNode;
    }

    private NGridConfig buildNGridConfig(ClusterConfiguration config) {
        NodeId nodeId = NodeId.of(localNodeId);
        NodeInfo local = new NodeInfo(nodeId, config.getHost(), config.getPort());

        NGridConfig.Builder builder = NGridConfig.builder(local)
                .clusterName(config.getClusterName())
                .dataDirectory(Path.of(config.getDataDirectory()))
                .replicationFactor(config.getReplicationFactor())
                .heartbeatInterval(Duration.ofSeconds(1))
                .leaseTimeout(Duration.ofSeconds(10))
                .mapName("ishin-tokens");

        // Adicionar peers a partir dos seeds, excluindo o próprio nó
        if (config.getSeeds() != null) {
            String localHostname = resolveHostname();
            for (String seed : config.getSeeds()) {
                String[] parts = seed.split(":");
                if (parts.length == 2) {
                    String host = parts[0];
                    int port = Integer.parseInt(parts[1]);

                    // Filtrar self-seed: comparar hostname do seed com nodeId e hostname local
                    if (host.equals(localNodeId) || host.equals(localHostname)) {
                        logger.debug("Skipping self-seed: [{}]", seed);
                        continue;
                    }

                    // NodeId do peer usa apenas hostname (consistente com o NodeId local)
                    NodeId peerId = NodeId.of(host);
                    NodeInfo peer = new NodeInfo(peerId, host, port);
                    builder.addPeer(peer);
                    logger.debug("Added peer: [{}] at [{}:{}]", host, host, port);
                } else {
                    logger.warn("Invalid seed format (expected host:port): [{}]", seed);
                }
            }
        }

        return builder.build();
    }

    private static String resolveHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "";
        }
    }

    private static String resolveNodeId(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        // Tentar variável de ambiente
        String envId = System.getenv("ISHIN_CLUSTER_NODE_ID");
        if (envId != null && !envId.isBlank()) {
            return envId.trim();
        }
        // Fallback para hostname
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "ishin-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }
}
