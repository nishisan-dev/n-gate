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
 * Configuração de um membro individual do upstream pool.
 * <p>
 * Cada membro representa um servidor backend real com URL, prioridade e peso
 * para balanceamento de carga.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-10
 */
public class UpstreamMemberConfiguration {

    /**
     * URL completa do membro (ex: "http://10.0.0.1:8080")
     */
    private String url;

    /**
     * Prioridade do membro. Menor valor = maior prioridade.
     * Membros de prioridade mais alta (menor número) são preferidos.
     * Membros de prioridade inferior só são usados se todos de prioridade
     * superior estiverem indisponíveis.
     */
    private int priority = 1;

    /**
     * Peso para distribuição de carga via Weighted Round-Robin.
     * Um membro com weight=5 recebe ~5x mais requests que um com weight=1,
     * dentro do mesmo grupo de prioridade.
     */
    private int weight = 1;

    /**
     * Se false, o membro é ignorado pelo pool (equivalente a "down" no NGINX).
     * Útil para manutenção sem remover o membro da configuração.
     */
    private boolean enabled = true;

    public UpstreamMemberConfiguration() {
    }

    public UpstreamMemberConfiguration(String url) {
        this.url = url;
    }

    public UpstreamMemberConfiguration(String url, int priority, int weight) {
        this.url = url;
        this.priority = priority;
        this.weight = weight;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String toString() {
        return "UpstreamMember{url='" + url + "', priority=" + priority
                + ", weight=" + weight + ", enabled=" + enabled + "}";
    }
}
