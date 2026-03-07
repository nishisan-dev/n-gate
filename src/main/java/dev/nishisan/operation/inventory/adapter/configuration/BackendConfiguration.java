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
package dev.nishisan.operation.inventory.adapter.configuration;

import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 05.01.2023
 */
public class BackendConfiguration {

    private String backendName;
    private String xOriginalHost;
    private String endPointUrl;
    private Map<String, String> defaultHeaders = new HashMap<>();
    private OauthServerClientConfiguration oauthClientConfig;

    public Map<String, String> getDefaultHeaders() {
        return defaultHeaders;
    }

    public void setDefaultHeaders(Map<String, String> defaultHeaders) {
        this.defaultHeaders = defaultHeaders;
    }

    /**
     * @return the backendName
     */
    public String getBackendName() {
        return backendName;
    }

    /**
     * @param backendName the backendName to set
     */
    public void setBackendName(String backendName) {
        this.backendName = backendName;
    }

    /**
     * @return the endPointUrl
     */
    public String getEndPointUrl() {
        return endPointUrl;
    }

    /**
     * @param endPointUrl the endPointUrl to set
     */
    public void setEndPointUrl(String endPointUrl) {
        this.endPointUrl = endPointUrl;
    }

    /**
     * @return the oauthClientConfig
     */
    public OauthServerClientConfiguration getOauthClientConfig() {
        return oauthClientConfig;
    }

    /**
     * @param oauthClientConfig the oauthClientConfig to set
     */
    public void setOauthClientConfig(OauthServerClientConfiguration oauthClientConfig) {
        this.oauthClientConfig = oauthClientConfig;
    }

    /**
     * @return the xOriginalHost
     */
    public String getxOriginalHost() {
        return xOriginalHost;
    }

    /**
     * @param xOriginalHost the xOriginalHost to set
     */
    public void setxOriginalHost(String xOriginalHost) {
        this.xOriginalHost = xOriginalHost;
    }
}
