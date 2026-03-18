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
package dev.nishisan.ishin.gateway.dashboard;

import dev.nishisan.ishin.gateway.configuration.DashboardConfiguration;
import dev.nishisan.ishin.gateway.configuration.ServerConfiguration;
import dev.nishisan.ishin.gateway.manager.ConfigurationManager;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Spring component que gerencia o lifecycle do {@link DashboardServer}.
 * <p>
 * Inicia após os proxies (Order 40) para garantir que métricas
 * e configuração já estão disponíveis.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
@Service
public class DashboardService {

    private static final Logger logger = LogManager.getLogger(DashboardService.class);

    @Autowired
    private ConfigurationManager configurationManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired(required = false)
    private dev.nishisan.ishin.gateway.tunnel.TunnelService tunnelService;

    private DashboardServer dashboardServer;

    @Order(40)
    @EventListener(ApplicationReadyEvent.class)
    private void onStartup() {
        ServerConfiguration serverConfig = configurationManager.getServerConfiguration();
        if (serverConfig == null) {
            logger.warn("DashboardService: ServerConfiguration nula, dashboard não será iniciado");
            return;
        }

        DashboardConfiguration dashboardConfig = serverConfig.getDashboard();
        if (dashboardConfig == null || !dashboardConfig.isEnabled()) {
            logger.info("DashboardService: dashboard desabilitado (dashboard.enabled=false ou ausente)");
            return;
        }

        try {
            // Criar event bridge se tunnel está ativo
            TunnelDashboardEventBridge eventBridge = null;
            if (tunnelService != null && serverConfig.isTunnelMode()) {
                // O DashboardServer criará o storage, precisamos do bridge depois
                // Por ora, criamos com null e conectamos após o server criar o storage
            }

            dashboardServer = new DashboardServer(dashboardConfig, serverConfig, meterRegistry, tunnelService);
            dashboardServer.start();

            // Conectar event bridge ao tunnel
            if (tunnelService != null && serverConfig.isTunnelMode()) {
                eventBridge = new TunnelDashboardEventBridge(dashboardServer.getStorage());
                tunnelService.setEventBridge(eventBridge);
            }
        } catch (Exception e) {
            logger.error("Falha ao iniciar Dashboard de Observabilidade", e);
        }
    }

    @PreDestroy
    private void shutdown() {
        if (dashboardServer != null) {
            logger.info("DashboardService: shutdown do dashboard...");
            dashboardServer.stop();
        }
    }
}
