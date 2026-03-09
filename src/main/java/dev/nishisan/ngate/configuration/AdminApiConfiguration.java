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
 * Configuração do Admin API (bloco {@code admin:} do adapter.yaml).
 * <p>
 * Controla a habilitação e autenticação dos endpoints administrativos
 * como deploy de rules e consulta de versão do bundle ativo.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-09
 */
public class AdminApiConfiguration {

    private boolean enabled = false;
    private String apiKey;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
