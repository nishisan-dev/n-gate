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
package dev.nishisan.ngate.manager;

import dev.nishisan.ngate.auth.OAuthClientManager;
import dev.nishisan.ngate.http.EndpointWrapper;
import dev.nishisan.ngate.http.circuit.BackendCircuitBreakerManager;
import dev.nishisan.ngate.http.ratelimit.RateLimitManager;
import dev.nishisan.ngate.observabitliy.ProxyMetrics;
import dev.nishisan.ngate.observabitliy.service.TracerService;
import dev.nishisan.ngate.upstream.UpstreamHealthChecker;
import dev.nishisan.ngate.upstream.UpstreamPoolManager;
import groovy.util.GroovyScriptEngine;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 06.01.2023
 */
@Service
public class EndpointManager {

    private final Logger logger = LogManager.getLogger(EndpointManager.class);
    private final Map<String, EndpointWrapper> endpoints = new ConcurrentHashMap<>();
    private final List<EndpointWrapper> activeWrappers = new ArrayList<>();

    /**
     * Retorna a lista de wrappers ativos (usada pelo RulesBundleManager
     * para propagar swap do GSE em todos os endpoints).
     */
    public List<EndpointWrapper> getActiveWrappers() {
        return activeWrappers;
    }
    private GroovyScriptEngine customGse;
    @Autowired
    private ConfigurationManager configurationManager;
    @Autowired
    private OAuthClientManager oAUthClient;

    @Autowired
    private TracerService tracerService;

    @Autowired
    private ProxyMetrics proxyMetrics;

    @Autowired
    private BackendCircuitBreakerManager circuitBreakerManager;

    @Autowired
    private RateLimitManager rateLimitManager;

    @Autowired
    private UpstreamPoolManager upstreamPoolManager;

    private final UpstreamHealthChecker healthChecker = new UpstreamHealthChecker();

    @EventListener(ApplicationReadyEvent.class)
    private void onStartup() {
        try {
            this.initGse();
            this.initUpstreamPools();
            this.initEndpoints();
            this.healthChecker.start(upstreamPoolManager);
        } catch (Throwable ex) {
            logger.error("Failed To Start System, please check configuration and custom gse folder.", ex);
        }
    }

    /**
     * Graceful shutdown: para todos os listeners Javalin e drena requests em andamento.
     * Invocado automaticamente pelo Spring Boot ao receber SIGTERM/shutdown.
     */
    @PreDestroy
    private void shutdown() {
        logger.info("n-gate graceful shutdown initiated...");
        healthChecker.stop();
        logger.info("Health checker stopped. Stopping {} endpoint wrapper(s)...", activeWrappers.size());
        for (EndpointWrapper wrapper : activeWrappers) {
            wrapper.stopAllListeners();
        }
        logger.info("All Javalin listeners stopped. Shutdown complete.");
    }

    private void initGse() throws IOException {
        customGse = new GroovyScriptEngine("custom");
    }

    /**
     * Inicializa os upstream pools a partir dos backends configurados.
     * Deve ser chamado ANTES de initEndpoints().
     */
    private void initUpstreamPools() {
        this.configurationManager.loadConfiguration().getEndpoints().forEach((name, epConfig) -> {
            if (epConfig.getBackends() != null && !epConfig.getBackends().isEmpty()) {
                upstreamPoolManager.initialize(epConfig.getBackends());
                logger.info("Upstream pools initialized for endpoint '{}'", name);
            }
        });
    }

    /**
     * Inicializa os endpoints do sistema
     */
    private void initEndpoints() {
        logger.debug("Starting Endpoints");
        logger.debug("Total endpoints size:[{}]", this.configurationManager.loadConfiguration().getEndpoints().size());
        this.configurationManager.loadConfiguration().getEndpoints().forEach((endpointName, endPoingConfiguration) -> {
            EndpointWrapper wrapper = new EndpointWrapper(oAUthClient, endPoingConfiguration, customGse, tracerService, proxyMetrics, circuitBreakerManager, rateLimitManager, upstreamPoolManager);
            activeWrappers.add(wrapper);

            logger.debug("\t Setting UP Endpoing:[{}] With :[{}] listener(s)", endpointName, endPoingConfiguration.getListeners().size());
            endPoingConfiguration.getListeners().forEach((listenerName, listenerConfig) -> {
                logger.debug("\t\tCreating Listener:[{}]", listenerName);

                Javalin service;

                if (listenerConfig.getSsl()) {
                    logger.debug("\t\t\t SSL Enabled For:[{}]", listenerName);
                    service = Javalin.create((javalinConfig) -> {
                        javalinConfig.startup.showJavalinBanner = false;
                        javalinConfig.concurrency.useVirtualThreads = true;
                        logger.info("Javalin 7 configured with native Virtual Threads (Loom)");

                        if (listenerConfig.getSslConfiguration().getKeystoreFile() != null
                                && listenerConfig.getSslConfiguration().getKeystorePassword() != null) {
                            logger.debug("SSL Configuration From JKS");
                            SslPlugin sslPLugin = new SslPlugin(ssl -> {
                                ssl.keystoreFromPath(listenerConfig.getSslConfiguration().getKeystoreFile(),
                                        listenerConfig.getSslConfiguration().getKeystorePassword());
                            });
                            javalinConfig.registerPlugin(sslPLugin);
                            logger.debug("SSL Configuration DONE 1");
                        }

                        if (listenerConfig.getSslConfiguration().getCertFile() != null
                                && listenerConfig.getSslConfiguration().getKeyFile() != null) {
                            logger.debug("SSL Configuration From PEM");
                            SslPlugin sslPLugin = new SslPlugin(ssl -> {
                                ssl.pemFromPath(listenerConfig.getSslConfiguration().getCertFile(),
                                        listenerConfig.getSslConfiguration().getKeyFile());
                                ssl.securePort = listenerConfig.getListenPort();
                                ssl.insecure = false;
                                ssl.sniHostCheck = false;
                            });
                            javalinConfig.registerPlugin(sslPLugin);
                            logger.debug("SSL Configuration DONE 2");
                        }

                        // Register handlers upfront via config.routes (Javalin 7 requirement)
                        wrapper.registerRoutes(listenerName, javalinConfig.routes, listenerConfig);
                    });
                } else {
                    logger.debug("\t\t\t SSL Disabled For:[{}]", listenerName);
                    service = Javalin.create((javalinConfig) -> {
                        javalinConfig.startup.showJavalinBanner = false;
                        javalinConfig.concurrency.useVirtualThreads = true;
                        logger.info("Javalin 7 configured with native Virtual Threads (Loom)");

                        // Register handlers upfront via config.routes (Javalin 7 requirement)
                        wrapper.registerRoutes(listenerName, javalinConfig.routes, listenerConfig);
                    });
                }

                // Store listener and start AFTER Javalin.create() returns
                wrapper.startListener(listenerName, service, listenerConfig);
            });
        });
    }

}
