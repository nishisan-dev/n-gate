/*
 * Copyright (C) 2026 Lucas Nishimura <lucas.nishimura@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dev.nishisan.ngate.upstream;

import dev.nishisan.ngate.configuration.BackendConfiguration;
import dev.nishisan.ngate.configuration.UpstreamMemberConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários do {@link UpstreamPool}.
 * Valida seleção de membros com priority groups, weighted round-robin,
 * failover e health state.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-10
 */
class UpstreamPoolTest {

    private BackendConfiguration createConfig(String name, String strategy,
                                               UpstreamMemberConfiguration... members) {
        BackendConfiguration config = new BackendConfiguration();
        config.setBackendName(name);
        config.setStrategy(strategy);
        config.setMembers(Arrays.asList(members));
        return config;
    }

    private UpstreamMemberConfiguration member(String url, int priority, int weight) {
        return new UpstreamMemberConfiguration(url, priority, weight);
    }

    // --- T1: Pool com 1 membro retorna sempre o mesmo ---
    @Test
    @DisplayName("T1: Pool com 1 membro retorna sempre o mesmo membro")
    void singleMemberAlwaysReturned() {
        UpstreamPool pool = new UpstreamPool(
                createConfig("test", "round-robin",
                        member("http://a:8080", 1, 1)));

        for (int i = 0; i < 10; i++) {
            Optional<UpstreamMemberState> selected = pool.selectMember();
            assertTrue(selected.isPresent());
            assertEquals("http://a:8080", selected.get().getUrl());
        }
    }

    // --- T2: Weighted Round-Robin distribui proporcionalmente ---
    @Test
    @DisplayName("T2: Weighted round-robin distribui requests proporcionalmente aos pesos")
    void weightedRoundRobinDistribution() {
        UpstreamPool pool = new UpstreamPool(
                createConfig("test", "round-robin",
                        member("http://a:8080", 1, 3),   // weight 3
                        member("http://b:8080", 1, 1))); // weight 1

        Map<String, Integer> counts = new HashMap<>();
        int totalRequests = 400; // múltiplo de 4 (3+1)

        for (int i = 0; i < totalRequests; i++) {
            Optional<UpstreamMemberState> selected = pool.selectMember();
            assertTrue(selected.isPresent());
            counts.merge(selected.get().getUrl(), 1, Integer::sum);
        }

        // Com weight 3:1, esperamos ~75% para 'a' e ~25% para 'b'
        int aCount = counts.getOrDefault("http://a:8080", 0);
        int bCount = counts.getOrDefault("http://b:8080", 0);

        assertEquals(totalRequests, aCount + bCount, "Todos os requests devem ser distribuídos");

        // Proporção exata: 300 e 100 (round-robin determinístico)
        assertEquals(300, aCount, "A (weight=3) deve receber 3/4 dos requests");
        assertEquals(100, bCount, "B (weight=1) deve receber 1/4 dos requests");
    }

    // --- T3: Membro unhealthy não é selecionado ---
    @Test
    @DisplayName("T3: Membro marcado como unhealthy não é selecionado")
    void unhealthyMemberSkipped() {
        UpstreamPool pool = new UpstreamPool(
                createConfig("test", "round-robin",
                        member("http://a:8080", 1, 1),
                        member("http://b:8080", 1, 1)));

        // Marca 'a' como unhealthy
        pool.getAllMembers().stream()
                .filter(m -> m.getUrl().equals("http://a:8080"))
                .forEach(UpstreamMemberState::markUnhealthy);

        for (int i = 0; i < 10; i++) {
            Optional<UpstreamMemberState> selected = pool.selectMember();
            assertTrue(selected.isPresent());
            assertEquals("http://b:8080", selected.get().getUrl(),
                    "Apenas o membro healthy deve ser selecionado");
        }
    }

    // --- T4: Fallback para tier 2 quando tier 1 todo down ---
    @Test
    @DisplayName("T4: Todos membros do tier 1 down → fallback para tier 2")
    void fallbackToLowerPriorityTier() {
        UpstreamPool pool = new UpstreamPool(
                createConfig("test", "round-robin",
                        member("http://primary:8080", 1, 1),
                        member("http://backup:8080", 2, 1)));

        // Tier 1 deve ser preferido
        Optional<UpstreamMemberState> selected = pool.selectMember();
        assertTrue(selected.isPresent());
        assertEquals("http://primary:8080", selected.get().getUrl());

        // Marca tier 1 como down
        pool.getAllMembers().stream()
                .filter(m -> m.getConfig().getPriority() == 1)
                .forEach(UpstreamMemberState::markUnhealthy);

        // Agora deve usar tier 2
        selected = pool.selectMember();
        assertTrue(selected.isPresent());
        assertEquals("http://backup:8080", selected.get().getUrl(),
                "Deve fazer fallback para tier 2 quando tier 1 está down");
    }

