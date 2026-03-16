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

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 05.01.2023
 */
public class ServerConfiguration {

    /**
     * Modo de operação: "proxy" (default) ou "tunnel".
     * Um processo é SEMPRE um OU outro, nunca ambos.
     */
    private String mode = "proxy";

    private Map<String, EndPointConfiguration> endpoints = new ConcurrentHashMap<>();
    private ClusterConfiguration cluster;
    private AdminApiConfiguration admin;
    private CircuitBreakerConfiguration circuitBreaker;
    private TunnelConfiguration tunnel;

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    /**
     * @return true se o modo de operação é "tunnel"
     */
    @JsonIgnore
    public boolean isTunnelMode() {
        return "tunnel".equalsIgnoreCase(mode);
    }

    public Map<String, EndPointConfiguration> getEndpoints() {
        return endpoints;
    }

    public void setEndpoints(Map<String, EndPointConfiguration> endpoints) {
        this.endpoints = endpoints;
    }

    public ClusterConfiguration getCluster() {
        return cluster;
    }

    public void setCluster(ClusterConfiguration cluster) {
        this.cluster = cluster;
    }

    public AdminApiConfiguration getAdmin() {
        return admin;
    }

    public void setAdmin(AdminApiConfiguration admin) {
        this.admin = admin;
    }

    public CircuitBreakerConfiguration getCircuitBreaker() {
        return circuitBreaker;
    }

    public void setCircuitBreaker(CircuitBreakerConfiguration circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    public TunnelConfiguration getTunnel() {
        return tunnel;
    }

    public void setTunnel(TunnelConfiguration tunnel) {
        this.tunnel = tunnel;
    }

    private RateLimitConfiguration rateLimiting;
    private DashboardConfiguration dashboard;

    public RateLimitConfiguration getRateLimiting() {
        return rateLimiting;
    }

    public void setRateLimiting(RateLimitConfiguration rateLimiting) {
        this.rateLimiting = rateLimiting;
    }

    public DashboardConfiguration getDashboard() {
        return dashboard;
    }

    public void setDashboard(DashboardConfiguration dashboard) {
        this.dashboard = dashboard;
    }

}
