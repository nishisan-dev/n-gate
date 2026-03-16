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
package dev.nishisan.ngate.dashboard;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Filtro de IP para acesso ao Dashboard de observabilidade.
 * <p>
 * Suporta IPs exatos (IPv4/IPv6) e ranges CIDR (ex: {@code 10.0.0.0/24}).
 * IPs não autorizados recebem HTTP 403.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
public class DashboardIpFilter {

    private static final Logger logger = LogManager.getLogger(DashboardIpFilter.class);

    private final List<IpRule> rules = new ArrayList<>();

    /**
     * Inicializa o filtro a partir da lista de IPs/CIDRs configurados.
     *
     * @param allowedEntries lista de IPs ou CIDRs (ex: "127.0.0.1", "10.0.0.0/24", "::1")
     */
    public DashboardIpFilter(List<String> allowedEntries) {
        for (String entry : allowedEntries) {
            try {
                if (entry.contains("/")) {
                    rules.add(new CidrRule(entry));
                } else {
                    rules.add(new ExactIpRule(entry));
                }
                logger.info("Dashboard IP allowlist: {}", entry);
            } catch (UnknownHostException e) {
                logger.warn("Dashboard IP allowlist: falha ao parsear '{}', ignorando: {}", entry, e.getMessage());
            }
        }
    }

    /**
     * Verifica se o IP do cliente é permitido.
     *
     * @param clientIp endereço IP do cliente
     * @return true se o IP é permitido
     */
    public boolean isAllowed(String clientIp) {
        if (rules.isEmpty()) {
            return true; // Sem regras = permite tudo
        }
        try {
            InetAddress addr = InetAddress.getByName(clientIp);
            for (IpRule rule : rules) {
                if (rule.matches(addr)) {
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            logger.warn("Dashboard IP filter: falha ao resolver '{}': {}", clientIp, e.getMessage());
        }
        return false;
    }

    // ─── IP Rule Interface ──────────────────────────────────────────────

    private interface IpRule {
        boolean matches(InetAddress address);
    }

    // ─── Exact IP Rule ──────────────────────────────────────────────────

    private static class ExactIpRule implements IpRule {
        private final InetAddress target;

        ExactIpRule(String ip) throws UnknownHostException {
            this.target = InetAddress.getByName(ip);
        }

        @Override
        public boolean matches(InetAddress address) {
            return target.equals(address);
        }
    }

    // ─── CIDR Range Rule ────────────────────────────────────────────────

    private static class CidrRule implements IpRule {
        private final byte[] networkBytes;
        private final int prefixLength;

        CidrRule(String cidr) throws UnknownHostException {
            String[] parts = cidr.split("/");
            InetAddress network = InetAddress.getByName(parts[0]);
            this.networkBytes = network.getAddress();
            this.prefixLength = Integer.parseInt(parts[1]);
        }

        @Override
        public boolean matches(InetAddress address) {
            byte[] addrBytes = address.getAddress();
            if (addrBytes.length != networkBytes.length) {
                return false; // IPv4 vs IPv6 mismatch
            }

            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            // Compara bytes completos
            for (int i = 0; i < fullBytes; i++) {
                if (addrBytes[i] != networkBytes[i]) {
                    return false;
                }
            }

            // Compara bits restantes (se houver)
            if (remainingBits > 0 && fullBytes < addrBytes.length) {
                int mask = 0xFF << (8 - remainingBits);
                if ((addrBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) {
                    return false;
                }
            }

            return true;
        }
    }
}
