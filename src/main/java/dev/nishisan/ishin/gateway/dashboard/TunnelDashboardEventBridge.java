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

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nishisan.ishin.gateway.dashboard.storage.DashboardStorageService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/**
 * Bridge entre o runtime do tunnel e o storage de eventos do dashboard.
 * <p>
 * Recebe callbacks do {@code TunnelRegistry} e {@code TunnelEngine} e
 * persiste eventos no {@link DashboardStorageService} para exibição
 * na timeline do dashboard.
 * <p>
 * O core do tunnel não depende diretamente do dashboard — esta classe
 * faz a ponte quando o dashboard está habilitado.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-18
 */
public class TunnelDashboardEventBridge {

    private static final Logger logger = LogManager.getLogger(TunnelDashboardEventBridge.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SOURCE = "tunnel";

    private final DashboardStorageService storage;

    public TunnelDashboardEventBridge(DashboardStorageService storage) {
        this.storage = storage;
    }

    // ─── Registry Events ────────────────────────────────────────────────

    public void onMemberAdded(int virtualPort, String memberKey) {
        saveEvent("MEMBER_ADDED", Map.of(
                "virtualPort", virtualPort,
                "member", memberKey
        ));
    }

    public void onMemberRemoved(int virtualPort, String memberKey) {
        saveEvent("MEMBER_REMOVED", Map.of(
                "virtualPort", virtualPort,
                "member", memberKey
        ));
    }

    public void onStandbyPromoted(int virtualPort, String memberKey) {
        saveEvent("STANDBY_PROMOTED", Map.of(
                "virtualPort", virtualPort,
                "member", memberKey
        ));
    }

    public void onKeepaliveTimeout(int virtualPort, String memberKey) {
        saveEvent("KEEPALIVE_TIMEOUT", Map.of(
                "virtualPort", virtualPort,
                "member", memberKey
        ));
    }

    // ─── Engine Events ──────────────────────────────────────────────────

    public void onListenerOpened(int virtualPort) {
        saveEvent("LISTENER_OPENED", Map.of(
                "virtualPort", virtualPort
        ));
    }

    public void onListenerClosed(int virtualPort) {
        saveEvent("LISTENER_CLOSED", Map.of(
                "virtualPort", virtualPort
        ));
    }

    public void onConnectError(int virtualPort, String backend, String errorType) {
        saveEvent("CONNECT_ERROR", Map.of(
                "virtualPort", virtualPort,
                "backend", backend,
                "errorType", errorType
        ));
    }

    // ─── Internal ───────────────────────────────────────────────────────

    private void saveEvent(String eventType, Map<String, Object> details) {
        try {
            String detailsJson = MAPPER.writeValueAsString(details);
            storage.saveEvent(eventType, SOURCE, detailsJson);
        } catch (Exception e) {
            logger.warn("Failed to save tunnel event '{}': {}", eventType, e.getMessage());
        }
    }
}
