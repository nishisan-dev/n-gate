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
package dev.nishisan.ngate.configuration;

/**
 * Configuração do active health check para upstream pools.
 * <p>
 * Quando habilitado, uma Virtual Thread periódica faz probing em cada membro
 * do pool para determinar se está saudável antes de receber tráfego real.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-10
 */
public class UpstreamHealthCheckConfiguration {

    /**
     * Se true, o health check ativo está habilitado para este pool.
     */
    private boolean enabled = false;

    /**
     * Path HTTP para o probe (ex: "/health", "/status", "/ping").
     */
    private String path = "/health";

    /**
     * Intervalo entre probes em segundos.
     */
    private int intervalSeconds = 10;

    /**
     * Timeout do probe HTTP em milissegundos.
     */
    private int timeoutMs = 3000;

    /**
     * Número de falhas consecutivas para marcar o membro como DOWN.
     */
    private int unhealthyThreshold = 3;

    /**
     * Número de sucessos consecutivos para marcar o membro como UP
     * após ter sido marcado como DOWN.
     */
    private int healthyThreshold = 2;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    public void setIntervalSeconds(int intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getUnhealthyThreshold() {
        return unhealthyThreshold;
    }

    public void setUnhealthyThreshold(int unhealthyThreshold) {
        this.unhealthyThreshold = unhealthyThreshold;
    }

    public int getHealthyThreshold() {
        return healthyThreshold;
    }

    public void setHealthyThreshold(int healthyThreshold) {
        this.healthyThreshold = healthyThreshold;
    }

    @Override
    public String toString() {
        return "HealthCheck{enabled=" + enabled + ", path='" + path
                + "', interval=" + intervalSeconds + "s, timeout=" + timeoutMs
                + "ms, unhealthyThreshold=" + unhealthyThreshold
                + ", healthyThreshold=" + healthyThreshold + "}";
    }
}