    // --- T5: Todos membros down retorna empty ---
    @Test
    @DisplayName("T5: Todos membros down → selectMember() retorna empty")
    void allMembersDownReturnsEmpty() {
        UpstreamPool pool = new UpstreamPool(
                createConfig("test", "round-robin",
                        member("http://a:8080", 1, 1),
                        member("http://b:8080", 2, 1)));

        // Marca todos como down
        pool.getAllMembers().forEach(UpstreamMemberState::markUnhealthy);

        Optional<UpstreamMemberState> selected = pool.selectMember();
        assertTrue(selected.isEmpty(), "Deve retornar empty quando todos estão down");
    }

    // --- T6: Membro disabled nunca é selecionado ---
    @Test
    @DisplayName("T6: Membro com enabled=false nunca é selecionado")
    void disabledMemberNeverSelected() {
        UpstreamMemberConfiguration disabled = member("http://disabled:8080", 1, 1);
        disabled.setEnabled(false);

        UpstreamPool pool = new UpstreamPool(
                createConfig("test", "round-robin",
                        disabled,
                        member("http://active:8080", 1, 1)));

        for (int i = 0; i < 10; i++) {
            Optional<UpstreamMemberState> selected = pool.selectMember();
            assertTrue(selected.isPresent());
            assertEquals("http://active:8080", selected.get().getUrl(),
                    "Membro disabled não deve ser selecionado");
        }
    }

    // --- Failover strategy ---
    @Test
    @DisplayName("Failover: sempre seleciona o primeiro membro disponível")
    void failoverSelectsFirst() {
        UpstreamPool pool = new UpstreamPool(
                createConfig("test", "failover",
                        member("http://primary:8080", 1, 1),
                        member("http://secondary:8080", 1, 1)));

        for (int i = 0; i < 10; i++) {
            Optional<UpstreamMemberState> selected = pool.selectMember();
            assertTrue(selected.isPresent());
            assertEquals("http://primary:8080", selected.get().getUrl());
        }

        // Marca primary como down → deve ir para secondary
        pool.getAllMembers().stream()
                .filter(m -> m.getUrl().equals("http://primary:8080"))
                .forEach(UpstreamMemberState::markUnhealthy);

        Optional<UpstreamMemberState> selected = pool.selectMember();
        assertTrue(selected.isPresent());
        assertEquals("http://secondary:8080", selected.get().getUrl());
    }

    // --- Random strategy ---
    @Test
    @DisplayName("Random: todos os membros são selecionados em distribuição estatística")
    void randomSelectsAll() {
        UpstreamPool pool = new UpstreamPool(
                createConfig("test", "random",
                        member("http://a:8080", 1, 1),
                        member("http://b:8080", 1, 1)));

        Set<String> selected = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            pool.selectMember().ifPresent(m -> selected.add(m.getUrl()));
        }

        assertTrue(selected.contains("http://a:8080"), "Random deve selecionar A eventualmente");
        assertTrue(selected.contains("http://b:8080"), "Random deve selecionar B eventualmente");
    }

    // --- Member recovery ---
    @Test
    @DisplayName("Membro recover: marcado UP após estar DOWN volta a ser selecionado")
    void memberRecovery() {
        UpstreamPool pool = new UpstreamPool(
                createConfig("test", "failover",
                        member("http://a:8080", 1, 1),
                        member("http://b:8080", 2, 1)));

        // Marca 'a' down → fallback para 'b'
        pool.getAllMembers().stream()
                .filter(m -> m.getUrl().equals("http://a:8080"))
                .forEach(UpstreamMemberState::markUnhealthy);

        assertEquals("http://b:8080", pool.selectMember().get().getUrl());

        // Recovery de 'a' → volta para 'a' (prioridade maior)
        pool.getAllMembers().stream()
                .filter(m -> m.getUrl().equals("http://a:8080"))
                .forEach(UpstreamMemberState::markHealthy);

        assertEquals("http://a:8080", pool.selectMember().get().getUrl(),
                "Membro recuperado deve voltar a ser selecionado");
    }
}
