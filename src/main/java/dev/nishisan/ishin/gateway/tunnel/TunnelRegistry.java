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

import dev.nishisan.ishin.gateway.configuration.TunnelConfiguration;
import dev.nishisan.utils.ngrid.structures.DistributedMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Observa o NGrid {@code DistributedMap} e mantém os {@link VirtualPortGroup}s
 * sincronizados com o estado do registry.
 * <p>
 * Responsabilidades:
 * <ul>
 *   <li>Polling periódico do NMap para detectar novos registros e atualizações</li>
 *   <li>Keepalive timeout checker (Camada 2 de detecção de falha)</li>
 *   <li>Promoção automática de STANDBY quando zero ACTIVE</li>
 *   <li>Notificação de listeners (open/close) ao {@link TunnelEngine}</li>
 * </ul>
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
public class TunnelRegistry {

    private static final Logger logger = LogManager.getLogger(TunnelRegistry.class);

    static final String REGISTRY_KEY_PREFIX = "tunnel:registry:";

    private final ConcurrentHashMap<Integer, VirtualPortGroup> groups = new ConcurrentHashMap<>();
    private final Set<String> knownRegistryKeys = ConcurrentHashMap.newKeySet();
    private final TunnelConfiguration config;
    private final TunnelLoadBalancer loadBalancer;
    private final TunnelMetrics metrics;

    // Callbacks para o TunnelEngine abrir/fechar listeners
    private Consumer<Integer> onGroupCreated;   // virtualPort
    private Consumer<Integer> onGroupRemoved;   // virtualPort

    // Callbacks para o event bridge do dashboard
    private BiConsumer<Integer, String> onMemberAdded;      // (virtualPort, memberKey)
    private BiConsumer<Integer, String> onMemberRemoved;    // (virtualPort, memberKey)
    private BiConsumer<Integer, String> onStandbyPromoted;  // (virtualPort, memberKey)
    private BiConsumer<Integer, String> onKeepaliveTimeout; // (virtualPort, memberKey)

    private volatile boolean running = false;
    private Thread pollerThread;
    private Thread keepaliveCheckerThread;

    private DistributedMap<String, TunnelRegistryEntry> registryMap;

    public TunnelRegistry(TunnelConfiguration config, TunnelMetrics metrics) {
        this.config = config;
        this.loadBalancer = TunnelLoadBalancer.forAlgorithm(config.getLoadBalancing());
        this.metrics = metrics;
    }

    public void setOnGroupCreated(Consumer<Integer> onGroupCreated) {
        this.onGroupCreated = onGroupCreated;
    }

    public void setOnGroupRemoved(Consumer<Integer> onGroupRemoved) {
        this.onGroupRemoved = onGroupRemoved;
    }

    public void setOnMemberAdded(BiConsumer<Integer, String> onMemberAdded) {
        this.onMemberAdded = onMemberAdded;
    }

    public void setOnMemberRemoved(BiConsumer<Integer, String> onMemberRemoved) {
        this.onMemberRemoved = onMemberRemoved;
    }

    public void setOnStandbyPromoted(BiConsumer<Integer, String> onStandbyPromoted) {
        this.onStandbyPromoted = onStandbyPromoted;
    }

    public void setOnKeepaliveTimeout(BiConsumer<Integer, String> onKeepaliveTimeout) {
        this.onKeepaliveTimeout = onKeepaliveTimeout;
    }

    public void setRegistryMap(DistributedMap<String, TunnelRegistryEntry> registryMap) {
        this.registryMap = registryMap;
    }

    /**
     * Inicia o polling e o keepalive checker em threads separadas.
     */
    public void start() {
        if (registryMap == null) {
            logger.error("Cannot start TunnelRegistry — registryMap not set");
            return;
        }

        this.running = true;

        // Poller: verifica mudanças no NMap a cada 1s
        this.pollerThread = Thread.ofVirtual().name("tunnel-registry-poller").start(() -> {
            while (running) {
                try {
                    pollRegistry();
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Error in registry poller", e);
                }
            }
        });

        // Keepalive checker: verifica timeouts a cada 1s
        this.keepaliveCheckerThread = Thread.ofVirtual().name("tunnel-keepalive-checker").start(() -> {
            while (running) {
                try {
                    checkKeepaliveTimeouts();
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Error in keepalive checker", e);
                }
            }
        });

        logger.info("TunnelRegistry started — poller and keepalive checker active");
    }

