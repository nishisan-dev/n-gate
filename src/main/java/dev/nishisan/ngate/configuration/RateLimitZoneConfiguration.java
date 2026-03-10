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
 * Configuração de uma zona individual de rate limiting.
 * Equivalente a uma {@code limit_req_zone} do Nginx.
 * <p>
 * Mapeada do bloco {@code rateLimiting.zones.<name>} no {@code adapter.yaml}.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-10
 */
public class RateLimitZoneConfiguration {

    /**
     * Número de requests permitidos por período de refresh.
     */
    private int limitForPeriod = 100;

    /**
     * Duração do período de refresh em segundos.
     * Ex: limitForPeriod=100, limitRefreshPeriodSeconds=1 → 100 req/s.
     */
    private int limitRefreshPeriodSeconds = 1;

    /**
     * Tempo máximo de espera (em segundos) quando o modo é "stall".
     * Se o slot não for liberado dentro deste tempo, o request é rejeitado.
     */
    private int timeoutSeconds = 5;

    public int getLimitForPeriod() {
        return limitForPeriod;
    }

    public void setLimitForPeriod(int limitForPeriod) {
        this.limitForPeriod = limitForPeriod;
    }

    public int getLimitRefreshPeriodSeconds() {
        return limitRefreshPeriodSeconds;
    }

    public void setLimitRefreshPeriodSeconds(int limitRefreshPeriodSeconds) {
        this.limitRefreshPeriodSeconds = limitRefreshPeriodSeconds;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
