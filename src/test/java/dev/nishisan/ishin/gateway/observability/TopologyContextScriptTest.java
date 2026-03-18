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
package dev.nishisan.ishin.gateway.observability;

import dev.nishisan.ishin.gateway.configuration.*;
import dev.nishisan.ishin.gateway.dashboard.api.DashboardApiRoutes;
import dev.nishisan.ishin.gateway.dashboard.collector.MetricsCollectorService;
import dev.nishisan.ishin.gateway.dashboard.storage.DashboardStorageService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários para o enriquecimento da topologia com nós de
 * {@code context} e {@code script}.
 * <p>
 * Valida que {@code /api/v1/topology} inclui os novos tipos de nós e edges
 * sem alterar os existentes (gateway, listener, backend).
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TopologyContextScriptTest {

    private ServerConfiguration serverConfig;
    private DashboardApiRoutes apiRoutes;

    @BeforeEach
    void setUp() {
        serverConfig = new ServerConfiguration();
        serverConfig.setMode("proxy");

        // Configurar endpoint com listener, contexts e backend
        EndPointConfiguration epConfig = new EndPointConfiguration();

        // Listener com 2 urlContexts
        EndPointListenersConfiguration listener = new EndPointListenersConfiguration();
        listener.setListenPort(9091);
        listener.setSsl(false);
        listener.setSecured(false);
        listener.setDefaultBackend("mock-backend");

        // Contexto com ruleMapping (deve gerar nó de script)
        EndPointURLContext ctx1 = new EndPointURLContext("/*", "ANY", "default/Rules.groovy");
        ctx1.setSecured(false);

        // Contexto sem ruleMapping (não deve gerar nó de script)
        EndPointURLContext ctx2 = new EndPointURLContext("/health", "GET", null);
        ctx2.setSecured(false);

        listener.getUrlContexts().put("default", ctx1);
        listener.getUrlContexts().put("health-check", ctx2);

        epConfig.getListeners().put("http-noauth", listener);

        // Backend
        BackendConfiguration backend = new BackendConfiguration();
        backend.setBackendName("mock-backend");
        epConfig.getBackends().put("mock-backend", backend);

        serverConfig.getEndpoints().put("default", epConfig);

        // Criar DashboardApiRoutes (collector e storage são mocks mínimos)
        String tempDir = System.getProperty("java.io.tmpdir") + "/ishin-topo-test-" + System.nanoTime();
        new File(tempDir).mkdirs();
        DashboardConfiguration.DashboardStorageConfiguration storageConfig =
                new DashboardConfiguration.DashboardStorageConfiguration();
        storageConfig.setPath(tempDir);
        DashboardStorageService storage = new DashboardStorageService(storageConfig);
        MetricsCollectorService collector = new MetricsCollectorService(new SimpleMeterRegistry(), storage);

        DashboardConfiguration dashboardConfig = new DashboardConfiguration();
        apiRoutes = new DashboardApiRoutes(collector, storage, serverConfig, dashboardConfig, null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callBuildTopology() throws Exception {
        Method m = DashboardApiRoutes.class.getDeclaredMethod("buildTopology");
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(apiRoutes);
    }

    // ─── Existing Nodes Preserved ───────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Topologia mantém nó gateway existente")
    void testGatewayNodePreserved() throws Exception {
        Map<String, Object> topology = callBuildTopology();
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) topology.get("nodes");

        assertTrue(nodes.stream().anyMatch(n -> "gateway".equals(n.get("type")) && "ishin-gateway".equals(n.get("id"))),
                "Deve conter nó gateway 'ishin-gateway'");
    }

    @Test
    @Order(2)
    @DisplayName("Topologia mantém nó listener existente")
    void testListenerNodePreserved() throws Exception {
        Map<String, Object> topology = callBuildTopology();
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) topology.get("nodes");

        assertTrue(nodes.stream().anyMatch(n ->
                        "listener".equals(n.get("type")) && "listener:http-noauth".equals(n.get("id"))),
                "Deve conter nó listener:http-noauth");
    }

    @Test
    @Order(3)
    @DisplayName("Topologia mantém nó backend existente")
    void testBackendNodePreserved() throws Exception {
        Map<String, Object> topology = callBuildTopology();
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) topology.get("nodes");

        assertTrue(nodes.stream().anyMatch(n ->
                        "backend".equals(n.get("type")) && "backend:mock-backend".equals(n.get("id"))),
                "Deve conter nó backend:mock-backend");
    }

    // ─── Context Nodes ──────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("Topologia inclui nós de contexto com campos corretos")
    @SuppressWarnings("unchecked")
    void testContextNodesPresent() throws Exception {
        Map<String, Object> topology = callBuildTopology();
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) topology.get("nodes");

        // Deve ter 2 nós de contexto
        List<Map<String, Object>> contextNodes = nodes.stream()
                .filter(n -> "context".equals(n.get("type")))
                .toList();

        assertEquals(2, contextNodes.size(), "Deve ter 2 nós de contexto");

        // Verificar campos do contexto 'default'
        Map<String, Object> defaultCtx = contextNodes.stream()
                .filter(n -> "default".equals(n.get("label")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Nó de contexto 'default' não encontrado"));

        assertEquals("context:http-noauth:default", defaultCtx.get("id"));
        assertEquals("http-noauth", defaultCtx.get("listener"));
        assertEquals("/*", defaultCtx.get("contextPath"));
        assertEquals("ANY", defaultCtx.get("method"));
        assertEquals("default/Rules.groovy", defaultCtx.get("ruleMapping"));
        assertEquals(false, defaultCtx.get("secured"));
    }

    // ─── Script Nodes ───────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("Topologia inclui nó de script quando ruleMapping está definido")
    @SuppressWarnings("unchecked")
    void testScriptNodePresent() throws Exception {
        Map<String, Object> topology = callBuildTopology();
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) topology.get("nodes");

        List<Map<String, Object>> scriptNodes = nodes.stream()
                .filter(n -> "script".equals(n.get("type")))
                .toList();

        // Apenas o contexto 'default' tem ruleMapping → 1 nó de script
        assertEquals(1, scriptNodes.size(), "Deve ter 1 nó de script");

        Map<String, Object> scriptNode = scriptNodes.get(0);
        assertEquals("script:http-noauth:default:default/Rules.groovy", scriptNode.get("id"));
        assertEquals("default/Rules.groovy", scriptNode.get("label"));
        assertEquals("default/Rules.groovy", scriptNode.get("script"));
        assertEquals("context:http-noauth:default", scriptNode.get("context"));
    }

    @Test
    @Order(6)
    @DisplayName("Topologia NÃO gera nó de script quando ruleMapping é null")
    @SuppressWarnings("unchecked")
    void testNoScriptNodeWhenRuleMappingNull() throws Exception {
        Map<String, Object> topology = callBuildTopology();
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) topology.get("nodes");

        // health-check não tem ruleMapping → não deve gerar script node
        boolean hasHealthScriptNode = nodes.stream()
                .filter(n -> "script".equals(n.get("type")))
                .anyMatch(n -> n.get("id").toString().contains("health-check"));

        assertFalse(hasHealthScriptNode, "Não deve ter nó de script para health-check");
    }

    // ─── Edges ──────────────────────────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("Topologia inclui edge listener → context")
    @SuppressWarnings("unchecked")
    void testListenerToContextEdge() throws Exception {
        Map<String, Object> topology = callBuildTopology();
        List<Map<String, Object>> edges = (List<Map<String, Object>>) topology.get("edges");

        assertTrue(edges.stream().anyMatch(e ->
                        "listener:http-noauth".equals(e.get("source"))
                                && "context:http-noauth:default".equals(e.get("target"))
                                && "context".equals(e.get("type"))),
                "Deve conter edge listener → context");
    }

    @Test
    @Order(8)
    @DisplayName("Topologia inclui edge context → ishin-gateway")
    @SuppressWarnings("unchecked")
    void testContextToGatewayEdge() throws Exception {
        Map<String, Object> topology = callBuildTopology();
        List<Map<String, Object>> edges = (List<Map<String, Object>>) topology.get("edges");

        assertTrue(edges.stream().anyMatch(e ->
                        "context:http-noauth:default".equals(e.get("source"))
                                && "ishin-gateway".equals(e.get("target"))
                                && "inbound-context".equals(e.get("type"))),
                "Deve conter edge context → ishin-gateway");
    }

    @Test
    @Order(9)
    @DisplayName("Topologia inclui edge context → script")
    @SuppressWarnings("unchecked")
    void testContextToScriptEdge() throws Exception {
        Map<String, Object> topology = callBuildTopology();
        List<Map<String, Object>> edges = (List<Map<String, Object>>) topology.get("edges");

        assertTrue(edges.stream().anyMatch(e ->
                        "context:http-noauth:default".equals(e.get("source"))
                                && e.get("target").toString().startsWith("script:")
                                && "script-exec".equals(e.get("type"))),
                "Deve conter edge context → script");
    }

    @Test
    @Order(10)
    @DisplayName("Edges originais (inbound, upstream) permanecem")
    @SuppressWarnings("unchecked")
    void testOriginalEdgesPreserved() throws Exception {
        Map<String, Object> topology = callBuildTopology();
        List<Map<String, Object>> edges = (List<Map<String, Object>>) topology.get("edges");

        // listener → ishin-gateway (inbound) original
        assertTrue(edges.stream().anyMatch(e ->
                        "listener:http-noauth".equals(e.get("source"))
                                && "ishin-gateway".equals(e.get("target"))
                                && "inbound".equals(e.get("type"))),
                "Edge inbound original deve permanecer");

        // ishin-gateway → backend (upstream) original
        assertTrue(edges.stream().anyMatch(e ->
                        "ishin-gateway".equals(e.get("source"))
                                && "backend:mock-backend".equals(e.get("target"))
                                && "upstream".equals(e.get("type"))),
                "Edge upstream original deve permanecer");
    }
}
