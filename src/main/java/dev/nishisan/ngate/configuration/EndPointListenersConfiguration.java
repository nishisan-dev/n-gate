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
package dev.nishisan.ngate.configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 05.01.2023
 */
public class EndPointListenersConfiguration {

    private String listenAddress = "0.0.0.0";
    private Integer listenPort = 8080;
    private Boolean ssl = false;
    private Boolean scriptOnly = false;
    private String defaultScript = "Default.groovy";
    private String defaultBackend;
    private Boolean secured = false;
    private SecureProviderConfig secureProvider;
    private Map<String, EndPointURLContext> urlContexts = new ConcurrentHashMap<>();
    private SSLListenerConfiguration sslConfiguration = new SSLListenerConfiguration();

    /**
     * Virtual hosts: mapping de serverName → backendName.
     * Quando o header Host da request casa com um serverName, o backend
     * correspondente é utilizado ao invés do defaultBackend.
     * Suporta exact match e wildcards (ex: "*.example.com").
     */
    private Map<String, String> virtualHosts = new LinkedHashMap<>();

    public Boolean getScriptOnly() {
        return scriptOnly;
    }

    public void setScriptOnly(Boolean scriptOnly) {
        this.scriptOnly = scriptOnly;
    }

    public String getDefaultScript() {
        return defaultScript;
    }

    public void setDefaultScript(String defaultScript) {
        this.defaultScript = defaultScript;
    }

    /**
     * @return the listenAddress
     */
    public String getListenAddress() {
        return listenAddress;
    }

    /**
     * @param listenAddress the listenAddress to set
     */
    public void setListenAddress(String listenAddress) {
        this.listenAddress = listenAddress;
    }

    /**
     * @return the listenPort
     */
    public Integer getListenPort() {
        return listenPort;
    }

    /**
     * @param listenPort the listenPort to set
     */
    public void setListenPort(Integer listenPort) {
        this.listenPort = listenPort;
    }

    /**
     * @return the ssl
     */
    public Boolean getSsl() {
        return ssl;
    }

    /**
     * @param ssl the ssl to set
     */
    public void setSsl(Boolean ssl) {
        this.ssl = ssl;
    }

    /**
     * @return the sslConfiguration
     */
    public SSLListenerConfiguration getSslConfiguration() {
        return sslConfiguration;
    }

    /**
     * @param sslConfiguration the sslConfiguration to set
     */
    public void setSslConfiguration(SSLListenerConfiguration sslConfiguration) {
        this.sslConfiguration = sslConfiguration;
    }

    /**
     * @return the defaultBackend
     */
    public String getDefaultBackend() {
        return defaultBackend;
    }

    /**
     * @param defaultBackend the defaultBackend to set
     */
    public void setDefaultBackend(String defaultBackend) {
        this.defaultBackend = defaultBackend;
    }

    public Map<String, EndPointURLContext> getUrlContexts() {
        return urlContexts;
    }

    public void setUrlContexts(Map<String, EndPointURLContext> urlContexts) {
        this.urlContexts = urlContexts;
    }

    public Boolean getSecured() {
        return secured;
    }

    public void setSecured(Boolean secured) {
        this.secured = secured;
    }

    public SecureProviderConfig getSecureProvider() {
        return secureProvider;
    }

    public void setSecureProvider(SecureProviderConfig secureProvider) {
        this.secureProvider = secureProvider;
    }

    private RateLimitRefConfiguration rateLimit;

    public RateLimitRefConfiguration getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimitRefConfiguration rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Map<String, String> getVirtualHosts() {
        return virtualHosts;
    }

    public void setVirtualHosts(Map<String, String> virtualHosts) {
        this.virtualHosts = virtualHosts;
    }

}
