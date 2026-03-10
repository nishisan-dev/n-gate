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

import dev.nishisan.ngate.configuration.UpstreamMemberConfiguration;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Estado runtime de um membro do upstream pool.
 * <p>
 * Combina a configuração estática ({@link UpstreamMemberConfiguration}) com
 * estado dinâmico de saúde: healthy/unhealthy, contadores de falhas/sucessos
 * consecutivos para o health checker.
 * <p>
 * Thread-safe via operações atômicas.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-10
 */
public class UpstreamMemberState {

    private final UpstreamMemberConfiguration config;
    private final AtomicBoolean healthy = new AtomicBoolean(true);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger consecutiveSuccesses = new AtomicInteger(0);

    public UpstreamMemberState(UpstreamMemberConfiguration config) {
        this.config = config;
    }

    /**
     * @return a configuração estática do membro
     */
    public UpstreamMemberConfiguration getConfig() {
        return config;
    }

    /**
     * @return URL do membro (shortcut para config.getUrl())
     */
    public String getUrl() {
        return config.getUrl();
    }

    /**
     * @return true se o membro está saudável e habilitado
     */
    public boolean isAvailable() {
        return config.isEnabled() && healthy.get();
    }

    /**
     * @return true se o membro está marcado como saudável
     */
    public boolean isHealthy() {
        return healthy.get();
    }

    /**
     * Marca o membro como saudável. Reseta contadores de falha.
     */
    public void markHealthy() {
        healthy.set(true);
        consecutiveFailures.set(0);
    }

    /**
     * Marca o membro como não saudável. Reseta contadores de sucesso.
     */
    public void markUnhealthy() {
        healthy.set(false);
        consecutiveSuccesses.set(0);
    }

    /**
     * Registra uma falha de health check.
     *
     * @return número de falhas consecutivas após esta
     */
    public int recordFailure() {
        consecutiveSuccesses.set(0);
        return consecutiveFailures.incrementAndGet();
    }

    /**
     * Registra um sucesso de health check.
     *
     * @return número de sucessos consecutivos após este
     */
    public int recordSuccess() {
        consecutiveFailures.set(0);
        return consecutiveSuccesses.incrementAndGet();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public int getConsecutiveSuccesses() {
        return consecutiveSuccesses.get();
    }

    @Override
    public String toString() {
        return "MemberState{url='" + config.getUrl()
                + "', priority=" + config.getPriority()
                + ", weight=" + config.getWeight()
                + ", healthy=" + healthy.get()
                + ", enabled=" + config.isEnabled() + "}";
    }
}
