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
package dev.nishisan.ngate.health;

import dev.nishisan.ngate.cluster.ClusterService;
import dev.nishisan.ngate.manager.ConfigurationManager;
import dev.nishisan.ngate.observability.service.TracerService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health indicator customizado para o n-gate.
 * <p>
 * Verifica se a configuração foi carregada com sucesso e reporta
 * informações operacionais (instanceId, endpoints configurados, cluster status).
 * <p>
 * Acessível via {@code GET /actuator/health}.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-08
 */
@Component
public class NGateHealthIndicator implements HealthIndicator {

    private static final Logger logger = LogManager.getLogger(NGateHealthIndicator.class);

    @Autowired
    private ConfigurationManager configurationManager;

    @Autowired
    private TracerService tracerService;

    @Autowired
    private ClusterService clusterService;

    @Override
    public Health health() {
        try {
            var config = configurationManager.loadConfiguration();
            if (config == null || config.getEndpoints() == null || config.getEndpoints().isEmpty()) {
                return Health.down()
                        .withDetail("reason", "No endpoints configured")
                        .withDetail("instanceId", tracerService.getInstanceId())
                        .build();
            }

            Health.Builder builder = Health.up()
                    .withDetail("instanceId", tracerService.getInstanceId())
                    .withDetail("endpointsConfigured", config.getEndpoints().size());

            // Cluster info
            if (clusterService.isClusterMode()) {
                builder.withDetail("clusterMode", true)
                       .withDetail("clusterNodeId", clusterService.getLocalNodeId())
                       .withDetail("isLeader", clusterService.isLeader())
                       .withDetail("activeMembers", clusterService.getActiveMembersCount());
            } else {
                builder.withDetail("clusterMode", false);
            }

            return builder.build();
        } catch (Exception e) {
            logger.error("Health check failed", e);
            return Health.down()
                    .withDetail("instanceId", tracerService.getInstanceId())
                    .withException(e)
                    .build();
        }
    }
}
