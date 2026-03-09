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
package dev.nishisan.ngate.rules;

import dev.nishisan.ngate.cluster.ClusterService;
import dev.nishisan.ngate.http.EndpointWrapper;
import dev.nishisan.ngate.manager.EndpointManager;
import dev.nishisan.utils.ngrid.structures.DistributedMap;
import groovy.util.GroovyScriptEngine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gerencia o ciclo de vida dos bundles de rules Groovy.
 * <p>
 * Responsabilidades:
 * <ul>
 *   <li>Receber bundles via Admin API</li>
 *   <li>Persistir em disco para sobreviver restarts</li>
 *   <li>Materializar scripts em diretório temporário</li>
 *   <li>Criar novo {@link GroovyScriptEngine} e swap atômico</li>
 *   <li>Publicar no {@link DistributedMap} para replicação cluster</li>
 *   <li>Polling periódico do DistributedMap para aplicar bundles de peers</li>
 * </ul>
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-09
 */
@Service
public class RulesBundleManager {

    private static final Logger logger = LogManager.getLogger(RulesBundleManager.class);
    private static final String RULES_MAP_NAME = "ngate-rules";
    private static final String RULES_MAP_KEY = "active-bundle";
    private static final String BUNDLE_PERSIST_FILE = "data/rules-bundle.dat";
    private static final long CLUSTER_POLL_INTERVAL_SECONDS = 5;

    @Autowired
    private ApplicationContext applicationContext;

    private final AtomicReference<RulesBundle> activeBundle = new AtomicReference<>();
    private final AtomicLong versionCounter = new AtomicLong(0);

    private DistributedMap<String, RulesBundle> distributedRulesMap;
    private EndpointManager endpointManager;
    private ClusterService clusterService;
    private ScheduledExecutorService clusterPollExecutor;

