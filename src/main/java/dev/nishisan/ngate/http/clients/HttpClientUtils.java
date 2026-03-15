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
package dev.nishisan.ngate.http.clients;

import dev.nishisan.ngate.configuration.BackendConfiguration;
import dev.nishisan.ngate.http.HttpProxyManager;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura at gmail.com>
 * created 08.08.2024
 */
public class HttpClientUtils {

    private static final Logger logger = LogManager.getLogger(HttpClientUtils.class);
    private final HttpProxyManager proxyManager;

    public HttpClientUtils(HttpProxyManager proxyManager) {
        this.proxyManager = proxyManager;
    }

    /**
     * Retorna um {@link BackendClient} fluent para o backend configurado.
     * <p>
     * A base URL é resolvida automaticamente a partir da configuração YAML
     * ({@code backends.<name>.members[0].url}) e o OkHttpClient já vem
     * configurado com interceptors de autenticação OAuth (se aplicável).
     * <p>
     * Uso no Groovy:
     * <pre>
     * def res = utils.backend("validator").post("/check", body)
     * </pre>
     *
     * @param name nome do backend conforme definido em adapter.yaml
     * @return BackendClient pronto para uso, ou null se o backend não existir
     */
    public BackendClient backend(String name) {
        BackendConfiguration backendConfig = proxyManager.getConfiguration().getBackends().get(name);
        if (backendConfig == null || backendConfig.getMembers().isEmpty()) {
            logger.error("Backend [{}] not found or has no members configured", name);
            return null;
        }
        String baseUrl = backendConfig.getMembers().get(0).getUrl();
        try {
            return new BackendClient(proxyManager.getHttpClientByListenerName(name), baseUrl);
        } catch (KeyManagementException | NoSuchAlgorithmException ex) {
            logger.error("Failed to create BackendClient for [{}]", name, ex);
            return null;
        }
    }

    public HttpClientWrapper getClient() {
        String uid = UUID.randomUUID().toString();
        uid = "AUTO-" + uid + "-TMP";
        try {
            return new HttpClientWrapper(this.proxyManager.getHttpClientByListenerName(uid));
        } catch (KeyManagementException | NoSuchAlgorithmException ex) {
            return null;
        }
    }

    public HttpClientWrapper getSyncBackend(String name) {
        try {
            return new HttpClientWrapper(this.proxyManager.getHttpClientByListenerName(name));
        } catch (KeyManagementException | NoSuchAlgorithmException ex) {
            return null;
        }
    }

    public AssyncHTTPClient getAssyncBackend(String name) {
        try {
            return new AssyncHTTPClient(this.proxyManager.getHttpClientByListenerName(name));
        } catch (KeyManagementException | NoSuchAlgorithmException ex) {
            return null;
        }
    }

}
