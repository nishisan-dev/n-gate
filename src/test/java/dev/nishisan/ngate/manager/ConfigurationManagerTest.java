/*
 * Copyright (C) 2026 Lucas Nishimura <lucas.nishimura@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dev.nishisan.ngate.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.nishisan.ngate.configuration.BackendConfiguration;
import dev.nishisan.ngate.configuration.EndPointConfiguration;
import dev.nishisan.ngate.configuration.EndPointListenersConfiguration;
import dev.nishisan.ngate.configuration.EndPointURLContext;
import dev.nishisan.ngate.configuration.ServerConfiguration;
import dev.nishisan.ngate.configuration.UpstreamMemberConfiguration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários de parsing YAML do {@link ConfigurationManager}.
 * <p>
 * Testa a serialização/desserialização do modelo de configuração
 * ({@link ServerConfiguration}) isolado do Spring context.
 * <p>
 * Não testa {@code loadConfiguration()} diretamente pois ele depende
 * de paths fixos e env vars. Ao invés disso, testa a capacidade do
 * ObjectMapper (YAML) de ler/gerar a estrutura de configuração.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-11
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigurationManagerTest {

    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @TempDir
    Path tempDir;

    @Test
    @Order(1)
    @DisplayName("T1: YAML válido com endpoint, listener e backend é parseado corretamente")
    void testValidYamlParsing() throws IOException {
        String yaml = """
                ---
                endpoints:
                  default:
                    listeners:
                      http-noauth:
                        listenAddress: "0.0.0.0"
                        listenPort: 9091
                        ssl: false
                        scriptOnly: false
                        defaultBackend: "static-backend"
                        secured: false
                        urlContexts:
                          default:
                            context: "/*"
                            method: "ANY"
                            ruleMapping: "default/Rules.groovy"
                    backends:
                      static-backend:
                        backendName: "static-backend"
                        members:
                          - url: "http://backend:8080"
                    ruleMapping: "default/Rules.groovy"
                    ruleMappingThreads: 1
                    socketTimeout: 30
                """;

        File configFile = tempDir.resolve("adapter.yaml").toFile();
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(yaml);
        }

        ServerConfiguration config = yamlMapper.readValue(configFile, ServerConfiguration.class);

        assertNotNull(config, "Config should not be null");
        assertNotNull(config.getEndpoints(), "Endpoints should not be null");
        assertTrue(config.getEndpoints().containsKey("default"), "Should have 'default' endpoint");

        EndPointConfiguration ep = config.getEndpoints().get("default");
        assertEquals("default/Rules.groovy", ep.getRuleMapping());
        assertEquals(1, ep.getRuleMappingThreads());
        assertEquals(30, ep.getSocketTimeout());

        // Listener
        assertTrue(ep.getListeners().containsKey("http-noauth"));
        EndPointListenersConfiguration listener = ep.getListeners().get("http-noauth");
        assertEquals("0.0.0.0", listener.getListenAddress());
        assertEquals(9091, listener.getListenPort());
        assertFalse(listener.getSsl());
        assertFalse(listener.getSecured());
        assertEquals("static-backend", listener.getDefaultBackend());

        // URL Context
        assertTrue(listener.getUrlContexts().containsKey("default"));
        EndPointURLContext urlCtx = listener.getUrlContexts().get("default");
        assertEquals("/*", urlCtx.getContext());
        assertEquals("ANY", urlCtx.getMethod());

        // Backend
        assertTrue(ep.getBackends().containsKey("static-backend"));
        BackendConfiguration backend = ep.getBackends().get("static-backend");
        assertEquals("static-backend", backend.getBackendName());
        assertEquals(1, backend.getMembers().size());
        assertEquals("http://backend:8080", backend.getMembers().get(0).getUrl());
    }

    @Test
    @Order(2)
    @DisplayName("T2: Configuração com cluster mode é parseada corretamente")
    void testClusterConfigParsing() throws IOException {
        String yaml = """
                ---
                endpoints:
                  default:
                    listeners:
                      http:
                        listenAddress: "0.0.0.0"
                        listenPort: 9091
                        ssl: false
                        secured: false
                        urlContexts:
                          default:
                            context: "/*"
                            method: "ANY"
                    backends:
                      backend:
                        backendName: "backend"
                        members:
                          - url: "http://backend:8080"
                    ruleMapping: "default/Rules.groovy"
                cluster:
                  enabled: true
                  host: "0.0.0.0"
                  port: 7100
                  clusterName: "test-cluster"
                  seeds:
                    - "node1:7100"
                    - "node2:7100"
                  replicationFactor: 2
                  dataDirectory: "/tmp/ngrid-data"
                """;

        File configFile = tempDir.resolve("adapter-cluster.yaml").toFile();
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(yaml);
        }

        ServerConfiguration config = yamlMapper.readValue(configFile, ServerConfiguration.class);

        assertNotNull(config.getCluster(), "Cluster config should not be null");
        assertTrue(config.getCluster().isEnabled());
        assertEquals("0.0.0.0", config.getCluster().getHost());
        assertEquals(7100, config.getCluster().getPort());
        assertEquals("test-cluster", config.getCluster().getClusterName());
        assertEquals(2, config.getCluster().getSeeds().size());
        assertEquals("node1:7100", config.getCluster().getSeeds().get(0));
        assertEquals(2, config.getCluster().getReplicationFactor());
        assertEquals("/tmp/ngrid-data", config.getCluster().getDataDirectory());
    }

    @Test
    @Order(3)
    @DisplayName("T3: YAML vazio retorna null — Jackson não cria objeto para documento vazio")
    void testEmptyYamlReturnsNull() throws IOException {
        String yaml = "---\n";

        File configFile = tempDir.resolve("adapter-empty.yaml").toFile();
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(yaml);
        }

        ServerConfiguration config = yamlMapper.readValue(configFile, ServerConfiguration.class);

        // Jackson retorna null para documento YAML vazio (apenas "---")
        // Isso é o comportamento esperado — ConfigurationManager trata esse caso
        // criando uma configuração default quando config == null
        assertNull(config, "Jackson should return null for empty YAML document");
    }

    @Test
    @Order(4)
    @DisplayName("T4: Roundtrip — serializar e desserializar mantém integridade")
    void testSerializationRoundtrip() throws IOException {
        // Criar config programaticamente
        ServerConfiguration original = new ServerConfiguration();
        EndPointConfiguration ep = new EndPointConfiguration();
        ep.setRuleMapping("test/Rules.groovy");

        EndPointListenersConfiguration listener = new EndPointListenersConfiguration();
        listener.setListenAddress("0.0.0.0");
        listener.setListenPort(8080);
        listener.getUrlContexts().put("api", new EndPointURLContext("/*", "ANY", "test/Rules.groovy"));

        BackendConfiguration backend = new BackendConfiguration();
        backend.setBackendName("test-backend");
        backend.getMembers().add(new UpstreamMemberConfiguration("http://localhost:3000"));

        ep.getListeners().put("http", listener);
        ep.getBackends().put("test-backend", backend);
        original.getEndpoints().put("test", ep);

        // Serializar para YAML
        File yamlFile = tempDir.resolve("roundtrip.yaml").toFile();
        yamlMapper.writeValue(yamlFile, original);

        // Desserializar
        ServerConfiguration restored = yamlMapper.readValue(yamlFile, ServerConfiguration.class);

        // Verificar integridade
        assertTrue(restored.getEndpoints().containsKey("test"));
        EndPointConfiguration restoredEp = restored.getEndpoints().get("test");
        assertEquals("test/Rules.groovy", restoredEp.getRuleMapping());
        assertTrue(restoredEp.getListeners().containsKey("http"));
        assertEquals(8080, restoredEp.getListeners().get("http").getListenPort());
        assertTrue(restoredEp.getBackends().containsKey("test-backend"));
        assertEquals("http://localhost:3000",
                restoredEp.getBackends().get("test-backend").getMembers().get(0).getUrl());
    }

    @Test
    @Order(5)
    @DisplayName("T5: YAML com virtualHosts é parseado corretamente")
    void testVirtualHostsParsing() throws IOException {
        String yaml = """
                ---
                endpoints:
                  default:
                    listeners:
                      http:
                        listenAddress: "0.0.0.0"
                        listenPort: 8080
                        ssl: false
                        secured: false
                        defaultBackend: "fallback"
                        virtualHosts:
                          "api.example.com": "api-backend"
                          "admin.example.com": "admin-backend"
                          "*.cdn.example.com": "cdn-backend"
                        urlContexts:
                          default:
                            context: "/*"
                            method: "ANY"
                    backends:
                      api-backend:
                        backendName: "api-backend"
                        members:
                          - url: "http://api:3000"
                      admin-backend:
                        backendName: "admin-backend"
                        members:
                          - url: "http://admin:3001"
                      cdn-backend:
                        backendName: "cdn-backend"
                        members:
                          - url: "http://cdn:3002"
                      fallback:
                        backendName: "fallback"
                        members:
                          - url: "http://fallback:3003"
                    ruleMapping: "default/Rules.groovy"
                """;

        File configFile = tempDir.resolve("adapter-vhost.yaml").toFile();
        try (FileWriter writer = new FileWriter(configFile)) {
            writer.write(yaml);
        }

        ServerConfiguration config = yamlMapper.readValue(configFile, ServerConfiguration.class);

        EndPointListenersConfiguration listener = config.getEndpoints().get("default")
                .getListeners().get("http");
        assertNotNull(listener.getVirtualHosts(), "virtualHosts should not be null");
        assertEquals(3, listener.getVirtualHosts().size());
        assertEquals("api-backend", listener.getVirtualHosts().get("api.example.com"));
        assertEquals("admin-backend", listener.getVirtualHosts().get("admin.example.com"));
        assertEquals("cdn-backend", listener.getVirtualHosts().get("*.cdn.example.com"));
        assertEquals("fallback", listener.getDefaultBackend());
    }
}
