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
package dev.nishisan.ngate.dashboard.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nishisan.ngate.configuration.DashboardConfiguration;
import dev.nishisan.ngate.configuration.ServerConfiguration;
import dev.nishisan.ngate.dashboard.collector.MetricsCollectorService;
import dev.nishisan.ngate.dashboard.storage.DashboardStorageService;
import io.javalin.router.JavalinDefaultRoutingApi;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Registra os endpoints REST da API do dashboard no Javalin dedicado.
 * <p>
 * Todos os endpoints são prefixados com {@code /api/v1/}.
 * Usa {@link JavalinDefaultRoutingApi} para compatibilidade com Javalin 7.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
public class DashboardApiRoutes {

    private static final Logger logger = LogManager.getLogger(DashboardApiRoutes.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MetricsCollectorService metricsCollector;
    private final DashboardStorageService storage;
    private final ServerConfiguration serverConfig;
    private final DashboardConfiguration dashboardConfig;
    private final OkHttpClient httpClient;

    public DashboardApiRoutes(MetricsCollectorService metricsCollector,
                              DashboardStorageService storage,
                              ServerConfiguration serverConfig,
                              DashboardConfiguration dashboardConfig) {
        this.metricsCollector = metricsCollector;
        this.storage = storage;
        this.serverConfig = serverConfig;
        this.dashboardConfig = dashboardConfig;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Registra todas as rotas da API via Javalin 7 routing API.
     */
    public void register(JavalinDefaultRoutingApi routes) {
        // ─── Métricas ───────────────────────────────────────────
        routes.get("/api/v1/metrics/current", ctx -> {
            ctx.json(metricsCollector.getCurrentMetrics());
        });

        routes.get("/api/v1/metrics/history", ctx -> {
            String name = ctx.queryParam("name");
            String fromStr = ctx.queryParam("from");
            String toStr = ctx.queryParam("to");

            if (name == null || name.isBlank()) {
                ctx.status(400).json(Map.of("error", "Parâmetro 'name' é obrigatório"));
                return;
            }

            Instant from = fromStr != null ? Instant.parse(fromStr) : Instant.now().minusSeconds(3600);
            Instant to = toStr != null ? Instant.parse(toStr) : Instant.now();

            var records = storage.queryMetrics(name, from, to);
            ctx.json(records);
        });

        routes.get("/api/v1/metrics/names", ctx -> {
            ctx.json(storage.listMetricNames());
        });

        // ─── Topologia ──────────────────────────────────────────
        routes.get("/api/v1/topology", ctx -> {
            ctx.json(buildTopology());
        });

        // ─── Traces (proxy Zipkin) ──────────────────────────────
        routes.get("/api/v1/traces", ctx -> {
            if (!dashboardConfig.getZipkin().isEnabled()) {
                ctx.status(503).json(Map.of("error", "Integração Zipkin desabilitada"));
                return;
            }
            String zipkinBase = dashboardConfig.getZipkin().getBaseUrl();
            String queryString = ctx.queryString() != null ? "?" + ctx.queryString() : "";
            String url = zipkinBase + "/api/v2/traces" + queryString;
            proxyZipkinRequest(ctx, url);
        });

        routes.get("/api/v1/traces/{traceId}", ctx -> {
            if (!dashboardConfig.getZipkin().isEnabled()) {
                ctx.status(503).json(Map.of("error", "Integração Zipkin desabilitada"));
                return;
            }
            String zipkinBase = dashboardConfig.getZipkin().getBaseUrl();
            String traceId = ctx.pathParam("traceId");
            String url = zipkinBase + "/api/v2/trace/" + traceId;
            proxyZipkinRequest(ctx, url);
        });

        // ─── Health ─────────────────────────────────────────────
        routes.get("/api/v1/health", ctx -> {
            ctx.json(buildHealthInfo());
        });

        // ─── Eventos ────────────────────────────────────────────
        routes.get("/api/v1/events", ctx -> {
            int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(100);
            ctx.json(storage.getRecentEvents(limit));
        });

        logger.info("Dashboard API routes registradas: /api/v1/*");
    }

    // ─── Topology Builder ───────────────────────────────────────────────

    private Map<String, Object> buildTopology() {
        Map<String, Object> topology = new LinkedHashMap<>();

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        // Nó central: n-gate
        nodes.add(Map.of(
                "id", "ngate",
                "type", "gateway",
                "label", "n-gate",
                "mode", serverConfig.getMode()
        ));

        // Listeners e Backends
        serverConfig.getEndpoints().forEach((epName, epConfig) -> {
            if (epConfig.getListeners() != null) {
                epConfig.getListeners().forEach((listenerName, listenerConfig) -> {
                    String listenerId = "listener:" + listenerName;
                    Map<String, Object> listenerNode = new LinkedHashMap<>();
                    listenerNode.put("id", listenerId);
                    listenerNode.put("type", "listener");
                    listenerNode.put("label", listenerName);
                    listenerNode.put("port", listenerConfig.getListenPort());
                    listenerNode.put("ssl", listenerConfig.getSsl());
                    listenerNode.put("secured", listenerConfig.getSecured());
                    nodes.add(listenerNode);

                    edges.add(Map.of(
                            "source", listenerId,
                            "target", "ngate",
                            "type", "inbound"
                    ));

                    if (listenerConfig.getDefaultBackend() != null) {
                        String backendId = "backend:" + listenerConfig.getDefaultBackend();
                        Map<String, Object> edge = new LinkedHashMap<>();
                        edge.put("source", "ngate");
                        edge.put("target", backendId);
                        edge.put("type", "upstream");
                        edge.put("listener", listenerName);
                        edges.add(edge);
                    }
                });
            }

            if (epConfig.getBackends() != null) {
                epConfig.getBackends().forEach((backendName, backendConfig) -> {
                    String backendId = "backend:" + backendName;
                    Map<String, Object> backendNode = new LinkedHashMap<>();
                    backendNode.put("id", backendId);
                    backendNode.put("type", "backend");
                    backendNode.put("label", backendName);
                    backendNode.put("members", backendConfig.getMembers() != null
                            ? backendConfig.getMembers().size() : 0);
                    backendNode.put("hasOauth", backendConfig.getOauthClientConfig() != null);
                    nodes.add(backendNode);
                });
            }
        });

        // Cluster info
        if (serverConfig.getCluster() != null && serverConfig.getCluster().isEnabled()) {
            Map<String, Object> clusterInfo = new LinkedHashMap<>();
            clusterInfo.put("enabled", true);
            clusterInfo.put("nodeId", serverConfig.getCluster().getNodeId());
            clusterInfo.put("clusterName", serverConfig.getCluster().getClusterName());
            topology.put("cluster", clusterInfo);
        }

        if (serverConfig.getCircuitBreaker() != null && serverConfig.getCircuitBreaker().isEnabled()) {
            topology.put("circuitBreaker", Map.of("enabled", true));
        }

        if (serverConfig.getRateLimiting() != null && serverConfig.getRateLimiting().isEnabled()) {
            topology.put("rateLimiting", Map.of("enabled", true));
        }

        topology.put("nodes", nodes);
        topology.put("edges", edges);
        topology.put("mode", serverConfig.getMode());

        return topology;
    }

    // ─── Health Builder ─────────────────────────────────────────────────

    private Map<String, Object> buildHealthInfo() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("timestamp", Instant.now().toString());
        health.put("mode", serverConfig.getMode());

        int listenerCount = serverConfig.getEndpoints().values().stream()
                .mapToInt(ep -> ep.getListeners() != null ? ep.getListeners().size() : 0)
                .sum();
        health.put("listeners", listenerCount);

        int backendCount = serverConfig.getEndpoints().values().stream()
                .mapToInt(ep -> ep.getBackends() != null ? ep.getBackends().size() : 0)
                .sum();
        health.put("backends", backendCount);

        return health;
    }

    // ─── Zipkin Proxy ───────────────────────────────────────────────────

    private void proxyZipkinRequest(io.javalin.http.Context ctx, String url) {
        try {
            Request req = new Request.Builder().url(url).build();
            try (Response resp = httpClient.newCall(req).execute()) {
                ctx.status(resp.code());
                ctx.contentType("application/json");
                if (resp.body() != null) {
                    ctx.result(resp.body().string());
                }
            }
        } catch (IOException e) {
            logger.warn("Falha ao consultar Zipkin em '{}': {}", url, e.getMessage());
            ctx.status(502).json(Map.of("error", "Zipkin indisponível: " + e.getMessage()));
        }
    }
}
