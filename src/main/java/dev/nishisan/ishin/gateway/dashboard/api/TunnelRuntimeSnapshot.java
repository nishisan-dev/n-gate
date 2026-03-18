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
package dev.nishisan.ishin.gateway.dashboard.api;

import java.util.List;

/**
 * Snapshot imutável do estado runtime do Tunnel Mode.
 * <p>
 * Usado pela API do dashboard para serializar o estado vivo do túnel
 * sem expor referências mutáveis das classes internas.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-18
 */
public record TunnelRuntimeSnapshot(
        String mode,
        int listeners,
        int groups,
        int members,
        int activeConnections,
        List<VirtualPortSnapshot> virtualPorts
) {

    /**
     * Snapshot de uma porta virtual e seus backend members.
     */
    public record VirtualPortSnapshot(
            int virtualPort,
            boolean listenerOpen,
            int activeMembers,
            int standbyMembers,
            int drainingMembers,
            List<TunnelMemberSnapshot> members
    ) {}

    /**
     * Snapshot de um backend member individual.
     */
    public record TunnelMemberSnapshot(
            String backendKey,
            String nodeId,
            String host,
            int realPort,
            String status,
            int weight,
            int activeConnections,
            double keepaliveAgeSeconds
    ) {}
}
