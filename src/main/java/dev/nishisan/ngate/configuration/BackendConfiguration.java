/*
 * Copyright (C) 2023 Lucas Nishimura <lucas.nishimura@gmail.com>
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
package dev.nishisan.ngate.configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuração de um backend com upstream pool.
 * <p>
 * Cada backend possui uma lista de {@link UpstreamMemberConfiguration members}
 * que representam os servidores reais. O balanceamento de carga é definido
 * pela {@code strategy} e o health check ativo é opcional.
 * <p>
 * <b>API BREAKER v3.0:</b> O campo {@code endPointUrl} foi removido.
 * Todos os backends devem usar a lista {@code members}.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 05.01.2023
 */
public class BackendConfiguration {

    private String backendName;
    private Map<String, String> defaultHeaders = new HashMap<>();
    private OauthServerClientConfiguration oauthClientConfig;
    private RateLimitRefConfiguration rateLimit;

    /**
     * Lista de membros do upstream pool. Obrigatório — pelo menos 1 membro.
     */
    private List<UpstreamMemberConfiguration> members = new ArrayList<>();

    /**
     * Estratégia de load balancing:
     * <ul>
     *   <li>{@code round-robin} (padrão) — Weighted Round-Robin dentro de cada priority group</li>
     *   <li>{@code failover} — Sempre usa o membro de maior prioridade disponível</li>
     *   <li>{@code random} — Seleção aleatória ponderada pelo weight</li>
     * </ul>
     */
    private String strategy = "round-robin";

    /**
     * Configuração do active health check. Se null ou disabled, só o circuit
     * breaker passivo protege os membros.
     */
    private UpstreamHealthCheckConfiguration healthCheck;

    // --- Getters/Setters ---

    public String getBackendName() {
        return backendName;
    }

    public void setBackendName(String backendName) {
        this.backendName = backendName;
    }

    public Map<String, String> getDefaultHeaders() {
        return defaultHeaders;
    }

    public void setDefaultHeaders(Map<String, String> defaultHeaders) {
        this.defaultHeaders = defaultHeaders;
    }

    public OauthServerClientConfiguration getOauthClientConfig() {
        return oauthClientConfig;
    }

    public void setOauthClientConfig(OauthServerClientConfiguration oauthClientConfig) {
        this.oauthClientConfig = oauthClientConfig;
    }

    public RateLimitRefConfiguration getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimitRefConfiguration rateLimit) {
        this.rateLimit = rateLimit;
    }

    public List<UpstreamMemberConfiguration> getMembers() {
        return members;
    }

    public void setMembers(List<UpstreamMemberConfiguration> members) {
        this.members = members;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public UpstreamHealthCheckConfiguration getHealthCheck() {
        return healthCheck;
    }

    public void setHealthCheck(UpstreamHealthCheckConfiguration healthCheck) {
        this.healthCheck = healthCheck;
    }
}
