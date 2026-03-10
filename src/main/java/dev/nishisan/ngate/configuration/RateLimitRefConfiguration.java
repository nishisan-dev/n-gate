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
 * Referência a uma zona de rate limiting, usada nos 3 escopos:
 * listener, rota (urlContext) e backend.
 * <p>
 * Exemplo YAML:
 * <pre>
 * rateLimit:
 *   zone: "api-global"
 *   mode: "nowait"     # opcional, override do defaultMode
 * </pre>
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-10
 */
public class RateLimitRefConfiguration {

    /**
     * Nome da zona definida em {@code rateLimiting.zones}.
     */
    private String zone;

    /**
     * Override de modo para este escopo.
     * Se null, usa {@code rateLimiting.defaultMode}.
     * Valores: "stall" | "nowait".
     */
    private String mode;

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }
}