    public void stop() {
        this.running = false;
        if (pollerThread != null) {
            pollerThread.interrupt();
        }
        if (keepaliveCheckerThread != null) {
            keepaliveCheckerThread.interrupt();
        }
        logger.info("TunnelRegistry stopped");
    }

    /**
     * Lê todas as entradas do NMap e sincroniza com os VirtualPortGroups.
     */
    private void pollRegistry() {
        if (registryMap == null) return;

        // Coletar todos os member keys atuais para detectar remoções
        ConcurrentHashMap<Integer, List<String>> currentKeys = new ConcurrentHashMap<>();
        groups.forEach((vp, group) -> {
            List<String> keys = new ArrayList<>();
            for (BackendMember m : group.getAllMembers()) {
                keys.add(m.getKey());
            }
            currentKeys.put(vp, keys);
        });

        ConcurrentHashMap<Integer, List<String>> seenKeys = new ConcurrentHashMap<>();

        // Iterar sobre as registry keys conhecidas usando get() por key
        // (DistributedMap do NGrid não expõe entrySet())
        Set<String> keysSnapshot = new HashSet<>(knownRegistryKeys);
        List<String> keysToForget = new ArrayList<>();

        for (String registryKey : keysSnapshot) {
            TunnelRegistryEntry registryEntry = registryMap.get(registryKey).orElse(null);
            if (registryEntry == null) {
                keysToForget.add(registryKey);
                continue;
            }

            for (TunnelRegistryEntry.ListenerRegistration listener : registryEntry.getListeners()) {
                int virtualPort = listener.getVirtualPort();

                // Criar grupo se não existe
                VirtualPortGroup group = groups.computeIfAbsent(virtualPort, vp -> {
                    VirtualPortGroup newGroup = new VirtualPortGroup(vp, loadBalancer);
                    logger.info("New VirtualPortGroup created for port {}", vp);
                    if (onGroupCreated != null) {
                        onGroupCreated.accept(vp);
                    }
                    return newGroup;
                });

                // Criar ou atualizar membro
                BackendMember member = new BackendMember(
                        registryEntry.getNodeId(),
                        registryEntry.getHost(),
                        listener.getRealPort(),
                        registryEntry.getStatus(),
                        registryEntry.getWeight(),
                        registryEntry.getLastKeepAlive(),
                        registryEntry.getKeepaliveInterval()
                );

                boolean isNew = group.addOrUpdateMember(member);
                if (isNew && onMemberAdded != null) {
                    onMemberAdded.accept(virtualPort, member.getKey());
                }

                // Registrar key como "vista"
                seenKeys.computeIfAbsent(virtualPort, k -> new ArrayList<>())
                        .add(member.getKey());
            }
        }

        // Remover registry keys que não existem mais no NMap
        for (String key : keysToForget) {
            knownRegistryKeys.remove(key);
        }

        // Detectar membros que sumiram do registry (deregister explícito)
        for (Map.Entry<Integer, List<String>> entry : currentKeys.entrySet()) {
            int vp = entry.getKey();
            List<String> seen = seenKeys.getOrDefault(vp, Collections.emptyList());
            for (String key : entry.getValue()) {
                if (!seen.contains(key)) {
                    VirtualPortGroup group = groups.get(vp);
                    if (group != null) {
                        boolean empty = group.removeMember(key);
                        metrics.recordPoolRemoval(vp, key, "graceful");
                        if (onMemberRemoved != null) {
                            onMemberRemoved.accept(vp, key);
                        }
                        if (empty) {
                            groups.remove(vp);
                            logger.info("VirtualPortGroup removed — vPort:{} has no members", vp);
                            if (onGroupRemoved != null) {
                                onGroupRemoved.accept(vp);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Verifica keepalive timeouts (Camada 2 de detecção de falha).
     */
    private void checkKeepaliveTimeouts() {
        int missedKeepalives = config.getMissedKeepalives();

        for (Map.Entry<Integer, VirtualPortGroup> entry : groups.entrySet()) {
            int vp = entry.getKey();
            VirtualPortGroup group = entry.getValue();

            List<String> toRemove = new ArrayList<>();

            for (BackendMember member : group.getAllMembers()) {
                if (member.isDraining()) continue; // draining tem seu próprio timeout

                if (member.isKeepaliveExpired(missedKeepalives)) {
                    logger.warn("Keepalive timeout — removing {} from vPort:{} " +
                                    "(last keepalive: {}ms ago)",
                            member.getKey(), vp,
                            System.currentTimeMillis() - member.getLastKeepAlive());
                    toRemove.add(member.getKey());
                }
            }

            for (String key : toRemove) {
                boolean empty = group.removeMember(key);
                metrics.recordPoolRemoval(vp, key, "keepalive_timeout");
                if (onKeepaliveTimeout != null) {
                    onKeepaliveTimeout.accept(vp, key);
                }

                if (empty) {
                    groups.remove(vp);
                    logger.info("VirtualPortGroup removed — vPort:{} has no members", vp);
                    if (onGroupRemoved != null) {
                        onGroupRemoved.accept(vp);
                    }
                }
            }

            // Auto-promote STANDBY se zero ACTIVE e config permite
            if (config.isAutoPromoteStandby() &&
                    group.getActiveMemberCount() == 0 &&
                    group.getStandbyMemberCount() > 0) {

                BackendMember promoted = group.promoteStandby();
                if (promoted != null) {
                    metrics.recordStandbyPromotion(vp);
                    if (onStandbyPromoted != null) {
                        onStandbyPromoted.accept(vp, promoted.getKey());
                    }
                    logger.info("Auto-promoted STANDBY {} in vPort:{}", promoted.getKey(), vp);
                }
            }
        }
    }

    /**
     * Remove um membro imediatamente (chamado pelo TunnelEngine após IOException).
     */
    public void removeMemberImmediate(int virtualPort, String memberKey, String reason) {
        VirtualPortGroup group = groups.get(virtualPort);
        if (group != null) {
            boolean empty = group.removeMember(memberKey);
            metrics.recordPoolRemoval(virtualPort, memberKey, reason);

            if (empty) {
                groups.remove(virtualPort);
                if (onGroupRemoved != null) {
                    onGroupRemoved.accept(virtualPort);
                }
            } else if (config.isAutoPromoteStandby() &&
                    group.getActiveMemberCount() == 0 &&
                    group.getStandbyMemberCount() > 0) {
                BackendMember promoted = group.promoteStandby();
                if (promoted != null) {
                    metrics.recordStandbyPromotion(virtualPort);
                }
            }
        }
    }

    /**
     * Obtém o grupo para uma porta virtual.
     */
    public VirtualPortGroup getGroup(int virtualPort) {
        return groups.get(virtualPort);
    }

    /**
     * Retorna todas as portas virtuais com grupos ativos.
     */
    public List<Integer> getActiveVirtualPorts() {
        return new ArrayList<>(groups.keySet());
    }

    public int getTotalGroupCount() {
        return groups.size();
    }

    public int getTotalMemberCount() {
        return groups.values().stream().mapToInt(VirtualPortGroup::getMemberCount).sum();
    }

    /**
     * Registra uma registry key conhecida para polling.
     * Chamado pelo TunnelRegistrationService quando um proxy publica seu registro.
     */
    public void addKnownRegistryKey(String key) {
        knownRegistryKeys.add(key);
    }

    /**
     * Remove uma registry key conhecida.
     */
    public void removeKnownRegistryKey(String key) {
        knownRegistryKeys.remove(key);
    }

    // ─── Snapshot Methods ───────────────────────────────────────────────

    /**
     * Retorna cópia defensiva do mapa de grupos.
     */
    public Map<Integer, VirtualPortGroup> getGroupsSnapshot() {
        return new HashMap<>(groups);
    }

    public int getTotalActiveMembers() {
        return groups.values().stream().mapToInt(VirtualPortGroup::getActiveMemberCount).sum();
    }

    public int getTotalStandbyMembers() {
        return groups.values().stream().mapToInt(VirtualPortGroup::getStandbyMemberCount).sum();
    }

    public int getTotalDrainingMembers() {
        return groups.values().stream().mapToInt(VirtualPortGroup::getDrainingMemberCount).sum();
    }
}
