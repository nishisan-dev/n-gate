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
package dev.nishisan.ngate.upstream;

import dev.nishisan.ngate.configuration.BackendConfiguration;
import dev.nishisan.ngate.configuration.UpstreamHealthCheckConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Gerencia os membros de um upstream pool e implementa a seleção de membros
 * baseada em strategy (round-robin, failover, random) com priority groups.
 * <p>
 * A seleção segue a ordem:
 * <ol>
 *   <li>Filtra membros disponíveis (enabled + healthy)</li>
 *   <li>Agrupa por prioridade e seleciona o tier de menor valor numérico</li>
 *   <li>Dentro do tier, aplica a estratégia configurada</li>
 * </ol>
 * <p>
 * Thread-safe: round-robin usa {@link AtomicInteger}, maps são imutáveis após construção.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-10
 */
public class UpstreamPool {

    private static final Logger logger = LogManager.getLogger(UpstreamPool.class);

    private final String backendName;
    private final String strategy;
    private final List<UpstreamMemberState> allMembers;
    private final UpstreamHealthCheckConfiguration healthCheckConfig;

    /**
     * Membros agrupados por prioridade (TreeMap para ordem natural).
     * Key = priority (1 = mais prioritário), Value = membros desse tier.
     */
    private final TreeMap<Integer, List<UpstreamMemberState>> membersByPriority;

    /**
     * Contador para Weighted Round-Robin. Cada tier tem seu próprio counter
     * para evitar interferência entre tiers.
     */
    private final Map<Integer, AtomicInteger> roundRobinCounters = new HashMap<>();

    public UpstreamPool(BackendConfiguration config) {
        this.backendName = config.getBackendName();
        this.strategy = config.getStrategy() != null ? config.getStrategy() : "round-robin";
        this.healthCheckConfig = config.getHealthCheck();

        // Cria os estados dos membros
        this.allMembers = config.getMembers().stream()
                .map(UpstreamMemberState::new)
                .collect(Collectors.toList());

        // Agrupa por prioridade usando TreeMap (ordem natural)
        this.membersByPriority = new TreeMap<>();
        for (UpstreamMemberState member : allMembers) {
            membersByPriority
                    .computeIfAbsent(member.getConfig().getPriority(), k -> new ArrayList<>())
                    .add(member);
        }

        // Inicializa um round-robin counter por tier
        for (Integer priority : membersByPriority.keySet()) {
            roundRobinCounters.put(priority, new AtomicInteger(0));
        }

        logger.info("UpstreamPool '{}' initialized: strategy={}, members={}, tiers={}",
                backendName, strategy, allMembers.size(), membersByPriority.keySet());
    }

    /**
     * Seleciona o próximo membro saudável do pool, de acordo com a strategy.
     *
     * @return membro selecionado, ou {@link Optional#empty()} se todos estiverem down
     */
    public Optional<UpstreamMemberState> selectMember() {
        // Itera pelos tiers em ordem de prioridade (menor = melhor)
        for (Map.Entry<Integer, List<UpstreamMemberState>> entry : membersByPriority.entrySet()) {
            int priority = entry.getKey();
            List<UpstreamMemberState> tierMembers = entry.getValue();

            // Filtra membros disponíveis no tier
            List<UpstreamMemberState> available = tierMembers.stream()
                    .filter(UpstreamMemberState::isAvailable)
                    .collect(Collectors.toList());

            if (available.isEmpty()) {
                logger.debug("Pool '{}': tier {} has no available members, trying next tier",
                        backendName, priority);
                continue;
            }

            // Aplica a estratégia dentro do tier
            return Optional.of(switch (strategy) {
                case "failover" -> selectFailover(available);
                case "random" -> selectRandom(available);
                default -> selectWeightedRoundRobin(available, priority);
            });
        }

        logger.warn("Pool '{}': ALL members are DOWN — no available upstream", backendName);
        return Optional.empty();
    }

    /**
     * Weighted Round-Robin: expande membros proporcionalmente ao weight e
     * seleciona sequencialmente usando um counter atômico.
     */
    private UpstreamMemberState selectWeightedRoundRobin(
            List<UpstreamMemberState> available, int priority) {

        // Constrói a lista expandida por peso
        List<UpstreamMemberState> expanded = new ArrayList<>();
        for (UpstreamMemberState m : available) {
            for (int i = 0; i < m.getConfig().getWeight(); i++) {
                expanded.add(m);
            }
        }

        AtomicInteger counter = roundRobinCounters.get(priority);
        int index = Math.abs(counter.getAndIncrement()) % expanded.size();
        UpstreamMemberState selected = expanded.get(index);

        logger.debug("Pool '{}': round-robin selected {} (tier {}, index {}/{})",
                backendName, selected.getUrl(), priority, index, expanded.size());
        return selected;
    }

    /**
     * Failover: retorna sempre o primeiro membro disponível (ordem de inserção).
     */
    private UpstreamMemberState selectFailover(List<UpstreamMemberState> available) {
        UpstreamMemberState selected = available.get(0);
        logger.debug("Pool '{}': failover selected {}", backendName, selected.getUrl());
        return selected;
    }

    /**
     * Random: seleção aleatória ponderada pelo weight.
     */
    private UpstreamMemberState selectRandom(List<UpstreamMemberState> available) {
        // Constrói lista expandida por peso
        List<UpstreamMemberState> expanded = new ArrayList<>();
        for (UpstreamMemberState m : available) {
            for (int i = 0; i < m.getConfig().getWeight(); i++) {
                expanded.add(m);
            }
        }

        UpstreamMemberState selected = expanded.get(
                ThreadLocalRandom.current().nextInt(expanded.size()));
        logger.debug("Pool '{}': random selected {}", backendName, selected.getUrl());
        return selected;
    }

    /**
     * @return todos os membros do pool (para iteração pelo health checker)
     */
    public List<UpstreamMemberState> getAllMembers() {
        return Collections.unmodifiableList(allMembers);
    }

    /**
     * @return nome do backend
     */
    public String getBackendName() {
        return backendName;
    }

    /**
     * @return estratégia de balanceamento
     */
    public String getStrategy() {
        return strategy;
    }

    /**
     * @return número total de membros no pool
     */
    public int size() {
        return allMembers.size();
    }

    /**
     * @return número de membros disponíveis (enabled + healthy)
     */
    public long availableCount() {
        return allMembers.stream().filter(UpstreamMemberState::isAvailable).count();
    }

    /**
     * @return configuração do health check, ou null se não configurado
     */
    public UpstreamHealthCheckConfiguration getHealthCheckConfig() {
        return healthCheckConfig;
    }
}
