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
package dev.nishisan.ngate.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nishisan.ngate.configuration.DashboardConfiguration;
import dev.nishisan.ngate.configuration.ServerConfiguration;
import dev.nishisan.ngate.dashboard.api.DashboardApiRoutes;
import dev.nishisan.ngate.dashboard.collector.MetricsCollectorService;
import dev.nishisan.ngate.dashboard.storage.DashboardStorageService;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Servidor Javalin dedicado ao dashboard de observabilidade.
 * <p>
 * Executa em uma porta separada ({@code dashboard.port}, default 9200),
 * com IP filtering, servindo o React SPA e API REST.
 * <p>
 * Lifecycle: chamado pelo {@link DashboardService} Spring component
 * após inicialização dos listeners de proxy.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
public class DashboardServer {

    private static final Logger logger = LogManager.getLogger(DashboardServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DashboardConfiguration config;
    private final DashboardIpFilter ipFilter;
    private final DashboardApiRoutes apiRoutes;
    private final MetricsCollectorService metricsCollector;
    private final DashboardStorageService storage;

    private Javalin app;
    private ScheduledExecutorService scheduler;

    // WebSocket: clientes conectados para push de métricas
    private final Set<WsContext> wsClients = ConcurrentHashMap.newKeySet();

    public DashboardServer(DashboardConfiguration config,
                           ServerConfiguration serverConfig,
                           MeterRegistry meterRegistry) {
        this.config = config;
        this.ipFilter = new DashboardIpFilter(config.getAllowedIps());
        this.storage = new DashboardStorageService(config.getStorage());
        this.metricsCollector = new MetricsCollectorService(meterRegistry, storage);
        this.apiRoutes = new DashboardApiRoutes(metricsCollector, storage, serverConfig, config);
    }

    /**
     * Inicia o servidor do dashboard.
     */
    public void start() {
        logger.info("Iniciando Dashboard de Observabilidade na porta {}...", config.getPort());

        app = Javalin.create(javalinConfig -> {
            javalinConfig.startup.showJavalinBanner = false;
            javalinConfig.concurrency.useVirtualThreads = true;

            // Serve React SPA de classpath (build output em static/dashboard/)
            javalinConfig.staticFiles.add(staticFileConfig -> {
                staticFileConfig.directory = "/static/dashboard";
                staticFileConfig.hostedPath = "/";
                staticFileConfig.location = Location.CLASSPATH;
            });

            // CORS para desenvolvimento local
            javalinConfig.bundledPlugins.enableCors(cors -> {
                cors.addRule(rule -> {
                    rule.anyHost();
                });
            });

            // ─── Todas as rotas registradas via config.routes (Javalin 7) ───

            // IP Filter (antes de tudo)
            javalinConfig.routes.before("/*", ctx -> {
                if (!ipFilter.isAllowed(ctx.ip())) {
                    logger.warn("Dashboard: acesso negado para IP {}", ctx.ip());
                    ctx.status(403).result("Forbidden: IP not allowed");
                    ctx.skipRemainingHandlers();
                }
            });

            // API Routes
            apiRoutes.register(javalinConfig.routes);

            // WebSocket: push de métricas tempo real
            javalinConfig.routes.ws("/ws/metrics", ws -> {
                ws.onConnect(ctx -> {
                    logger.info("Dashboard WebSocket: cliente conectado");
                    wsClients.add(ctx);
                });
                ws.onClose(ctx -> {
                    wsClients.remove(ctx);
                    logger.info("Dashboard WebSocket: cliente desconectado");
                });
                ws.onError(ctx -> {
                    wsClients.remove(ctx);
                });
            });

            // SPA Fallback (React Router) — qualquer rota não-API retorna index.html
            javalinConfig.routes.get("/*", ctx -> {
                String path = ctx.path();
                if (!path.startsWith("/api/") && !path.startsWith("/ws/")) {
                    InputStream indexStream = getClass().getResourceAsStream("/static/dashboard/index.html");
                    if (indexStream != null) {
                        ctx.contentType("text/html");
                        ctx.result(indexStream);
                    } else {
                        ctx.status(404).result("Dashboard UI not built. Run 'npm run build' in n-gate-ui/");
                    }
                }
            });
        });

        // Start Javalin
        app.start(config.getBindAddress(), config.getPort());
        logger.info("✅ Dashboard de Observabilidade iniciado em http://{}:{}", config.getBindAddress(), config.getPort());

        // ─── Schedulers ─────────────────────────────────────────
        startSchedulers();
    }

    /**
     * Para o servidor do dashboard gracefully.
     */
    public void stop() {
        logger.info("Parando Dashboard de Observabilidade...");

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

        if (app != null) {
            app.stop();
        }

        if (storage != null) {
            storage.shutdown();
        }

        logger.info("Dashboard de Observabilidade parado");
    }

    // ─── Schedulers ─────────────────────────────────────────────────────

    private void startSchedulers() {
        scheduler = Executors.newScheduledThreadPool(4, r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            t.setName("dashboard-scheduler");
            return t;
        });

        // Coleta periódica de métricas → H2 (tier raw)
        int scrapeInterval = config.getStorage().getScrapeIntervalSeconds();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                metricsCollector.collect();
            } catch (Exception e) {
                logger.warn("Dashboard collector falhou: {}", e.getMessage());
            }
        }, scrapeInterval, scrapeInterval, TimeUnit.SECONDS);

        // Push de métricas via WebSocket a cada 5s
        scheduler.scheduleAtFixedRate(this::pushMetricsToWebSocket, 5, 5, TimeUnit.SECONDS);

        // ─── RRD Consolidation ──────────────────────────────────
        // raw → 5min (a cada 5 minutos)
        scheduler.scheduleAtFixedRate(() -> {
            try {
                storage.consolidate(
                        DashboardStorageService.Tier.RAW,
                        DashboardStorageService.Tier.FIVE_MIN, 5);
            } catch (Exception e) {
                logger.warn("RRD consolidação raw→5min falhou: {}", e.getMessage());
            }
        }, 5, 5, TimeUnit.MINUTES);

        // 5min → 10min (a cada 10 minutos)
        scheduler.scheduleAtFixedRate(() -> {
            try {
                storage.consolidate(
                        DashboardStorageService.Tier.FIVE_MIN,
                        DashboardStorageService.Tier.TEN_MIN, 10);
            } catch (Exception e) {
                logger.warn("RRD consolidação 5min→10min falhou: {}", e.getMessage());
            }
        }, 10, 10, TimeUnit.MINUTES);

        // 10min → 1hour (a cada 1 hora)
        scheduler.scheduleAtFixedRate(() -> {
            try {
                storage.consolidate(
                        DashboardStorageService.Tier.TEN_MIN,
                        DashboardStorageService.Tier.ONE_HOUR, 60);
            } catch (Exception e) {
                logger.warn("RRD consolidação 10min→1hour falhou: {}", e.getMessage());
            }
        }, 60, 60, TimeUnit.MINUTES);

        // Purge de dados expirados per-tier a cada 1h
        scheduler.scheduleAtFixedRate(() -> {
            try {
                storage.purgeExpired();
            } catch (Exception e) {
                logger.warn("Dashboard purge falhou: {}", e.getMessage());
            }
        }, 1, 1, TimeUnit.HOURS);

        logger.info("Dashboard schedulers: scrape={}s, ws-push=5s, consolidação=5min/10min/1h, purge=1h",
                scrapeInterval);
    }

    private void pushMetricsToWebSocket() {
        if (wsClients.isEmpty()) return;

        try {
            Map<String, Object> metrics = metricsCollector.getCurrentMetrics();
            String json = MAPPER.writeValueAsString(metrics);

            wsClients.removeIf(ctx -> {
                try {
                    ctx.send(json);
                    return false;
                } catch (Exception e) {
                    logger.debug("Dashboard WebSocket: removendo cliente morto: {}", e.getMessage());
                    return true;
                }
            });
        } catch (Exception e) {
            logger.warn("Dashboard WebSocket push falhou: {}", e.getMessage());
        }
    }
}
