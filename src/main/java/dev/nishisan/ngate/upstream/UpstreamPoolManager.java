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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gerencia instâncias de {@link UpstreamPool} por nome de backend.
 * <p>
 * Centraliza a criação e acesso aos pools, sendo o ponto de entrada
 * principal para o {@code HttpProxyManager} consultar qual membro usar.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-10
 */
@Component
public class UpstreamPoolManager {

    private static final Logger logger = LogManager.getLogger(UpstreamPoolManager.class);

    private final Map<String, UpstreamPool> pools = new ConcurrentHashMap<>();

    /**
     * Inicializa os pools a partir da configuração de backends.
     * Deve ser chamado após o carregamento da configuração (adapter.yaml).
     *
     * @param backends mapa de backendName → BackendConfiguration
     */
    public void initialize(Map<String, BackendConfiguration> backends) {
        pools.clear();
        backends.forEach((name, config) -> {
            if (config.getMembers() == null || config.getMembers().isEmpty()) {
                logger.warn("Backend '{}' has no members configured — skipping pool creation", name);
                return;
            }
            UpstreamPool pool = new UpstreamPool(config);
            pools.put(name, pool);
            logger.info("Upstream pool '{}' created: {} member(s), strategy={}",
                    name, pool.size(), pool.getStrategy());
        });

        logger.info("UpstreamPoolManager initialized with {} pool(s): {}",
                pools.size(), pools.keySet());
    }

    /**
     * Obtém o pool para o backend especificado.
     *
     * @param backendName nome do backend
     * @return o pool, ou {@link Optional#empty()} se não configurado
     */
    public Optional<UpstreamPool> getPool(String backendName) {
        return Optional.ofNullable(pools.get(backendName));
    }

    /**
     * Seleciona o próximo membro saudável para o backend especificado.
     *
     * @param backendName nome do backend
     * @return membro selecionado, ou {@link Optional#empty()} se:
     *         <ul>
     *           <li>Backend não tem pool configurado</li>
     *           <li>Todos os membros estão down</li>
     *         </ul>
     */
    public Optional<UpstreamMemberState> selectMember(String backendName) {
        UpstreamPool pool = pools.get(backendName);
        if (pool == null) {
            logger.warn("No upstream pool found for backend '{}'", backendName);
            return Optional.empty();
        }
        return pool.selectMember();
    }

    /**
     * @return mapa de todos os pools (imutável, para diagnóstico)
     */
    public Map<String, UpstreamPool> getAllPools() {
        return Map.copyOf(pools);
    }
}
