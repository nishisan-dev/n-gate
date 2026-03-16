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

import java.util.ArrayList;
import java.util.List;

/**
 * Configuração do Dashboard de observabilidade (bloco {@code dashboard:} do adapter.yaml).
 * <p>
 * Controla porta dedicada, IP allowlist, storage H2 e integração Zipkin.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
public class DashboardConfiguration {

    private boolean enabled = false;
    private int port = 9200;
    private String bindAddress = "0.0.0.0";
    private List<String> allowedIps = new ArrayList<>(List.of("127.0.0.1", "::1"));
    private DashboardStorageConfiguration storage = new DashboardStorageConfiguration();
    private DashboardZipkinConfiguration zipkin = new DashboardZipkinConfiguration();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }

    public List<String> getAllowedIps() {
        return allowedIps;
    }

    public void setAllowedIps(List<String> allowedIps) {
        this.allowedIps = allowedIps;
    }

    public DashboardStorageConfiguration getStorage() {
        return storage;
    }

    public void setStorage(DashboardStorageConfiguration storage) {
        this.storage = storage;
    }

    public DashboardZipkinConfiguration getZipkin() {
        return zipkin;
    }

    public void setZipkin(DashboardZipkinConfiguration zipkin) {
        this.zipkin = zipkin;
    }

    /**
     * Configuração do storage H2 embedded.
     */
    public static class DashboardStorageConfiguration {
        private String type = "h2";
        private String path = "./data/dashboard";
        private int retentionHours = 168; // 7 dias
        private int scrapeIntervalSeconds = 60;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public int getRetentionHours() {
            return retentionHours;
        }

        public void setRetentionHours(int retentionHours) {
            this.retentionHours = retentionHours;
        }

        public int getScrapeIntervalSeconds() {
            return scrapeIntervalSeconds;
        }

        public void setScrapeIntervalSeconds(int scrapeIntervalSeconds) {
            this.scrapeIntervalSeconds = scrapeIntervalSeconds;
        }
    }

    /**
     * Configuração da integração com Zipkin.
     */
    public static class DashboardZipkinConfiguration {
        private boolean enabled = true;
        private String baseUrl = "http://localhost:9411";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
}
