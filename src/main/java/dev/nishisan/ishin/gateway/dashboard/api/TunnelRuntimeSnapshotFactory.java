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

import dev.nishisan.ishin.gateway.tunnel.BackendMember;
import dev.nishisan.ishin.gateway.tunnel.TunnelEngine;
import dev.nishisan.ishin.gateway.tunnel.TunnelRegistry;
import dev.nishisan.ishin.gateway.tunnel.TunnelService;
import dev.nishisan.ishin.gateway.tunnel.VirtualPortGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Factory para construção de {@link TunnelRuntimeSnapshot} a partir do estado
 * vivo do {@link TunnelService}.
 * <p>
 * Monta snapshots read-only sem expor estruturas mutáveis do core do tunnel.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-18
 */
public final class TunnelRuntimeSnapshotFactory {

    private TunnelRuntimeSnapshotFactory() {
        // utility class
    }

    /**
     * Cria um snapshot completo do runtime do tunnel.
     *
     * @param tunnelService o serviço do tunnel (pode ser null se tunnel não está ativo)
     * @return snapshot imutável ou snapshot vazio se tunnel não está rodando
     */
    public static TunnelRuntimeSnapshot create(TunnelService tunnelService) {
        if (tunnelService == null || !tunnelService.isRunning()) {
            return new TunnelRuntimeSnapshot("tunnel", 0, 0, 0, 0, Collections.emptyList());
        }

        TunnelRegistry registry = tunnelService.getTunnelRegistry();
        TunnelEngine engine = tunnelService.getTunnelEngine();

        if (registry == null || engine == null) {
            return new TunnelRuntimeSnapshot("tunnel", 0, 0, 0, 0, Collections.emptyList());
        }

        Map<Integer, VirtualPortGroup> groupsSnapshot = registry.getGroupsSnapshot();
        List<TunnelRuntimeSnapshot.VirtualPortSnapshot> virtualPortSnapshots = new ArrayList<>();

        int totalMembers = 0;

        for (Map.Entry<Integer, VirtualPortGroup> entry : groupsSnapshot.entrySet()) {
            int virtualPort = entry.getKey();
            VirtualPortGroup group = entry.getValue();

            List<TunnelRuntimeSnapshot.TunnelMemberSnapshot> memberSnapshots = new ArrayList<>();
            for (BackendMember member : group.getAllMembers()) {
                double keepaliveAge = (System.currentTimeMillis() - member.getLastKeepAlive()) / 1000.0;
                memberSnapshots.add(new TunnelRuntimeSnapshot.TunnelMemberSnapshot(
                        member.getKey(),
                        member.getNodeId(),
                        member.getHost(),
                        member.getRealPort(),
                        member.getStatus(),
                        member.getWeight(),
                        member.getActiveConnections().get(),
                        Math.round(keepaliveAge * 10.0) / 10.0
                ));
            }

            totalMembers += memberSnapshots.size();

            virtualPortSnapshots.add(new TunnelRuntimeSnapshot.VirtualPortSnapshot(
                    virtualPort,
                    engine.isListenerOpen(virtualPort),
                    group.getActiveMemberCount(),
                    group.getStandbyMemberCount(),
                    group.getDrainingMemberCount(),
                    Collections.unmodifiableList(memberSnapshots)
            ));
        }

        return new TunnelRuntimeSnapshot(
                "tunnel",
                engine.getListenerCount(),
                groupsSnapshot.size(),
                totalMembers,
                engine.getTotalActiveConnections(),
                Collections.unmodifiableList(virtualPortSnapshots)
        );
    }
}
