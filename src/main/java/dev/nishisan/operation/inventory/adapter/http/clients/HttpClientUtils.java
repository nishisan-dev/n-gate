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
package dev.nishisan.operation.inventory.adapter.http.clients;

import dev.nishisan.operation.inventory.adapter.http.HttpProxyManager;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura at gmail.com>
 * created 08.08.2024
 */
public class HttpClientUtils {

    private final HttpProxyManager proxyManager;

    public HttpClientUtils(HttpProxyManager proxyManager) {
        this.proxyManager = proxyManager;
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
