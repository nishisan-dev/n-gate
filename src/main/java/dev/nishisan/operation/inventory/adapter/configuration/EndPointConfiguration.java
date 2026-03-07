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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 05.01.2023
 */
public class EndPointConfiguration {

    private Map<String, EndPointListenersConfiguration> listeners = new ConcurrentHashMap<>();
    private Map<String, BackendConfiguration> backends = new ConcurrentHashMap<>();
    private String ruleMapping;
    private Integer ruleMappingThreads = 1;
    private Integer socketTimeout = 3600;

    public String getRuleMapping() {
        return ruleMapping;
    }

    public void setRuleMapping(String ruleMapping) {
        this.ruleMapping = ruleMapping;
    }

   

    /**
     * @return the listeners
     */
    public Map<String, EndPointListenersConfiguration> getListeners() {
        return listeners;
    }

    /**
     * @param listeners the listeners to set
     */
    public void setListeners(Map<String, EndPointListenersConfiguration> listeners) {
        this.listeners = listeners;
    }

    /**
     * @return the backends
     */
    public Map<String, BackendConfiguration> getBackends() {
        return backends;
    }

    /**
     * @param backends the backends to set
     */
    public void setBackends(Map<String, BackendConfiguration> backends) {
        this.backends = backends;
    }

    /**
     * @return the ruleMappingThreads
     */
    public Integer getRuleMappingThreads() {
        return ruleMappingThreads;
    }

    /**
     * @param ruleMappingThreads the ruleMappingThreads to set
     */
    public void setRuleMappingThreads(Integer ruleMappingThreads) {
        this.ruleMappingThreads = ruleMappingThreads;
    }

    /**
     * @return the socketTimeout
     */
    public Integer getSocketTimeout() {
        return socketTimeout;
    }

    /**
     * @param socketTimeout the socketTimeout to set
     */
    public void setSocketTimeout(Integer socketTimeout) {
        this.socketTimeout = socketTimeout;
    }

}
