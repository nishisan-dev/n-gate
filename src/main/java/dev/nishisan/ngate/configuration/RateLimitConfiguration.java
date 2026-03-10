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

import java.util.HashMap;
import java.util.Map;

/**
 * Configuração global do rate limiting.
 * Mapeado do bloco {@code rateLimiting:} no {@code adapter.yaml}.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-10
 */
public class RateLimitConfiguration {

    private boolean enabled = false;

    /**
     * Modo padrão aplicado quando uma referência não especifica modo.
     * Valores: "stall" (aguarda slot) | "nowait" (rejeita 429 imediato).
     */
    private String defaultMode = "nowait";

    /**
     * Zonas nomeadas de rate limiting.
     * Chave: nome da zona (ex: "api-global").
     */
    private Map<String, RateLimitZoneConfiguration> zones = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultMode() {
        return defaultMode;
    }

    public void setDefaultMode(String defaultMode) {
        this.defaultMode = defaultMode;
    }

    public Map<String, RateLimitZoneConfiguration> getZones() {
        return zones;
    }

    public void setZones(Map<String, RateLimitZoneConfiguration> zones) {
        this.zones = zones;
    }
}
