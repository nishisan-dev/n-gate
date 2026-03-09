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
import okhttp3.*;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes de integração para Rules Deploy via Admin API em cluster NGrid.
 * <p>
 * T8: Standalone rules deploy — deploy no nó 1, verificar swap local.
 * T9: Cluster rules replication — deploy no nó 1, nó 2 aplica via DistributedMap polling.
 * T10: Admin API auth — requests sem API key devem retornar 401.
 * <p>
 * Usa Testcontainers + JUnit 5 + Awaitility para polling assíncrono.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-09
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class NGridClusterRulesDeployIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(NGridClusterRulesDeployIntegrationTest.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private static final String API_KEY = "test-admin-key-12345";
    private static final String WRONG_API_KEY = "wrong-key";

    // Portas internas dos containers
    private static final int PROXY_PORT = 9091;
    private static final int MGMT_PORT = 9190;   // Spring Boot management port (Actuator + Admin API)
    private static final int NGRID_PORT = 7100;
    private static final int BACKEND_PORT = 8080;

    // Shared Docker network
    private static final Network network = Network.newNetwork();

    // ─── Build da imagem n-gate ───────────────────────────────────────────

    private static final ImageFromDockerfile ngateImage = new ImageFromDockerfile("ngate-rules-test", false)
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

    // ─── Nó 1 (n-gate com admin API) ─────────────────────────────────────

    @Container
    private static final GenericContainer<?> node1 = new GenericContainer<>(ngateImage)
            .withNetwork(network)
            .withNetworkAliases("ngate-rules-node1")
            .withEnv("SPRING_PROFILES_ACTIVE", "test")
            .withEnv("SERVER_PORT", "9190")
            .withEnv("MANAGEMENT_PORT", "9190")
            .withEnv("NGATE_CONFIG", "/app/config/adapter-test-cluster-rules.yaml")
            .withEnv("NGATE_CLUSTER_NODE_ID", "ngate-rules-node1")
            .withEnv("NGATE_INSTANCE_ID", "rules-test-node-1")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("adapter-test-cluster-rules.yaml"),
                    "/app/config/adapter-test-cluster-rules.yaml")
            .withExposedPorts(PROXY_PORT, MGMT_PORT, NGRID_PORT)
            .dependsOn(mockBackend)
            .waitingFor(Wait.forHttp("/actuator/health")
                    .forPort(MGMT_PORT)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(120)))
            .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("rules-node-1"));

    // ─── Nó 2 (n-gate com admin API) ─────────────────────────────────────

    @Container
    private static final GenericContainer<?> node2 = new GenericContainer<>(ngateImage)
            .withNetwork(network)
            .withNetworkAliases("ngate-rules-node2")
            .withEnv("SPRING_PROFILES_ACTIVE", "test")
            .withEnv("SERVER_PORT", "9190")
            .withEnv("MANAGEMENT_PORT", "9190")
            .withEnv("NGATE_CONFIG", "/app/config/adapter-test-cluster-rules.yaml")
            .withEnv("NGATE_CLUSTER_NODE_ID", "ngate-rules-node2")
            .withEnv("NGATE_INSTANCE_ID", "rules-test-node-2")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("adapter-test-cluster-rules.yaml"),
                    "/app/config/adapter-test-cluster-rules.yaml")
            .withExposedPorts(PROXY_PORT, MGMT_PORT, NGRID_PORT)
            .dependsOn(mockBackend)
            .waitingFor(Wait.forHttp("/actuator/health")
                    .forPort(MGMT_PORT)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(120)))
            .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("rules-node-2"));

    // ─── Helper Methods ──────────────────────────────────────────────────

    /**
     * URL base da Admin API para um container.
     */
    private String adminUrl(GenericContainer<?> container, String path) {
        return String.format("http://%s:%d/admin/rules%s",
                container.getHost(), container.getMappedPort(MGMT_PORT), path);
    }

    /**
     * Faz GET com header X-API-Key e retorna o JsonNode da resposta.
     */
    private JsonNode getJsonWithAuth(String url, String apiKey) throws IOException {
        Request.Builder builder = new Request.Builder().url(url).get();
        if (apiKey != null) {
            builder.addHeader("X-API-Key", apiKey);
        }
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            assertNotNull(response.body(), "Response body is null for: " + url);
            return objectMapper.readTree(response.body().string());
        }
    }

    /**
     * Faz GET com auth e retorna o HTTP status code.
     */
    private int getStatusCodeWithAuth(String url, String apiKey) throws IOException {
        Request.Builder builder = new Request.Builder().url(url).get();
        if (apiKey != null) {
            builder.addHeader("X-API-Key", apiKey);
        }
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            return response.code();
        }
    }

    /**
     * Deploya um script .groovy via POST multipart na Admin API.
     *
     * @return o HTTP status code + body JSON do response
     */
    private DeployResult deployScript(GenericContainer<?> container, String apiKey,
                                       String scriptName, String scriptContent) throws IOException {
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("scripts", scriptName,
                        RequestBody.create(scriptContent.getBytes(StandardCharsets.UTF_8),
                                MediaType.parse("text/plain")))
                .build();

        Request.Builder builder = new Request.Builder()
                .url(adminUrl(container, "/deploy"))
                .post(requestBody);
        if (apiKey != null) {
            builder.addHeader("X-API-Key", apiKey);
        }

        try (Response response = httpClient.newCall(builder.build()).execute()) {
            String body = response.body() != null ? response.body().string() : "{}";
            return new DeployResult(response.code(), objectMapper.readTree(body));
        }
    }

    private record DeployResult(int statusCode, JsonNode body) {}

    // ─── Testes ──────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("T10: Admin API auth — request sem API key retorna 401")
    void testAdminApiRejectsWithoutApiKey() throws IOException {
        // Sem header X-API-Key
        int statusNoKey = getStatusCodeWithAuth(adminUrl(node1, "/version"), null);
        assertEquals(401, statusNoKey, "Admin API should return 401 without API key");

        // Com key errada
        int statusWrongKey = getStatusCodeWithAuth(adminUrl(node1, "/version"), WRONG_API_KEY);
        assertEquals(401, statusWrongKey, "Admin API should return 401 with wrong API key");

        log.info("✅ T10: Admin API rejeita requests sem API key ou com key errada");
    }

    @Test
    @Order(2)
    @DisplayName("T8: Standalone rules deploy — deploy no nó 1, swap local confirmado")
    void testStandaloneRulesDeploy() throws IOException {
        // 0. Aguardar Admin API pronto (ApplicationReadyEvent pode ter delay)
        log.info("Aguardando Admin API ficar pronta no nó 1...");
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    int status = getStatusCodeWithAuth(adminUrl(node1, "/version"), API_KEY);
                    assertEquals(200, status, "Admin API should return 200 with correct API key");
                });

        // 1. Verificar que inicialmente não há bundle ativo
        JsonNode versionBefore = getJsonWithAuth(adminUrl(node1, "/version"), API_KEY);
        log.info("Version before deploy: {}", versionBefore);
        assertEquals("no-bundle", versionBefore.path("status").asText(),
                "Should have no bundle before first deploy");

        // 2. Criar um script Groovy simples para deploy
        String testScript = """
                // Test script deployed via Admin API
                // This rule adds a custom header to identify deployed rules
                workload.clientResponse.addHeader('X-Rules-Version', 'test-v1');
                """;

        // 3. Fazer deploy via Admin API
        DeployResult result = deployScript(node1, API_KEY, "default/Rules.groovy", testScript);
        log.info("Deploy response: status={}, body={}", result.statusCode(), result.body());

        assertEquals(200, result.statusCode(), "Deploy should return 200");
        assertEquals("deployed", result.body().path("status").asText(),
                "Deploy response should have status=deployed");
        assertEquals(1, result.body().path("version").asLong(),
                "First deploy should be version 1");
        assertEquals(1, result.body().path("scriptCount").asInt(),
                "Deploy should report 1 script");

        // 4. Verificar que a versão foi atualizada
        JsonNode versionAfter = getJsonWithAuth(adminUrl(node1, "/version"), API_KEY);
        log.info("Version after deploy: {}", versionAfter);
        assertEquals("active", versionAfter.path("status").asText(),
                "Should have active bundle after deploy");
        assertEquals(1, versionAfter.path("version").asLong(),
                "Active version should be 1");

        // 5. Segundo deploy — versão incrementa
        String testScript2 = """
                // Test script v2
                workload.clientResponse.addHeader('X-Rules-Version', 'test-v2');
                """;

        DeployResult result2 = deployScript(node1, API_KEY, "default/Rules.groovy", testScript2);
        assertEquals(200, result2.statusCode(), "Second deploy should return 200");
        assertEquals(2, result2.body().path("version").asLong(),
                "Second deploy should be version 2");

        log.info("✅ T8: Standalone rules deploy validado — 2 deploys com versão incremental");
    }

    @Test
    @Order(3)
    @DisplayName("T9: Cluster rules replication — deploy no nó 1, nó 2 recebe via polling")
    void testClusterRulesReplication() throws IOException {
        // 1. Aguardar mesh formation (2 nós ativos)
        log.info("Aguardando mesh formation entre os 2 nós...");
        await().atMost(90, TimeUnit.SECONDS)
                .pollInterval(3, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    // Verificar que ambos estão UP via actuator health
                    int status1 = getStatusCodeWithAuth(
                            String.format("http://%s:%d/actuator/health",
                                    node1.getHost(), node1.getMappedPort(MGMT_PORT)), null);
                    int status2 = getStatusCodeWithAuth(
                            String.format("http://%s:%d/actuator/health",
                                    node2.getHost(), node2.getMappedPort(MGMT_PORT)), null);
                    assertEquals(200, status1, "Node 1 should be healthy");
                    assertEquals(200, status2, "Node 2 should be healthy");
                });

        // 2. Fazer deploy de um script NOVO no nó 1 (v3 — após T8 que já fez v1 e v2)
        String clusterScript = """
                // Cluster-replicated script v3
                workload.clientResponse.addHeader('X-Cluster-Rules', 'replicated-v3');
                """;

        DeployResult deployResult = deployScript(node1, API_KEY, "default/Rules.groovy", clusterScript);
        log.info("Deploy on node 1: status={}, body={}", deployResult.statusCode(), deployResult.body());
        assertEquals(200, deployResult.statusCode(), "Deploy on node 1 should return 200");
        long deployedVersion = deployResult.body().path("version").asLong();
        assertTrue(deployedVersion >= 1, "Deployed version should be >= 1");

        // 3. Verificar versão no nó 1 (imediato)
        JsonNode node1Version = getJsonWithAuth(adminUrl(node1, "/version"), API_KEY);
        assertEquals("active", node1Version.path("status").asText(),
                "Node 1 should have active bundle");
        assertEquals(deployedVersion, node1Version.path("version").asLong(),
                "Node 1 active version should match deployed version");

        // 4. Aguardar replicação para nó 2 (via polling do DistributedMap, ~5s interval)
        log.info("Aguardando replicação do bundle v{} para nó 2 (polling interval ~5s)...", deployedVersion);
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    JsonNode node2Version = getJsonWithAuth(adminUrl(node2, "/version"), API_KEY);
                    log.info("Node 2 version check: {}", node2Version);
                    assertEquals("active", node2Version.path("status").asText(),
                            "Node 2 should have active bundle after replication");
                    assertEquals(deployedVersion, node2Version.path("version").asLong(),
                            "Node 2 active version should match deployed version from node 1");
                });

        log.info("✅ T9: Cluster rules replication validada — v{} replicado de nó 1 para nó 2", deployedVersion);
    }

    @Test
    @Order(4)
    @DisplayName("T11: Admin API deploy rejeita request sem arquivos")
    void testDeployRejectsEmptyPayload() throws IOException {
        // POST sem scripts (body vazio não multipart)
        Request request = new Request.Builder()
                .url(adminUrl(node1, "/deploy"))
                .addHeader("X-API-Key", API_KEY)
                .post(RequestBody.create("{}", MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            // Deve retornar 400 ou 415 (unsupported media type)
            assertTrue(response.code() >= 400,
                    "Deploy without multipart should fail. Got: " + response.code());
        }

        log.info("✅ T11: Admin API rejeita deploy sem scripts");
    }
}
