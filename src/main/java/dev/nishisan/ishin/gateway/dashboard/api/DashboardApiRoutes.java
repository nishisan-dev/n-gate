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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nishisan.ishin.gateway.configuration.DashboardConfiguration;
import dev.nishisan.ishin.gateway.configuration.ServerConfiguration;
import dev.nishisan.ishin.gateway.dashboard.api.TunnelRuntimeSnapshot;
import dev.nishisan.ishin.gateway.dashboard.api.TunnelRuntimeSnapshotFactory;
import dev.nishisan.ishin.gateway.dashboard.collector.MetricsCollectorService;
import dev.nishisan.ishin.gateway.dashboard.storage.DashboardStorageService;
import dev.nishisan.ishin.gateway.tunnel.TunnelService;
import io.javalin.router.JavalinDefaultRoutingApi;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.management.ManagementFactory;
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
    private final TunnelService tunnelService; // nullable
    private final OkHttpClient httpClient;

    public DashboardApiRoutes(MetricsCollectorService metricsCollector,
                              DashboardStorageService storage,
                              ServerConfiguration serverConfig,
                              DashboardConfiguration dashboardConfig,
                              TunnelService tunnelService) {
        this.metricsCollector = metricsCollector;
        this.storage = storage;
        this.serverConfig = serverConfig;
        this.dashboardConfig = dashboardConfig;
        this.tunnelService = tunnelService;
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
            String tierParam = ctx.queryParam("tier"); // opcional: forçar um tier

            if (name == null || name.isBlank()) {
                ctx.status(400).json(Map.of("error", "Parâmetro 'name' é obrigatório"));
                return;
            }

            Instant from = fromStr != null ? Instant.parse(fromStr) : Instant.now().minusSeconds(3600);
            Instant to = toStr != null ? Instant.parse(toStr) : Instant.now();

            List<DashboardStorageService.SeriesRecord> records;
            String resolvedTier;

            if (tierParam != null && !tierParam.isBlank()) {
                resolvedTier = tierParam;
            } else {
                resolvedTier = DashboardStorageService.resolveTier(
                        java.time.Duration.between(from, to));
            }

            // Busca direta
            records = storage.queryMetricsWithTier(name, resolvedTier, from, to);

            // Se vazio, pode ser um Timer armazenado como .mean/.max/.count
            // Tenta pelo sufixo .mean que contém os valores médios
            if (records.isEmpty()) {
                records = storage.queryMetricsWithTier(name + ".mean", resolvedTier, from, to);
            }

            // Retorna com metadata do tier usado
            ctx.json(Map.of(
                    "tier", resolvedTier,
                    "points", records.size(),
                    "data", records
            ));
        });

        routes.get("/api/v1/metrics/names", ctx -> {
            ctx.json(storage.listMetricNames());
        });

        routes.get("/api/v1/metrics/tiers", ctx -> {
            ctx.json(List.of(
                    Map.of("name", "raw", "retention", "6h", "interval", "15s"),
                    Map.of("name", "5min", "retention", "7d", "interval", "5min"),
                    Map.of("name", "10min", "retention", "30d", "interval", "10min"),
                    Map.of("name", "1hour", "retention", "365d", "interval", "1h")
            ));
        });

        // ─── Topologia ──────────────────────────────────────────
        routes.get("/api/v1/topology", ctx -> {
            if (serverConfig.isTunnelMode()) {
                ctx.json(buildTunnelTopology());
            } else {
                ctx.json(buildTopology());
            }
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

        // ─── Tunnel Runtime ──────────────────────────────────────
        routes.get("/api/v1/tunnel/runtime", ctx -> {
            if (!serverConfig.isTunnelMode()) {
                ctx.status(404).json(Map.of("error", "Disponível apenas em tunnel mode"));
                return;
            }
            ctx.json(TunnelRuntimeSnapshotFactory.create(tunnelService));
        });

        logger.info("Dashboard API routes registradas: /api/v1/*");
    }

    // ─── Topology Builder ───────────────────────────────────────────────

    private Map<String, Object> buildTopology() {
        Map<String, Object> topology = new LinkedHashMap<>();

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        // Nó central: Ishin Gateway
        nodes.add(Map.of(
                "id", "ishin-gateway",
                "type", "gateway",
                "label", "ishin-gateway",
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
                            "target", "ishin-gateway",
                            "type", "inbound"
                    ));

                    // ─── Context & Script nodes ─────────────────────────
                    if (listenerConfig.getUrlContexts() != null) {
                        listenerConfig.getUrlContexts().forEach((ctxName, urlCtx) -> {
                            String contextId = "context:" + listenerName + ":" + ctxName;

                            // Nó de contexto
                            Map<String, Object> contextNode = new LinkedHashMap<>();
                            contextNode.put("id", contextId);
                            contextNode.put("type", "context");
                            contextNode.put("label", ctxName);
                            contextNode.put("listener", listenerName);
                            contextNode.put("contextPath", urlCtx.getContext());
                            contextNode.put("method", urlCtx.getMethod());
                            contextNode.put("ruleMapping", urlCtx.getRuleMapping());
                            contextNode.put("secured", urlCtx.getSecured());
                            nodes.add(contextNode);

                            // Edge: listener → context
                            Map<String, Object> listenerToCtxEdge = new LinkedHashMap<>();
                            listenerToCtxEdge.put("source", listenerId);
                            listenerToCtxEdge.put("target", contextId);
                            listenerToCtxEdge.put("type", "context");
                            edges.add(listenerToCtxEdge);

                            // Edge: context → ishin-gateway
                            Map<String, Object> ctxToGatewayEdge = new LinkedHashMap<>();
                            ctxToGatewayEdge.put("source", contextId);
                            ctxToGatewayEdge.put("target", "ishin-gateway");
                            ctxToGatewayEdge.put("type", "inbound-context");
                            edges.add(ctxToGatewayEdge);

                            // Nó de script (quando ruleMapping está definido)
                            if (urlCtx.getRuleMapping() != null && !urlCtx.getRuleMapping().trim().isEmpty()) {
                                String scriptName = urlCtx.getRuleMapping().trim();
                                String scriptId = "script:" + listenerName + ":" + ctxName + ":" + scriptName;

                                Map<String, Object> scriptNode = new LinkedHashMap<>();
                                scriptNode.put("id", scriptId);
                                scriptNode.put("type", "script");
                                scriptNode.put("label", scriptName);
                                scriptNode.put("script", scriptName);
                                scriptNode.put("context", contextId);
                                nodes.add(scriptNode);

                                // Edge: context → script
                                Map<String, Object> ctxToScriptEdge = new LinkedHashMap<>();
                                ctxToScriptEdge.put("source", contextId);
                                ctxToScriptEdge.put("target", scriptId);
                                ctxToScriptEdge.put("type", "script-exec");
                                edges.add(ctxToScriptEdge);
                            }
                        });
                    }

                    if (listenerConfig.getDefaultBackend() != null) {
                        String backendId = "backend:" + listenerConfig.getDefaultBackend();
                        Map<String, Object> edge = new LinkedHashMap<>();
                        edge.put("source", "ishin-gateway");
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

    /**
     * Constrói topologia L4 para tunnel mode a partir do runtime vivo.
     */
    private Map<String, Object> buildTunnelTopology() {
        Map<String, Object> topology = new LinkedHashMap<>();

        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        // Nó central: Tunnel
        nodes.add(Map.of(
                "id", "ishin-tunnel",
                "type", "tunnel",
                "label", "ishin-tunnel",
                "mode", "tunnel"
        ));

        TunnelRuntimeSnapshot snapshot = TunnelRuntimeSnapshotFactory.create(tunnelService);

        for (TunnelRuntimeSnapshot.VirtualPortSnapshot vpSnapshot : snapshot.virtualPorts()) {
            String vpId = "vport:" + vpSnapshot.virtualPort();

            // Nó de porta virtual
            Map<String, Object> vpNode = new LinkedHashMap<>();
            vpNode.put("id", vpId);
            vpNode.put("type", "virtual-port");
            vpNode.put("label", "vPort:" + vpSnapshot.virtualPort());
            vpNode.put("port", vpSnapshot.virtualPort());
            vpNode.put("listenerOpen", vpSnapshot.listenerOpen());
            vpNode.put("activeMembers", vpSnapshot.activeMembers());
            vpNode.put("standbyMembers", vpSnapshot.standbyMembers());
            vpNode.put("drainingMembers", vpSnapshot.drainingMembers());
            nodes.add(vpNode);

            // Edge: virtual-port → tunnel
            edges.add(Map.of(
                    "source", vpId,
                    "target", "ishin-tunnel",
                    "type", "tunnel-link"
            ));

            // Nós de members
            for (TunnelRuntimeSnapshot.TunnelMemberSnapshot memberSnapshot : vpSnapshot.members()) {
                String memberId = "member:" + vpSnapshot.virtualPort() + ":" + memberSnapshot.backendKey();

                Map<String, Object> memberNode = new LinkedHashMap<>();
                memberNode.put("id", memberId);
                memberNode.put("type", "tunnel-member");
                memberNode.put("label", memberSnapshot.backendKey());
                memberNode.put("nodeId", memberSnapshot.nodeId());
                memberNode.put("host", memberSnapshot.host());
                memberNode.put("realPort", memberSnapshot.realPort());
                memberNode.put("status", memberSnapshot.status());
                memberNode.put("weight", memberSnapshot.weight());
                memberNode.put("activeConnections", memberSnapshot.activeConnections());
                memberNode.put("keepaliveAgeSeconds", memberSnapshot.keepaliveAgeSeconds());
                nodes.add(memberNode);

                // Edge: tunnel → member
                edges.add(Map.of(
                        "source", "ishin-tunnel",
                        "target", memberId,
                        "type", "tunnel-link"
                ));
            }
        }

        // Cluster info
        if (serverConfig.getCluster() != null && serverConfig.getCluster().isEnabled()) {
            Map<String, Object> clusterInfo = new LinkedHashMap<>();
            clusterInfo.put("enabled", true);
            clusterInfo.put("nodeId", serverConfig.getCluster().getNodeId());
            clusterInfo.put("clusterName", serverConfig.getCluster().getClusterName());
            topology.put("cluster", clusterInfo);
        }

        topology.put("nodes", nodes);
        topology.put("edges", edges);
        topology.put("mode", "tunnel");

        return topology;
    }

    // ─── Health Builder ─────────────────────────────────────────────────

    private Map<String, Object> buildHealthInfo() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("timestamp", Instant.now().toString());
        health.put("mode", serverConfig.getMode());

        // Versão do JAR (vem do MANIFEST.MF gerado pelo Spring Boot)
        String version = getClass().getPackage().getImplementationVersion();
        health.put("version", version != null ? version : "dev");

        // Uptime em milissegundos
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        health.put("uptimeMs", uptimeMs);
        health.put("uptime", formatUptime(uptimeMs));

        int listenerCount = serverConfig.getEndpoints().values().stream()
                .mapToInt(ep -> ep.getListeners() != null ? ep.getListeners().size() : 0)
                .sum();
        health.put("listeners", listenerCount);

        int backendCount = serverConfig.getEndpoints().values().stream()
                .mapToInt(ep -> ep.getBackends() != null ? ep.getBackends().size() : 0)
                .sum();
        health.put("backends", backendCount);

        // Campos específicos do tunnel mode
        if (serverConfig.isTunnelMode() && tunnelService != null) {
            TunnelRuntimeSnapshot snapshot = TunnelRuntimeSnapshotFactory.create(tunnelService);
            health.put("virtualListeners", snapshot.listeners());
            health.put("tunnelGroups", snapshot.groups());
            health.put("tunnelMembers", snapshot.members());
            health.put("activeConnections", snapshot.activeConnections());
            health.put("dashboardCapabilities", List.of("metrics", "topology", "events"));
        } else {
            health.put("dashboardCapabilities", List.of("metrics", "topology", "events", "traces"));
        }

        return health;
    }

    private static String formatUptime(long ms) {
        long seconds = ms / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (days > 0) return String.format("%dd %dh %dm", days, hours, minutes);
        if (hours > 0) return String.format("%dh %dm %ds", hours, minutes, secs);
        if (minutes > 0) return String.format("%dm %ds", minutes, secs);
        return String.format("%ds", secs);
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
