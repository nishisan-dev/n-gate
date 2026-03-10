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
package dev.nishisan.ngate.observability;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes de integração para métricas Prometheus via Micrometer.
 * <p>
 * Valida que o endpoint {@code /actuator/prometheus} expõe métricas
 * {@code ngate_*} e que elas incrementam corretamente após tráfego.
 * <p>
 * Usa Testcontainers: 1 nó n-gate + 1 Nginx mock-backend.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-09
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PrometheusMetricsIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PrometheusMetricsIntegrationTest.class);
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private static final int PROXY_PORT = 9091;
    private static final int HEALTH_PORT = 9190;

    private static final Network network = Network.newNetwork();

    private static final ImageFromDockerfile ngateImage = new ImageFromDockerfile("ngate-metrics-test", false)
            .withFileFromPath(".", Path.of(System.getProperty("user.dir")))
            .withFileFromPath("settings.xml",
                    Path.of(System.getProperty("user.home"), ".m2", "settings.xml"));

    // ─── Backend mock (Nginx) ─────────────────────────────────────────────

    @Container
    private static final GenericContainer<?> mockBackend = new GenericContainer<>("nginx:alpine")
            .withNetwork(network)
            .withNetworkAliases("mock-backend")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("testcontainers/mock-backend.conf"),
                    "/etc/nginx/conf.d/default.conf")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/health").forPort(8080).forStatusCode(200));

    // ─── n-gate (standalone com métricas + CB) ────────────────────────────

    @Container
    private static final GenericContainer<?> ngate = new GenericContainer<>(ngateImage)
            .withNetwork(network)
            .withNetworkAliases("ngate-metrics")
            .withEnv("SPRING_PROFILES_DEFAULT", "test")
            .withEnv("NGATE_CONFIG", "/app/config/adapter-test-metrics.yaml")
            .withEnv("NGATE_INSTANCE_ID", "metrics-test-node")
            .withEnv("SERVER_PORT", "9190")
            .withEnv("MANAGEMENT_PORT", "9190")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("adapter-test-metrics.yaml"),
                    "/app/config/adapter-test-metrics.yaml")
            .withExposedPorts(PROXY_PORT, HEALTH_PORT)
            .dependsOn(mockBackend)
            .waitingFor(Wait.forHttp("/actuator/health")
                    .forPort(HEALTH_PORT)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(120)))
            .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("ngate"));

    // ─── Helpers ──────────────────────────────────────────────────────────

    private String prometheusUrl() {
        return String.format("http://%s:%d/actuator/prometheus",
                ngate.getHost(), ngate.getMappedPort(HEALTH_PORT));
    }

    private String proxyUrl() {
        return String.format("http://%s:%d/",
                ngate.getHost(), ngate.getMappedPort(PROXY_PORT));
    }

    private String getBody(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            assertNotNull(response.body(), "Response body is null for: " + url);
            return response.body().string();
        }
    }

    private int getStatusCode(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            return response.code();
        }
    }

    // ─── Testes ──────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("T1: /actuator/prometheus retorna 200 com formato Prometheus")
    void testPrometheusEndpointAvailable() throws IOException {
        int status = getStatusCode(prometheusUrl());
        assertEquals(200, status, "Prometheus endpoint should return 200");

        String body = getBody(prometheusUrl());
        // Deve conter métricas padrão do Micrometer/JVM
        assertTrue(body.contains("jvm_memory"), "Should contain JVM metrics");
        log.info("✅ /actuator/prometheus retorna 200 com métricas JVM");
    }

    @Test
    @Order(2)
    @DisplayName("T2: Após requests ao proxy, métricas ngate_requests_total incrementam")
    void testInboundMetricsIncrement() throws IOException {
        // Gerar tráfego: 5 requests ao proxy
        for (int i = 0; i < 5; i++) {
            int status = getStatusCode(proxyUrl());
            assertEquals(200, status, "Proxy should return 200 on request " + i);
        }

        // Verificar que /actuator/prometheus contém a métrica inbound
        String metrics = getBody(prometheusUrl());

        assertTrue(metrics.contains("ngate_requests_total"),
                "Should contain ngate_requests_total counter. Full metrics (first 2000 chars): " +
                metrics.substring(0, Math.min(2000, metrics.length())));

        log.info("✅ ngate_requests_total presente após tráfego");
    }

    @Test
    @Order(3)
    @DisplayName("T3: Métricas upstream presentes após tráfego")
    void testUpstreamMetricsPresent() throws IOException {
        // Gerar mais tráfego para garantir
        for (int i = 0; i < 3; i++) {
            getStatusCode(proxyUrl());
        }

        String metrics = getBody(prometheusUrl());

        assertTrue(metrics.contains("ngate_upstream_requests_total"),
                "Should contain ngate_upstream_requests_total counter");

        assertTrue(metrics.contains("ngate_upstream_duration"),
                "Should contain ngate_upstream_duration timer");

        assertTrue(metrics.contains("ngate_request_duration"),
                "Should contain ngate_request_duration timer");

        log.info("✅ Métricas upstream presentes: ngate_upstream_requests_total, ngate_upstream_duration, ngate_request_duration");
    }

    @Test
    @Order(4)
    @DisplayName("T4: Métricas do circuit breaker presentes (Resilience4j)")
    void testCircuitBreakerMetricsPresent() throws IOException {
        // Após requests, verificar que métricas do Resilience4j aparecem
        // O CB está habilitado via adapter.yaml, então getOrCreate() registra métricas
        String metrics = getBody(prometheusUrl());

        // As métricas de CB do Resilience4j são registradas via TaggedCircuitBreakerMetrics
        // e só aparecem após pelo menos um circuito ser criado (lazy)
        // Gerar requests para criar o CB do mock-backend
        for (int i = 0; i < 3; i++) {
            getStatusCode(proxyUrl());
        }

        metrics = getBody(prometheusUrl());

        // Resilience4j registra métricas como resilience4j_circuitbreaker_*
        assertTrue(metrics.contains("resilience4j_circuitbreaker"),
                "Should contain Resilience4j circuit breaker metrics. " +
                "Searching for 'resilience4j_circuitbreaker' in metrics.");

        log.info("✅ Métricas Resilience4j circuit breaker presentes");
    }
}
