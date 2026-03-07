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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.gson.JsonIOException;
import dev.nishisan.operation.inventory.adapter.auth.OAuthClientManager;
import dev.nishisan.operation.inventory.adapter.configuration.BackendConfiguration;
import dev.nishisan.operation.inventory.adapter.configuration.EndPointConfiguration;
import dev.nishisan.operation.inventory.adapter.configuration.EndPointListenersConfiguration;
import dev.nishisan.operation.inventory.adapter.configuration.EndPointURLContext;
import dev.nishisan.operation.inventory.adapter.configuration.OauthServerClientConfiguration;
import dev.nishisan.operation.inventory.adapter.configuration.SSLListenerConfiguration;
import dev.nishisan.operation.inventory.adapter.configuration.ServerConfiguration;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 05.01.2023
 */
@Service
public class ConfigurationManager {

    private Logger logger = LogManager.getLogger(ConfigurationManager.class);
    private ServerConfiguration configuration = null;
    @Autowired
    private OAuthClientManager oauthClientManager;

    private ObjectMapper yamlSerializer = new ObjectMapper(new YAMLFactory());

    @EventListener(ApplicationReadyEvent.class)
    private void onStartup() {
        this.loadConfiguration();

        configuration.getEndpoints().forEach((k, v) -> {
            v.getBackends().forEach((bk, bv) -> {
                if (bv.getOauthClientConfig() != null) {
                    oauthClientManager.addSsoConfiguration(bv.getOauthClientConfig());
                }
            });

        });

    }

    /**
     * Procura os paths em ordem! o primeiro que encontrar ele vai usar
     *
     * @return
     */
    private ArrayList<String> getConfigurationPaths() {
        ArrayList<String> paths = new ArrayList<>();
        paths.add("config/adapter.yaml");
        paths.add("/app/inventory-adapter/config/adapter.yaml");
        paths.add("/etc/inventory-adapter/adapter.yaml");
        paths.add("c:/temp/inventory.yaml");
        String envPath = System.getenv("INVENTORY_ADAPTER_CONFIG");
        if (envPath != null) {
            //
            // Variaveis de ambiente tem prioridade
            //
            paths.add(0, envPath);
        }
        return paths;
    }

    /**
     * Carrega ou Cria o arquivo de configuração
     *
     * @return
     */
    public ServerConfiguration loadConfiguration() {
        if (this.configuration == null) {
            logger.debug("Loading System Configuration:");
            for (String configPath : this.getConfigurationPaths()) {
                File configFile = new File(configPath);
                logger.debug(" Trying Configuration File at: [" + configPath + "] Exists: [" + configFile.exists() + "]");
                if (configFile.exists()) {
                    try {
                        //
                        // Load from Json
                        //

                        FileReader reader = new FileReader(configFile);
//                        this.configuration = gson.fromJson(reader, ServerConfiguration.class);
                        this.configuration = yamlSerializer.readValue(reader, ServerConfiguration.class);
                        reader.close();
                        logger.info("Configuration file Loaded from :[" + configFile.getPath() + "]");
                        return this.configuration;
                    } catch (FileNotFoundException ex) {
                        logger.error("Configuration File , not found... weird exception..", ex);
                    } catch (IOException ex) {
                        logger.error("Failed to Close IO..", ex);
                    }
                    break;
                }
            }
            //
            // Não existe arquivo de configuração... vamos tentar criar...UM DEFAULT
            //
            this.configuration = new ServerConfiguration();

            EndPointConfiguration defaulEndpoint = new EndPointConfiguration();
            defaulEndpoint.setRuleMapping("default/Rules.groovy");
            EndPointListenersConfiguration httpListenerConfiguration = new EndPointListenersConfiguration();
            httpListenerConfiguration.setListenAddress("0.0.0.0");
            httpListenerConfiguration.setListenPort(8080);
            httpListenerConfiguration.setSsl(false);
            httpListenerConfiguration.setDefaultBackend("http");
            
//            EndPointURLContext endpointUrlContext = new EndPointURLContext("/*","ANY","default/Rules.groovy");
            httpListenerConfiguration.getUrlContexts().put("default", new EndPointURLContext("/*","ANY","default/Rules.groovy"));

            SSLListenerConfiguration sslConfiguration = new SSLListenerConfiguration();
            sslConfiguration.setAlias("default-ssl");
            sslConfiguration.setKeystoreFile("default-keystore.jks");
            sslConfiguration.setKeystorePassword("keystore-password");
            sslConfiguration.setTruststoreFile("trust-store.jks");
            sslConfiguration.setTruststorePassword("truststore-password");
            httpListenerConfiguration.setSslConfiguration(sslConfiguration);

            BackendConfiguration defaultHttpBackend = new BackendConfiguration();

            defaultHttpBackend.setBackendName("http");
            defaultHttpBackend.setEndPointUrl("http://keycloak:8080");

            OauthServerClientConfiguration configuration
                    = new OauthServerClientConfiguration(
                            "inventory-keycloak",
                            "inventory-adapter-client",
                            "inventory-adapter-secret",
                            "inventory-svc",
                            "inventory-svc-pass",
                            "http://keycloak:8080/realms/inventory-dev/protocol/openid-connect/token",
                            null, Arrays.asList("openid", "profile", "email"));

            defaultHttpBackend.setOauthClientConfig(configuration);

            EndPointListenersConfiguration httpsListenerConfiguration = new EndPointListenersConfiguration();
            httpsListenerConfiguration.setListenAddress("0.0.0.0");
            httpsListenerConfiguration.setListenPort(8181);
            httpsListenerConfiguration.setSsl(true);
            httpsListenerConfiguration.setSslConfiguration(sslConfiguration);

            defaulEndpoint.getListeners().put("http", httpListenerConfiguration);
            defaulEndpoint.getBackends().put("http", defaultHttpBackend);

            defaulEndpoint.getListeners().put("https", httpsListenerConfiguration);

            
            
            this.configuration.getEndpoints().put("default", defaulEndpoint);
            
            

            File theConfigurationFile = new File(getConfigurationPaths().get(0));
            if (theConfigurationFile.getParentFile().exists()) {
                try {
                    FileWriter writer = new FileWriter(theConfigurationFile);
//                    gson.toJson(this.configuration, writer);
                    this.yamlSerializer.writeValue(writer, this.configuration);
//                    writer.flush();
//                    writer.close();
                    logger.info("Default Configuration file Created at: [" + theConfigurationFile.getPath() + "]");
                } catch (IOException ex) {
                    logger.error("Failed to save configuration file", ex);
                } catch (JsonIOException ex) {
                    logger.error("Configuration File Produced an invalid JSON", ex);
                }
            } else {
                logger.warn("Cannot Create Default Configuration file at: [" + getConfigurationPaths().get(0) + "]");
            }
        }
        return this.configuration;
    }

//    public static void main(String[] args) {
//        ConfigurationManager a = new ConfigurationManager();
//        a.loadConfiguration();
//    }
};
