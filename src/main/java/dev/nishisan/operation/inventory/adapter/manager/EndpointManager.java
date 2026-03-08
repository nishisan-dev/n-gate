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
package dev.nishisan.operation.inventory.adapter.manager;

import dev.nishisan.operation.inventory.adapter.auth.OAuthClientManager;
import dev.nishisan.operation.inventory.adapter.configuration.SSLListenerConfiguration;
import dev.nishisan.operation.inventory.adapter.http.EndpointWrapper;
import dev.nishisan.operation.inventory.adapter.observabitliy.service.TracerService;
import groovy.util.GroovyScriptEngine;
import io.javalin.Javalin;
import io.javalin.community.ssl.SslPlugin;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

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
    private GroovyScriptEngine customGse;
    @Autowired
    private ConfigurationManager configurationManager;
    @Autowired
    private OAuthClientManager oAUthClient;

    @Autowired
    private TracerService tracerService;

    @EventListener(ApplicationReadyEvent.class)
    private void onStartup() {
        try {
            this.initGse();
            this.initEndpoints();
        } catch (Throwable ex) {
            logger.error("Failed To Start System, please check configuration and custom gse folder.", ex);
        }
    }

    private void initGse() throws IOException {
        customGse = new GroovyScriptEngine("custom");
    }

    /**
     * Inicializa os endpoints do sistema
     */
    private void initEndpoints() {
        logger.debug("Starting Endpoints");
        logger.debug("Total endpoints size:[{}]", this.configurationManager.loadConfiguration().getEndpoints().size());
        this.configurationManager.loadConfiguration().getEndpoints().forEach((endpointName, endPoingConfiguration) -> {
            EndpointWrapper wrapper = new EndpointWrapper(oAUthClient, endPoingConfiguration, customGse, tracerService);

            logger.debug("\t Setting UP Endpoing:[{}] With :[{}] listener(s)", endpointName, endPoingConfiguration.getListeners().size());
            endPoingConfiguration.getListeners().forEach((listenerName, listenerConfig) -> {
                logger.debug("\t\tCreating Listener:[{}]", listenerName);
                //
                // Javalin Micro service, 
                //
                Javalin service = null;
                if (listenerConfig.getSsl()) {
                    logger.debug("\t\t\t SSL Enabled For:[{}]", listenerName);
                    service = Javalin.create((javalinConfig) -> {
                        //
                        // Disable Banner
                        //

                        javalinConfig.showJavalinBanner = false;

                        // Thread pool customizado
                        QueuedThreadPool threadPool = new QueuedThreadPool(
                                endPoingConfiguration.getJettyMaxThreads(),
                                endPoingConfiguration.getJettyMinThreads(),
                                endPoingConfiguration.getJettyIdleTimeout());
                        threadPool.setName("JettyServerThreadPool");
                        threadPool.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
                        javalinConfig.jetty.threadPool = threadPool;
                        logger.info("Jetty ThreadPool configured: min={}, max={}, idleTimeout={}ms, virtualThreads=enabled",
                                endPoingConfiguration.getJettyMinThreads(),
                                endPoingConfiguration.getJettyMaxThreads(),
                                endPoingConfiguration.getJettyIdleTimeout());

//                        javalinConfig.http.gzipOnlyCompression();

//                        SslContextFactory.Server sslContextFactory = getFactoryFromConfig(listenerConfig.getSslConfiguration());
                        if (listenerConfig.getSslConfiguration().getKeystoreFile() != null
                                && listenerConfig.getSslConfiguration().getKeystorePassword() != null) {
                            logger.debug("SSL Configuration From JKS");
                            SslPlugin sslPLugin = new SslPlugin(ssl -> {
                                ssl.keystoreFromPath(listenerConfig.getSslConfiguration().getKeystoreFile(),
                                        listenerConfig.getSslConfiguration().getKeystorePassword());
                            });
                            logger.debug("SSL Configuration Apply");

                            javalinConfig.registerPlugin(sslPLugin);
                            logger.debug("SSL Configuration DONE 1 ");
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
                            logger.debug("SSL Configuration Apply");

                            javalinConfig.registerPlugin(sslPLugin);
                            logger.debug("SSL Configuration DONE 2");
                        }

                    });
                } else {
                    logger.debug("\t\t\t SSL Disabled For:[{}]", listenerName);
                    service = Javalin.create((javalinConfig) -> {
                        //
                        // Lidar com as Configurações AQUI
                        //
                        javalinConfig.showJavalinBanner = false;

                        // Thread pool customizado
                        QueuedThreadPool threadPool = new QueuedThreadPool(
                                endPoingConfiguration.getJettyMaxThreads(),
                                endPoingConfiguration.getJettyMinThreads(),
                                endPoingConfiguration.getJettyIdleTimeout());
                        threadPool.setName("JettyServerThreadPool");
                        threadPool.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());
                        javalinConfig.jetty.threadPool = threadPool;
                        logger.info("Jetty ThreadPool configured: min={}, max={}, idleTimeout={}ms, virtualThreads=enabled",
                                endPoingConfiguration.getJettyMinThreads(),
                                endPoingConfiguration.getJettyMaxThreads(),
                                endPoingConfiguration.getJettyIdleTimeout());
                    });
                }

                if (service != null) {
                    //
                    //
                    //
                    logger.debug("Service is Present");
                    wrapper.addServiceListener(listenerName, service, listenerConfig);
                } else {
                    logger.error("Service is null, cant start");
                }
            });
        });
    }

    private SslContextFactory.Server getFactoryFromConfig(SSLListenerConfiguration configuration) {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(configuration.getKeystoreFile());
        sslContextFactory.setKeyStorePassword(configuration.getKeystorePassword());
        return sslContextFactory;
    }
}
