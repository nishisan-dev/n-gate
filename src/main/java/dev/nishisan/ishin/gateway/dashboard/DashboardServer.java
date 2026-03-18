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
package dev.nishisan.ishin.gateway.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nishisan.ishin.gateway.configuration.DashboardConfiguration;
import dev.nishisan.ishin.gateway.configuration.ServerConfiguration;
import dev.nishisan.ishin.gateway.dashboard.api.DashboardApiRoutes;
import dev.nishisan.ishin.gateway.dashboard.collector.MetricsCollectorService;
import dev.nishisan.ishin.gateway.dashboard.storage.DashboardStorageService;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Stream;

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
    private static final String CLASSPATH_PREFIX = "/static/dashboard";

    private final DashboardConfiguration config;
    private final DashboardIpFilter ipFilter;
    private final DashboardApiRoutes apiRoutes;
    private final MetricsCollectorService metricsCollector;
    private final DashboardStorageService storage;

    private Javalin app;
    private ScheduledExecutorService scheduler;
    private Path extractedDashboardDir;

    // WebSocket: clientes conectados para push de métricas
    private final Set<WsContext> wsClients = ConcurrentHashMap.newKeySet();

    public DashboardServer(DashboardConfiguration config,
                           ServerConfiguration serverConfig,
                           MeterRegistry meterRegistry,
                           dev.nishisan.ishin.gateway.tunnel.TunnelService tunnelService) {
        this.config = config;
        this.ipFilter = new DashboardIpFilter(config.getAllowedIps());
        this.storage = new DashboardStorageService(config.getStorage());
        this.metricsCollector = new MetricsCollectorService(meterRegistry, storage);
        this.apiRoutes = new DashboardApiRoutes(metricsCollector, storage, serverConfig, config, tunnelService);
    }

    /**
     * Acesso ao storage para criação do event bridge.
     */
    public DashboardStorageService getStorage() {
        return storage;
    }

    /**
     * Inicia o servidor do dashboard.
     */
    public void start() {
        logger.info("Iniciando Dashboard de Observabilidade na porta {}...", config.getPort());

        // Extrai assets do SPA do classpath para diretório temporário no filesystem.
        // Necessário porque o Jetty ResourceHandler dentro de um Spring Boot fat JAR
        // trata paths em BOOT-INF/classes como "alias" e recusa servi-los.
        extractedDashboardDir = extractDashboardAssets();
        final String dashboardPath = extractedDashboardDir.toAbsolutePath().toString();
        logger.info("Dashboard SPA assets extraídos para: {}", dashboardPath);

        app = Javalin.create(javalinConfig -> {
            javalinConfig.startup.showJavalinBanner = false;
            javalinConfig.concurrency.useVirtualThreads = true;

            // Serve React SPA do filesystem (evita issue de alias do Jetty com nested JARs)
            javalinConfig.staticFiles.add(staticFileConfig -> {
                staticFileConfig.directory = dashboardPath;
                staticFileConfig.hostedPath = "/";
                staticFileConfig.location = Location.EXTERNAL;
            });

            // SPA Fallback (React Router) — rotas sem extensão retornam index.html
            javalinConfig.spaRoot.addFile("/", dashboardPath + "/index.html", Location.EXTERNAL);

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

        // Limpa diretório temporário de assets extraídos
        if (extractedDashboardDir != null) {
            try {
                deleteRecursively(extractedDashboardDir);
                logger.info("Dashboard temp dir removido: {}", extractedDashboardDir);
            } catch (IOException e) {
                logger.warn("Falha ao remover dashboard temp dir: {}", e.getMessage());
            }
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

    // ─── Asset Extraction (Spring Boot fat JAR workaround) ───────────────

    /**
     * Extrai os assets do React SPA do classpath para um diretório temporário.
     * <p>
     * Dentro de um Spring Boot fat JAR, os resources ficam em BOOT-INF/classes/
     * e o Jetty ResourceHandler trata esses paths como "aliases", recusando
     * servi-los por segurança. Extrair para o filesystem resolve o problema.
     *
     * @return Path do diretório temporário contendo os assets extraídos
     */
    private Path extractDashboardAssets() {
        try {
            Path tempDir = Files.createTempDirectory("ishin-dashboard-");
            logger.debug("Extraindo dashboard SPA de classpath {} para {}", CLASSPATH_PREFIX, tempDir);

            // Lista de arquivos conhecidos do SPA build output
            // Usar resource listing via classpath URL
            URL resourceUrl = getClass().getResource(CLASSPATH_PREFIX);
            if (resourceUrl == null) {
                throw new IOException("Dashboard SPA não encontrado no classpath: " + CLASSPATH_PREFIX);
            }

            // Extrai usando protocolo-agnóstico (funciona tanto em fat JAR quanto dev)
            String protocol = resourceUrl.getProtocol();
            logger.debug("Dashboard resource protocol: {}, URL: {}", protocol, resourceUrl);

            if ("jar".equals(protocol) || "nested".equals(protocol)) {
                // Dentro de um JAR: lê via classloader e copia manualmente
                extractKnownAssets(tempDir);
            } else {
                // Desenvolvimento local: copia do filesystem
                Path sourcePath = Path.of(resourceUrl.toURI());
                copyDirectory(sourcePath, tempDir);
            }

            return tempDir;
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException("Falha ao extrair dashboard SPA do classpath", e);
        }
    }

    /**
     * Extrai assets conhecidos do SPA via classloader (seguro para fat JARs).
     */
    private void extractKnownAssets(Path targetDir) throws IOException {
        // index.html
        extractResource(CLASSPATH_PREFIX + "/index.html", targetDir.resolve("index.html"));

        // Lista assets dinamicamente via classloader
        Path assetsDir = targetDir.resolve("assets");
        Files.createDirectories(assetsDir);

        // Tenta listar o diretório de assets via classpath
        URL assetsUrl = getClass().getResource(CLASSPATH_PREFIX + "/assets");
        if (assetsUrl != null) {
            // Para JARs, usa o approach de enumerar resources conhecidos
            // Como o Vite gera hashes nos nomes, precisamos ler o index.html
            // para descobrir os nomes dos assets
            String indexContent = new String(
                    getClass().getResourceAsStream(CLASSPATH_PREFIX + "/index.html").readAllBytes());

            // Extrai nomes de assets referenciados no index.html
            // Pattern: src="/assets/xxx" ou href="/assets/xxx"
            java.util.regex.Pattern assetPattern =
                    java.util.regex.Pattern.compile("(?:src|href)=\"/assets/([^\"]+)\"");
            java.util.regex.Matcher matcher = assetPattern.matcher(indexContent);

            while (matcher.find()) {
                String assetName = matcher.group(1);
                extractResource(CLASSPATH_PREFIX + "/assets/" + assetName,
                        assetsDir.resolve(assetName));
            }
        }

        // Tenta extrair favicon se existir
        try {
            extractResource(CLASSPATH_PREFIX + "/favicon.svg", targetDir.resolve("favicon.svg"));
        } catch (IOException ignored) {
            // favicon opcional
        }

        logger.info("Dashboard SPA: {} arquivos extraídos do classpath",
                countFiles(targetDir));
    }

    /**
     * Extrai um único resource do classpath para o filesystem.
     */
    private void extractResource(String classpathPath, Path target) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(classpathPath)) {
            if (is == null) {
                throw new IOException("Resource não encontrado: " + classpathPath);
            }
            Files.createDirectories(target.getParent());
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
            logger.debug("Dashboard: extraído {} → {}", classpathPath, target);
        }
    }

    /**
     * Copia um diretório recursivamente (para desenvolvimento local).
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            walk.forEach(src -> {
                try {
                    Path dest = target.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Falha ao copiar: " + src, e);
                }
            });
        }
    }

    /**
     * Remove um diretório recursivamente.
     */
    private void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            logger.debug("Falha ao deletar {}: {}", p, e.getMessage());
                        }
                    });
        }
    }

    /**
     * Conta arquivos em um diretório recursivamente.
     */
    private long countFiles(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return -1;
        }
    }
}