    @EventListener(ApplicationReadyEvent.class)
    private void onStartup() {
        this.endpointManager = applicationContext.getBean(EndpointManager.class);

        // Lookup seguro — ClusterService pode não estar disponível em standalone puro
        try {
            this.clusterService = applicationContext.getBean(ClusterService.class);
        } catch (Exception ex) {
            logger.info("ClusterService not available — running in standalone mode");
            this.clusterService = null;
        }

        // Tentar carregar bundle persistido do disco
        RulesBundle persisted = loadFromDisk();
        if (persisted != null) {
            logger.info("Found persisted rules bundle v{} from {} — applying...",
                    persisted.version(), persisted.deployedAt());
            applyBundleLocally(persisted);
        } else {
            logger.info("No persisted rules bundle found — using default rules/ directory");
        }

        // Inicializar integração cluster se habilitado
        if (clusterService != null && clusterService.isClusterMode()) {
            logger.info("RulesBundleManager: cluster mode — initializing distributed rules map");
            this.distributedRulesMap = clusterService.getDistributedMap(
                    RULES_MAP_NAME, String.class, RulesBundle.class);

            // Se não temos bundle local, verificar se o cluster já tem um
            if (persisted == null && distributedRulesMap != null) {
                try {
                    RulesBundle fromCluster = distributedRulesMap.get(RULES_MAP_KEY).orElse(null);
                    if (fromCluster != null) {
                        logger.info("Found rules bundle v{} in cluster — applying...", fromCluster.version());
                        applyBundleLocally(fromCluster);
                        persistToDisk(fromCluster);
                    }
                } catch (Exception ex) {
                    logger.warn("Failed to read rules bundle from cluster DistributedMap", ex);
                }
            }

            // Iniciar polling thread para detectar bundles publicados por peers
            if (distributedRulesMap != null) {
                this.clusterPollExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "ngate-rules-cluster-poll");
                    t.setDaemon(true);
                    return t;
                });
                clusterPollExecutor.scheduleWithFixedDelay(
                        this::pollClusterForUpdates,
                        CLUSTER_POLL_INTERVAL_SECONDS,
                        CLUSTER_POLL_INTERVAL_SECONDS,
                        TimeUnit.SECONDS
                );
                logger.info("Cluster rules polling started — interval={}s", CLUSTER_POLL_INTERVAL_SECONDS);
            }
        }
    }

    /**
     * Verifica periodicamente se existe um bundle mais recente no DistributedMap
     * (publicado por outro nó do cluster).
     */
    private void pollClusterForUpdates() {
        try {
            RulesBundle fromCluster = distributedRulesMap.get(RULES_MAP_KEY).orElse(null);
            if (fromCluster != null) {
                RulesBundle current = activeBundle.get();
                if (current == null || fromCluster.version() > current.version()) {
                    logger.info("Cluster rules update detected — applying bundle v{} from peer",
                            fromCluster.version());
                    applyBundleLocally(fromCluster);
                    persistToDisk(fromCluster);
                }
            }
        } catch (Exception ex) {
            logger.warn("Failed to poll cluster for rules updates", ex);
        }
    }

    /**
     * Deploya um novo bundle de rules. Chamado pelo Admin API.
     *
     * @param scripts  mapa de path relativo → conteúdo do script (bytes)
     * @param deployedBy identificador de quem realizou o deploy
     * @return o RulesBundle criado
     */
    public RulesBundle deploy(Map<String, byte[]> scripts, String deployedBy) {
        long version = versionCounter.incrementAndGet();
        RulesBundle bundle = new RulesBundle(version, Instant.now(), deployedBy, scripts);

        logger.info("Deploying rules bundle v{} — {} script(s) by [{}]",
                version, scripts.size(), deployedBy);

        // 1. Aplicar localmente
        applyBundleLocally(bundle);

        // 2. Persistir em disco
        persistToDisk(bundle);

        // 3. Publicar no cluster (se habilitado)
        if (distributedRulesMap != null) {
            try {
                distributedRulesMap.put(RULES_MAP_KEY, bundle);
                logger.info("Rules bundle v{} published to cluster DistributedMap", version);
            } catch (Exception ex) {
                logger.warn("Failed to publish rules bundle v{} to cluster — local-only", version, ex);
            }
        }

        return bundle;
    }

    /**
     * @return o bundle ativo, ou null se nenhum deploy foi realizado
     */
    public RulesBundle getActiveBundle() {
        return activeBundle.get();
    }

    /**
     * Aplica um bundle localmente: materializa scripts em diretório temporário,
     * cria novo GSE e faz swap atômico em todos os EndpointWrappers.
     */
    private void applyBundleLocally(RulesBundle bundle) {
        try {
            // 1. Materializar scripts em diretório temporário
            Path tempDir = Files.createTempDirectory("ngate-rules-v" + bundle.version() + "-");
            for (Map.Entry<String, byte[]> entry : bundle.scripts().entrySet()) {
                Path scriptPath = tempDir.resolve(entry.getKey());
                Files.createDirectories(scriptPath.getParent());
                Files.write(scriptPath, entry.getValue());
                logger.debug("Materialized script: [{}] ({} bytes)", entry.getKey(), entry.getValue().length);
            }

            // 2. Criar novo GroovyScriptEngine
            GroovyScriptEngine newGse = new GroovyScriptEngine(tempDir.toAbsolutePath().toString());
            CompilerConfiguration config = newGse.getConfig();
            config.setRecompileGroovySource(false); // Sem recompilação — bundle é estático até próximo deploy
            logger.info("New GroovyScriptEngine created from [{}]", tempDir);

            // 3. Swap atômico em todos os wrappers ativos
            for (EndpointWrapper wrapper : endpointManager.getActiveWrappers()) {
                wrapper.swapGroovyEngine(newGse);
            }

            // 4. Atualizar referência do bundle ativo
            activeBundle.set(bundle);
            versionCounter.set(Math.max(versionCounter.get(), bundle.version()));

            logger.info("Rules bundle v{} applied successfully — {} script(s)", bundle.version(), bundle.scripts().size());
        } catch (Exception ex) {
            logger.error("Failed to apply rules bundle v{}", bundle.version(), ex);
            throw new RuntimeException("Failed to apply rules bundle", ex);
        }
    }

    /**
     * Persiste o bundle em disco para sobreviver restarts.
     */
    private void persistToDisk(RulesBundle bundle) {
        try {
            Path persistPath = Path.of(BUNDLE_PERSIST_FILE);
            Files.createDirectories(persistPath.getParent());
            try (ObjectOutputStream oos = new ObjectOutputStream(
                    new FileOutputStream(persistPath.toFile()))) {
                oos.writeObject(bundle);
            }
            logger.info("Rules bundle v{} persisted to [{}]", bundle.version(), persistPath);
        } catch (Exception ex) {
            logger.warn("Failed to persist rules bundle to disk", ex);
        }
    }

    /**
     * Carrega bundle do disco (se existir).
     */
    private RulesBundle loadFromDisk() {
        Path persistPath = Path.of(BUNDLE_PERSIST_FILE);
        if (!Files.exists(persistPath)) {
            return null;
        }
        try (ObjectInputStream ois = new ObjectInputStream(
                new FileInputStream(persistPath.toFile()))) {
            RulesBundle bundle = (RulesBundle) ois.readObject();
            logger.info("Rules bundle v{} loaded from disk [{}]", bundle.version(), persistPath);
            return bundle;
        } catch (Exception ex) {
            logger.warn("Failed to load rules bundle from disk — ignoring", ex);
            return null;
        }
    }
}
