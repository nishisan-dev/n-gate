/*
 * Copyright (C) 2026 Lucas Nishimura <lucas.nishimura@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dev.nishisan.ngate.upstream;

import dev.nishisan.ngate.configuration.UpstreamHealthCheckConfiguration;
import dev.nishisan.ngate.configuration.UpstreamMemberConfiguration;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários do {@link UpstreamHealthChecker}.
 * Usa MockWebServer para simular probes de health check.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-10
 */
class UpstreamHealthCheckerTest {

    private MockWebServer server;
    private OkHttpClient probeClient;
    private UpstreamHealthCheckConfiguration hcConfig;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        probeClient = new OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(1, TimeUnit.SECONDS)
                .build();

        hcConfig = new UpstreamHealthCheckConfiguration();
        hcConfig.setEnabled(true);
        hcConfig.setPath("/health");
        hcConfig.setUnhealthyThreshold(3);
        hcConfig.setHealthyThreshold(2);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private UpstreamMemberState createMember() {
        String url = server.url("").toString();
        // Remove trailing slash
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return new UpstreamMemberState(new UpstreamMemberConfiguration(url));
    }

    @Test
    @DisplayName("T1: Probe bem-sucedido mantém membro healthy")
    void successfulProbeKeepsMemberHealthy() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("OK"));

        UpstreamMemberState member = createMember();
        UpstreamHealthChecker checker = new UpstreamHealthChecker();

        checker.probeMember(probeClient, hcConfig, member, "test-pool");

        assertTrue(member.isHealthy(), "Membro deve permanecer healthy após probe OK");
        assertEquals(1, member.getConsecutiveSuccesses());
        assertEquals(0, member.getConsecutiveFailures());
    }

    @Test
    @DisplayName("T2: Após unhealthyThreshold falhas consecutivas → marca DOWN")
    void consecutiveFailuresMarkDown() {
        // Enfileira 3 respostas 500
        for (int i = 0; i < 3; i++) {
            server.enqueue(new MockResponse().setResponseCode(500).setBody("Error"));
        }

        UpstreamMemberState member = createMember();
        UpstreamHealthChecker checker = new UpstreamHealthChecker();

        // Primeiras 2 falhas: ainda healthy
        checker.probeMember(probeClient, hcConfig, member, "test-pool");
        assertTrue(member.isHealthy(), "Ainda healthy após 1 falha");

        checker.probeMember(probeClient, hcConfig, member, "test-pool");
        assertTrue(member.isHealthy(), "Ainda healthy após 2 falhas");

        // 3ª falha: unhealthyThreshold atingido → DOWN
        checker.probeMember(probeClient, hcConfig, member, "test-pool");
        assertFalse(member.isHealthy(), "Deve estar DOWN após 3 falhas consecutivas");
    }

    @Test
    @DisplayName("T3: Após healthyThreshold sucessos quando DOWN → marca UP")
    void consecutiveSuccessesAfterDownMarkUp() {
        // Enfileira 2 respostas 200
        for (int i = 0; i < 2; i++) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("OK"));
        }

        UpstreamMemberState member = createMember();
        member.markUnhealthy(); // Começa como DOWN
        UpstreamHealthChecker checker = new UpstreamHealthChecker();

        // 1º sucesso: ainda DOWN
        checker.probeMember(probeClient, hcConfig, member, "test-pool");
        assertFalse(member.isHealthy(), "Ainda DOWN após 1 sucesso");

        // 2º sucesso: healthyThreshold atingido → UP
        checker.probeMember(probeClient, hcConfig, member, "test-pool");
        assertTrue(member.isHealthy(), "Deve estar UP após 2 sucessos consecutivos");
    }

    @Test
    @DisplayName("Falha de conexão conta como probe failure")
    void connectionFailureCountsAsFailure() {
        UpstreamMemberState member = new UpstreamMemberState(
                new UpstreamMemberConfiguration("http://192.0.2.1:1")); // endereço impossível

        OkHttpClient fastClient = new OkHttpClient.Builder()
                .connectTimeout(100, TimeUnit.MILLISECONDS)
                .readTimeout(100, TimeUnit.MILLISECONDS)
                .callTimeout(500, TimeUnit.MILLISECONDS)
                .build();

        UpstreamHealthChecker checker = new UpstreamHealthChecker();

        // 3 falhas de conexão
        for (int i = 0; i < 3; i++) {
            checker.probeMember(fastClient, hcConfig, member, "test-pool");
        }

        assertFalse(member.isHealthy(), "Deve estar DOWN após falhas de conexão");
    }

    @Test
    @DisplayName("Sucesso reseta contagem de falhas")
    void successResetsFailureCount() {
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(200)); // sucesso reseta
        server.enqueue(new MockResponse().setResponseCode(500));
        server.enqueue(new MockResponse().setResponseCode(500));

        UpstreamMemberState member = createMember();
        UpstreamHealthChecker checker = new UpstreamHealthChecker();

        // 2 falhas
        checker.probeMember(probeClient, hcConfig, member, "test-pool");
        checker.probeMember(probeClient, hcConfig, member, "test-pool");
        assertEquals(2, member.getConsecutiveFailures());

        // 1 sucesso reseta falhas
        checker.probeMember(probeClient, hcConfig, member, "test-pool");
        assertEquals(0, member.getConsecutiveFailures());
        assertTrue(member.isHealthy());

        // 2 falhas novamente (não atinge threshold)
        checker.probeMember(probeClient, hcConfig, member, "test-pool");
        checker.probeMember(probeClient, hcConfig, member, "test-pool");
        assertTrue(member.isHealthy(), "Ainda healthy — threshold não atingido");
    }
}
