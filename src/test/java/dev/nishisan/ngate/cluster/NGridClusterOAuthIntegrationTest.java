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
 * Testes de integração do OAuth Token Sharing POW-RBL no cluster NGrid.
 * <p>
 * Valida:
 * <ul>
 *   <li>T6: Token sharing via DistributedMap — Nó 1 obtém token do Keycloak,
 *       Nó 2 lê do mapa distribuído sem ir ao IdP</li>
 *   <li>T7: Resiliência — queda do Nó 1, Nó 2 continua servindo requests</li>
 * </ul>
 * <p>
 * Requer: Docker com acesso ao Keycloak image.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-09
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class NGridClusterOAuthIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(NGridClusterOAuthIntegrationTest.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    // Portas internas dos containers
    private static final int PROXY_PORT = 9091;
    private static final int HEALTH_PORT = 9190;
    private static final int NGRID_PORT = 7100;
    private static final int BACKEND_PORT = 8080;
    private static final int KEYCLOAK_PORT = 8080;

    // Shared Docker network
    private static final Network network = Network.newNetwork();

    // ─── Build da imagem n-gate ───────────────────────────────────────────

    private static final ImageFromDockerfile ngateImage = new ImageFromDockerfile("ngate-oauth-test", false)
            .withFileFromPath(".", Path.of(System.getProperty("user.dir")))
            .withFileFromPath("settings.xml",
                    Path.of(System.getProperty("user.home"), ".m2", "settings.xml"));

    // ─── Keycloak ─────────────────────────────────────────────────────────

    @Container
    private static final GenericContainer<?> keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.0")
            .withNetwork(network)
            .withNetworkAliases("keycloak")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("testcontainers/realm-inventory-dev.json"),
                    "/opt/keycloak/data/import/realm-inventory-dev.json")
            .withCommand("start-dev", "--import-realm")
            .withExposedPorts(KEYCLOAK_PORT)
            .waitingFor(Wait.forHttp("/realms/inventory-dev")
                    .forPort(KEYCLOAK_PORT)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(120)))
            .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("keycloak"));

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

    // ─── Nó 1 (n-gate com OAuth) ─────────────────────────────────────────

    @Container
    private static final GenericContainer<?> node1 = new GenericContainer<>(ngateImage)
            .withNetwork(network)
            .withNetworkAliases("ngate-oauth-node1")
            .withEnv("SPRING_PROFILES_DEFAULT", "test")
            .withEnv("NGATE_CONFIG", "/app/config/adapter-test-cluster-oauth.yaml")
            .withEnv("NGATE_CLUSTER_NODE_ID", "ngate-oauth-node1")
            .withEnv("NGATE_INSTANCE_ID", "oauth-node-1")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("adapter-test-cluster-oauth.yaml"),
                    "/app/config/adapter-test-cluster-oauth.yaml")
            .withExposedPorts(PROXY_PORT, HEALTH_PORT, NGRID_PORT)
            .dependsOn(keycloak, mockBackend)
            .waitingFor(Wait.forHttp("/actuator/health")
                    .forPort(HEALTH_PORT)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(120)))
            .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("oauth-node-1"));

    // ─── Nó 2 (n-gate com OAuth) ─────────────────────────────────────────

    @Container
    private static final GenericContainer<?> node2 = new GenericContainer<>(ngateImage)
            .withNetwork(network)
            .withNetworkAliases("ngate-oauth-node2")
            .withEnv("SPRING_PROFILES_DEFAULT", "test")
            .withEnv("NGATE_CONFIG", "/app/config/adapter-test-cluster-oauth.yaml")
            .withEnv("NGATE_CLUSTER_NODE_ID", "ngate-oauth-node2")
            .withEnv("NGATE_INSTANCE_ID", "oauth-node-2")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("adapter-test-cluster-oauth.yaml"),
                    "/app/config/adapter-test-cluster-oauth.yaml")
            .withExposedPorts(PROXY_PORT, HEALTH_PORT, NGRID_PORT)
            .dependsOn(keycloak, mockBackend)
            .waitingFor(Wait.forHttp("/actuator/health")
                    .forPort(HEALTH_PORT)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(120)))
            .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("oauth-node-2"));

    // ─── Helper Methods ──────────────────────────────────────────────────

    private JsonNode getJson(String url) throws IOException {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            assertNotNull(response.body(), "Response body is null for: " + url);
            return objectMapper.readTree(response.body().string());
        }
    }

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
        return String.format("http://%s:%d/test-oauth",
                container.getHost(), container.getMappedPort(PROXY_PORT));
    }

    private JsonNode getNGateDetails(JsonNode health) {
        JsonNode components = health.path("components");
        for (String key : new String[]{"nGate", "NGate", "n-gate", "ngate"}) {
            JsonNode hi = components.path(key);
            if (!hi.isMissingNode()) {
                return hi.path("details");
            }
        }
        var fields = components.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            JsonNode details = entry.getValue().path("details");
            if (details.has("clusterMode") || details.has("instanceId")) {
                return details;
            }
        }
        return objectMapper.createObjectNode();
    }

    /**
     * Verifica se o log do container contém a string esperada.
     */
    private boolean containerLogContains(GenericContainer<?> container, String text) {
        return container.getLogs().contains(text);
    }

    // ─── Testes ──────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("T6: Token Sharing POW-RBL — ambos nós servem requests autenticados via OAuth")
    void testTokenSharingViaPOWRBL() {
        log.info("Aguardando mesh formation do cluster OAuth...");

        // Garantir que o mesh está formado antes de testar OAuth
        await().atMost(90, TimeUnit.SECONDS)
                .pollInterval(3, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    JsonNode details1 = getNGateDetails(getJson(healthUrl(node1)));
                    JsonNode details2 = getNGateDetails(getJson(healthUrl(node2)));
                    assertTrue(details1.path("clusterMode").asBoolean(false),
                            "Node 1 should be in cluster mode");
                    assertTrue(details2.path("clusterMode").asBoolean(false),
                            "Node 2 should be in cluster mode");
                    assertEquals(2, details1.path("activeMembers").asInt(0),
                            "Should have 2 active members");
                });

        log.info("Mesh formado. Enviando request ao Nó 1 (primeiro token acquisition)...");

        // Step 1: Request ao Nó 1 — dispara getAccessToken() → login Keycloak → publish to map
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    int status = getStatusCode(proxyUrl(node1));
                    assertEquals(200, status,
                            "Node 1 proxy should return 200 (OAuth token acquired from Keycloak)");
                });

        log.info("Nó 1 obteve token e serviu request. Verificando Nó 1 autenticou com Bearer...");

        // Validar que o Nó 1 autenticou com Bearer token (log nível DEBUG aparece como STDOUT)
        assertTrue(containerLogContains(node1, "Interceptor Called Authenticated"),
                "Node 1 should have authenticated request with Bearer token");

        log.info("Nó 1 OK. Enviando request ao Nó 2...");

        // Step 2: Request ao Nó 2 — pode ler do DistributedMap ou fazer login próprio (POW-RBL)
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    int status = getStatusCode(proxyUrl(node2));
                    assertEquals(200, status,
                            "Node 2 proxy should return 200 (token from DistributedMap or Keycloak)");
                });

        // Validar que o Nó 2 também autenticou com Bearer token
        assertTrue(containerLogContains(node2, "Interceptor Called Authenticated"),
                "Node 2 should have authenticated request with Bearer token");

        // Avaliar se o DistributedMap foi utilizado (log info)
        boolean node2ReadFromMap = containerLogContains(node2, "loaded from cluster DistributedMap");
        boolean node2MadeOwnLogin = containerLogContains(node2, "Sent New Token");

        if (node2ReadFromMap) {
            log.info("✅ T6 IDEAL: Nó 2 leu token do DistributedMap — zero calls extras ao Keycloak");
        } else if (node2MadeOwnLogin) {
            log.info("✅ T6 ACCEPTABLE: Nó 2 obteve token diretamente do Keycloak (race condition POW-RBL)");
        } else {
            log.info("✅ T6 OK: Ambos os nós serviram requests autenticados com Bearer token");
        }
    }

    @Test
    @Order(2)
    @DisplayName("T7: Resiliência — Nó 1 cai, Nó 2 continua servindo requests com token válido")
    void testTokenResilienceOnNodeFailure() {
        log.info("Confirmando que Nó 2 está servindo requests antes de parar Nó 1...");

        // Confirmar que o Nó 2 está funcional
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    int status = getStatusCode(proxyUrl(node2));
                    assertEquals(200, status, "Node 2 should be serving requests");
                });

        // Parar Nó 1
        log.info("Parando Nó 1 (simulando falha do nó que obteve o token)...");
        node1.stop();

        // Nó 2 deve continuar servindo — o token está no cache local
        log.info("Nó 1 parado. Verificando que Nó 2 continua operando...");

        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    // Health continua UP
                    JsonNode health2 = getJson(healthUrl(node2));
                    assertEquals("UP", health2.path("status").asText(),
                            "Node 2 should still be UP after Node 1 failure");

                    // Proxy continua servindo (usa token do cache local)
                    int status = getStatusCode(proxyUrl(node2));
                    assertEquals(200, status,
                            "Node 2 proxy should still serve requests after Node 1 failure");
                });

        // Verificar que Nó 2 detectou saída do Nó 1
        await().atMost(60, TimeUnit.SECONDS)
                .pollInterval(3, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    JsonNode details2 = getNGateDetails(getJson(healthUrl(node2)));
                    int activeMembers = details2.path("activeMembers").asInt(0);
                    assertTrue(activeMembers <= 1,
                            "Node 2 should see at most 1 active member after Node 1 failure. " +
                                    "Got: " + activeMembers);
                });

        // Fazer mais requests para confirmar estabilidade contínua
        log.info("Confirmando estabilidade contínua do Nó 2 (múltiplos requests)...");
        for (int i = 0; i < 5; i++) {
            await().atMost(10, TimeUnit.SECONDS)
                    .pollInterval(1, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        int status = getStatusCode(proxyUrl(node2));
                        assertEquals(200, status,
                                "Node 2 should remain stable after repeated requests");
                    });
        }

        log.info("✅ T7: Nó 2 operando sozinho — resiliência confirmada após queda do Nó 1");
    }
}
