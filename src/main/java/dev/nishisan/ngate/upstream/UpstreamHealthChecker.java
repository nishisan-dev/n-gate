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
package dev.nishisan.ngate.upstream;

import dev.nishisan.ngate.configuration.UpstreamHealthCheckConfiguration;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executa health checks ativos nos membros dos upstream pools.
 * <p>
 * Para cada pool com {@code healthCheck.enabled=true}, agenda um probe
 * periódico usando um OkHttp client dedicado com timeout curto.
 * Virtual Threads (Java 21) são usados para eficiência — um thread por probe.
 * <p>
 * Ciclo de vida:
 * <ul>
 *   <li>{@link #start(UpstreamPoolManager)} — agenda os probes</li>
 *   <li>{@link #stop()} — cancela probes e faz shutdown do scheduler</li>
 * </ul>
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-10
 */
public class UpstreamHealthChecker {

    private static final Logger logger = LogManager.getLogger(UpstreamHealthChecker.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    private final List<ScheduledFuture<?>> scheduledProbes = new ArrayList<>();

    /**
     * Inicia os health checks para todos os pools que têm healthCheck habilitado.
     *
     * @param poolManager o manager com os pools já inicializados
     */
    public void start(UpstreamPoolManager poolManager) {
        if (running.compareAndSet(false, true)) {
            this.scheduler = Executors.newScheduledThreadPool(0, Thread.ofVirtual().factory());

            poolManager.getAllPools().forEach((backendName, pool) -> {
                UpstreamHealthCheckConfiguration hcConfig = pool.getHealthCheckConfig();
                if (hcConfig == null || !hcConfig.isEnabled()) {
                    logger.debug("Health check disabled for pool '{}'", backendName);
                    return;
                }

                // OkHttp client dedicado para probes (timeout curto, sem interceptors)
                OkHttpClient probeClient = new OkHttpClient.Builder()
                        .connectTimeout(hcConfig.getTimeoutMs(), TimeUnit.MILLISECONDS)
                        .readTimeout(hcConfig.getTimeoutMs(), TimeUnit.MILLISECONDS)
                        .callTimeout(hcConfig.getTimeoutMs(), TimeUnit.MILLISECONDS)
                        .retryOnConnectionFailure(false)
                        .followRedirects(false)
                        .build();

                // Agenda probe periódico para cada membro
                for (UpstreamMemberState member : pool.getAllMembers()) {
                    ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(
                            () -> probeMember(probeClient, hcConfig, member, backendName),
                            hcConfig.getIntervalSeconds(), // initial delay = interval
                            hcConfig.getIntervalSeconds(),
                            TimeUnit.SECONDS);
                    scheduledProbes.add(future);

                    logger.info("Health check scheduled for pool '{}' member {}: interval={}s, path={}",
                            backendName, member.getUrl(),
                            hcConfig.getIntervalSeconds(), hcConfig.getPath());
                }
            });

            logger.info("UpstreamHealthChecker started: {} probe(s) scheduled", scheduledProbes.size());
        }
    }

    /**
     * Para todos os health checks e faz shutdown do scheduler.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            scheduledProbes.forEach(f -> f.cancel(false));
            scheduledProbes.clear();

            if (scheduler != null) {
                scheduler.shutdown();
                try {
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        scheduler.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    scheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            logger.info("UpstreamHealthChecker stopped");
        }
    }

    /**
     * Executa um probe de saúde em um membro.
     * Package-private para testes.
     */
    void probeMember(OkHttpClient client, UpstreamHealthCheckConfiguration hcConfig,
                     UpstreamMemberState member, String backendName) {
        String probeUrl = member.getUrl() + hcConfig.getPath();
        boolean wasHealthy = member.isHealthy();

        try {
            Request request = new Request.Builder()
                    .url(probeUrl)
                    .get()
                    .build();

            try (Response response = client.newCall(request).execute()) {
                int code = response.code();
                if (code >= 200 && code < 300) {
                    int successes = member.recordSuccess();
                    if (!wasHealthy && successes >= hcConfig.getHealthyThreshold()) {
                        member.markHealthy();
                        logger.info("⬆ Pool '{}' member {} transitioned DOWN → UP " +
                                        "(after {} consecutive successes)",
                                backendName, member.getUrl(), successes);
                    }
                } else {
                    handleProbeFailure(hcConfig, member, backendName, wasHealthy,
                            "HTTP " + code);
                }
            }
        } catch (IOException e) {
            handleProbeFailure(hcConfig, member, backendName, wasHealthy,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (Exception e) {
            handleProbeFailure(hcConfig, member, backendName, wasHealthy,
                    "Unexpected: " + e.getMessage());
        }
    }

    private void handleProbeFailure(UpstreamHealthCheckConfiguration hcConfig,
                                    UpstreamMemberState member, String backendName,
                                    boolean wasHealthy, String reason) {
        int failures = member.recordFailure();
        if (wasHealthy && failures >= hcConfig.getUnhealthyThreshold()) {
            member.markUnhealthy();
            logger.warn("⬇ Pool '{}' member {} transitioned UP → DOWN " +
                            "(after {} consecutive failures, reason: {})",
                    backendName, member.getUrl(), failures, reason);
        } else {
            logger.debug("Pool '{}' member {} probe failed ({}/{}): {}",
                    backendName, member.getUrl(), failures,
                    hcConfig.getUnhealthyThreshold(), reason);
        }
    }

    public boolean isRunning() {
        return running.get();
    }
}
