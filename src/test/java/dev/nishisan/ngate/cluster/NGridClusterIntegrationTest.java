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
package dev.nishisan.ngate.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes de integração do Cluster Mode NGrid com 2 nós n-gate reais.
 * <p>
 * Valida: mesh formation, leader election, proxy funcional, graceful shutdown
 * e instance ID único no health check.
 * <p>
 * Usa Testcontainers + JUnit 5 + Awaitility para polling assíncrono.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-09
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class NGridClusterIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(NGridClusterIntegrationTest.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    // Portas internas dos containers
    private static final int PROXY_PORT = 9091;
    private static final int HEALTH_PORT = 9190;
    private static final int NGRID_PORT = 7100;
    private static final int BACKEND_PORT = 8080;

    // Shared Docker network
    private static final Network network = Network.newNetwork();

    // ─── Build da imagem n-gate ───────────────────────────────────────────

    private static final ImageFromDockerfile ngateImage = new ImageFromDockerfile("ngate-test", false)
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
            .withExposedPorts(BACKEND_PORT)
            .waitingFor(Wait.forHttp("/health").forPort(BACKEND_PORT).forStatusCode(200));

    // ─── Nó 1 (n-gate) ───────────────────────────────────────────────────

    @Container
    private static final GenericContainer<?> node1 = new GenericContainer<>(ngateImage)
            .withNetwork(network)
            .withNetworkAliases("ngate-node1")
            .withEnv("SPRING_PROFILES_DEFAULT", "test")
            .withEnv("NGATE_CONFIG", "/app/config/adapter-test-cluster.yaml")
            .withEnv("NGATE_CLUSTER_NODE_ID", "ngate-node1")
            .withEnv("NGATE_INSTANCE_ID", "test-node-1")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("adapter-test-cluster.yaml"),
                    "/app/config/adapter-test-cluster.yaml")
            .withExposedPorts(PROXY_PORT, HEALTH_PORT, NGRID_PORT)
            .dependsOn(mockBackend)
            .waitingFor(Wait.forHttp("/actuator/health")
                    .forPort(HEALTH_PORT)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(120)))
            .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("node-1"));

    // ─── Nó 2 (n-gate) ───────────────────────────────────────────────────

    @Container
    private static final GenericContainer<?> node2 = new GenericContainer<>(ngateImage)
            .withNetwork(network)
            .withNetworkAliases("ngate-node2")
            .withEnv("SPRING_PROFILES_DEFAULT", "test")
            .withEnv("NGATE_CONFIG", "/app/config/adapter-test-cluster.yaml")
            .withEnv("NGATE_CLUSTER_NODE_ID", "ngate-node2")
            .withEnv("NGATE_INSTANCE_ID", "test-node-2")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("adapter-test-cluster.yaml"),
                    "/app/config/adapter-test-cluster.yaml")
            .withExposedPorts(PROXY_PORT, HEALTH_PORT, NGRID_PORT)
            .dependsOn(mockBackend)
            .waitingFor(Wait.forHttp("/actuator/health")
                    .forPort(HEALTH_PORT)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(120)))
            .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("node-2"));

    // ─── Helper Methods ──────────────────────────────────────────────────

    /**
     * Faz GET e retorna a resposta como JsonNode.
     */
    private JsonNode getJson(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            assertNotNull(response.body(), "Response body is null for: " + url);
            return objectMapper.readTree(response.body().string());
        }
    }

    /**
     * Faz GET e retorna o HTTP status code.
     */
    private int getStatusCode(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            return response.code();
        }
    }

    private String healthUrl(GenericContainer<?> container) {
        return String.format("http://%s:%d/actuator/health",
                container.getHost(), container.getMappedPort(HEALTH_PORT));
    }

    private String proxyUrl(GenericContainer<?> container) {
        return String.format("http://%s:%d/",
                container.getHost(), container.getMappedPort(PROXY_PORT));
    }

    /**
     * Extrai o bloco "details" do health indicator customizado nGate,
     * navegando de forma resiliente pelo JSON do Actuator.
     * <p>
     * Estrutura esperada:
     * <pre>
     * {
     *   "status": "UP",
     *   "components": {
     *     "nGate": {
     *       "status": "UP",
     *       "details": { "instanceId": "...", "clusterMode": true, ... }
     *     }
     *   }
     * }
     * </pre>
     * Tenta também variantes de nomes: "nGate", "NGate", "n-gate".
     */
    private JsonNode getNGateDetails(JsonNode health) {
        JsonNode components = health.path("components");
        // Tentar variações possíveis do nome do HealthIndicator
        for (String key : new String[]{"nGate", "NGate", "n-gate", "ngate"}) {
            JsonNode hi = components.path(key);
            if (!hi.isMissingNode()) {
                return hi.path("details");
            }
        }
        // Fallback: buscar por qualquer componente que tenha "clusterMode" nos details
        var fields = components.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            JsonNode details = entry.getValue().path("details");
            if (details.has("clusterMode") || details.has("instanceId")) {
                log.info("Found health indicator under key: '{}'", entry.getKey());
                return details;
            }
        }
        log.warn("Could not find nGate health indicator. Full health JSON: {}", health);
        return objectMapper.createObjectNode(); // empty node
    }

    // ─── Testes ──────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("T1: Cluster mesh formation — 2 nós se descobrem e formam mesh")
    void testClusterMeshFormation() {
        log.info("Aguardando mesh formation entre os 2 nós...");

        // Polling até ambos reportarem activeMembers: 2
        await().atMost(90, TimeUnit.SECONDS)
                .pollInterval(3, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    JsonNode health1 = getJson(healthUrl(node1));
                    JsonNode health2 = getJson(healthUrl(node2));

                    // Ambos devem estar UP
                    assertEquals("UP", health1.path("status").asText(), "Node 1 should be UP");
                    assertEquals("UP", health2.path("status").asText(), "Node 2 should be UP");

                    // Extrair detalhes do health indicator customizado
                    JsonNode details1 = getNGateDetails(health1);
                    JsonNode details2 = getNGateDetails(health2);

                    // Ambos devem reportar cluster mode ativo
                    assertTrue(details1.path("clusterMode").asBoolean(false),
                            "Node 1 should be in cluster mode. Details: " + details1);
                    assertTrue(details2.path("clusterMode").asBoolean(false),
                            "Node 2 should be in cluster mode. Details: " + details2);

                    // Ambos devem ver 2 membros
                    assertEquals(2, details1.path("activeMembers").asInt(0),
                            "Node 1 should see 2 active members. Details: " + details1);
                    assertEquals(2, details2.path("activeMembers").asInt(0),
                            "Node 2 should see 2 active members. Details: " + details2);
                });

        log.info("✅ Mesh formation confirmada — 2 nós ativos");
    }

    @Test
    @Order(2)
    @DisplayName("T2: Leader election — exatamente 1 líder entre os 2 nós")
    void testLeaderElection() {
        await().atMost(90, TimeUnit.SECONDS)
                .pollInterval(3, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    JsonNode details1 = getNGateDetails(getJson(healthUrl(node1)));
                    JsonNode details2 = getNGateDetails(getJson(healthUrl(node2)));

                    boolean node1IsLeader = details1.path("isLeader").asBoolean(false);
                    boolean node2IsLeader = details2.path("isLeader").asBoolean(false);

                    // Exatamente um deve ser líder (XOR)
                    assertTrue(node1IsLeader ^ node2IsLeader,
                            String.format("Exactly one node should be leader. Node1=%s, Node2=%s. " +
                                    "Details1=%s, Details2=%s",
                                    node1IsLeader, node2IsLeader, details1, details2));
                });

        log.info("✅ Leader election validada — exatamente 1 líder");
    }

    @Test
    @Order(3)
    @DisplayName("T3: Proxy funcional — requests retornam HTTP 200 em ambos os nós")
    void testProxyFunctional() throws IOException {
        // Node 1
        int status1 = getStatusCode(proxyUrl(node1));
        assertEquals(200, status1, "Proxy on Node 1 should return 200");

        // Node 2
        int status2 = getStatusCode(proxyUrl(node2));
        assertEquals(200, status2, "Proxy on Node 2 should return 200");

        // Validar que o body é o backend mock
        JsonNode body1 = getJson(proxyUrl(node1));
        assertEquals("ok", body1.path("status").asText(),
                "Node 1 proxy should forward to mock backend");

        JsonNode body2 = getJson(proxyUrl(node2));
        assertEquals("ok", body2.path("status").asText(),
                "Node 2 proxy should forward to mock backend");

        log.info("✅ Proxy funcional — ambos os nós retornam 200 do backend mock");
    }

    @Test
    @Order(4)
    @DisplayName("T5: Instance ID — health reporta instanceId distinto por nó")
    void testHealthReportsInstanceId() {
        // Executar ANTES do graceful shutdown (T4/Order=5) para ter ambos os nós
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    JsonNode details1 = getNGateDetails(getJson(healthUrl(node1)));
                    JsonNode details2 = getNGateDetails(getJson(healthUrl(node2)));

                    String instanceId1 = details1.path("instanceId").asText("");
                    String instanceId2 = details2.path("instanceId").asText("");

                    assertFalse(instanceId1.isBlank(),
                            "Node 1 instanceId should not be blank. Details: " + details1);
                    assertFalse(instanceId2.isBlank(),
                            "Node 2 instanceId should not be blank. Details: " + details2);

                    // Instance IDs devem ser diferentes entre nós
                    assertNotEquals(instanceId1, instanceId2,
                            "Instance IDs should be distinct between nodes");
                });

        log.info("✅ Instance IDs distintos validados");
    }

    @Test
    @Order(5)
    @DisplayName("T4: Graceful shutdown — Nó 2 é parado, Nó 1 continua operando")
    void testGracefulShutdown() {
        // Parar Nó 2
        log.info("Parando Nó 2...");
        node2.stop();

        // Nó 1 deve continuar UP e operando
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    JsonNode health1 = getJson(healthUrl(node1));
                    assertEquals("UP", health1.path("status").asText(),
                            "Node 1 should still be UP after Node 2 shutdown");

                    // Proxy ainda funciona
                    int status = getStatusCode(proxyUrl(node1));
                    assertEquals(200, status,
                            "Node 1 proxy should still serve requests after Node 2 shutdown");
                });

        // Verificar que o Nó 1 detectou a saída do Nó 2
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(3, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    JsonNode details1 = getNGateDetails(getJson(healthUrl(node1)));
                    int activeMembers = details1.path("activeMembers").asInt(0);
                    assertTrue(activeMembers <= 1,
                            "Node 1 should see at most 1 active member after Node 2 shutdown. " +
                            "Got: " + activeMembers + ". Details: " + details1);
                });

        log.info("✅ Graceful shutdown validado — Nó 1 operando sozinho");
    }
}
