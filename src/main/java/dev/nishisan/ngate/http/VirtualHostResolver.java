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
package dev.nishisan.ngate.http;

import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Resolve o backend de destino a partir do header Host da request HTTP
 * e do mapa de virtualHosts configurado no listener.
 * <p>
 * Ordem de matching:
 * <ol>
 *   <li>Exact match (case-insensitive): {@code "api.example.com"}</li>
 *   <li>Wildcard match (mais específico primeiro): {@code "*.example.com"}</li>
 * </ol>
 * Se nenhum match for encontrado, retorna {@code Optional.empty()},
 * sinalizando ao caller que deve usar o {@code defaultBackend}.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-15
 */
public class VirtualHostResolver {

    private static final Logger logger = LogManager.getLogger(VirtualHostResolver.class);

    private VirtualHostResolver() {
        // Utility class — não instanciar
    }

    /**
     * Resolve o backend name a partir do Host header e do mapa de virtualHosts.
     *
     * @param hostHeader   valor do header Host da request (pode conter porta, ex: {@code "api.example.com:8080"})
     * @param virtualHosts mapa de serverName → backendName
     * @return backend name se houver match, ou {@code empty()} se nenhum match
     */
    public static Optional<String> resolve(String hostHeader, Map<String, String> virtualHosts) {
        if (hostHeader == null || hostHeader.isBlank() || virtualHosts == null || virtualHosts.isEmpty()) {
            return Optional.empty();
        }

        // Normaliza: remove porta e converte para lowercase
        String hostname = normalizeHost(hostHeader);

        // 1. Exact match
        for (Map.Entry<String, String> entry : virtualHosts.entrySet()) {
            String serverName = entry.getKey().toLowerCase().trim();
            if (!serverName.startsWith("*") && serverName.equals(hostname)) {
                logger.debug("Virtual host exact match: [{}] → backend [{}]", hostname, entry.getValue());
                return Optional.of(entry.getValue());
            }
        }

        // 2. Wildcard match — seleciona o mais específico (mais longo)
        String bestMatch = null;
        String bestBackend = null;
        int bestLength = -1;

        for (Map.Entry<String, String> entry : virtualHosts.entrySet()) {
            String serverName = entry.getKey().toLowerCase().trim();
            if (serverName.startsWith("*.")) {
                // "*.example.com" → ".example.com"
                String suffix = serverName.substring(1);
                if (hostname.endsWith(suffix) && suffix.length() > bestLength) {
                    bestMatch = serverName;
                    bestBackend = entry.getValue();
                    bestLength = suffix.length();
                }
            }
        }

        if (bestBackend != null) {
            logger.debug("Virtual host wildcard match: [{}] matched [{}] → backend [{}]",
                    hostname, bestMatch, bestBackend);
            return Optional.of(bestBackend);
        }

        logger.debug("Virtual host no match for host: [{}]", hostname);
        return Optional.empty();
    }

    /**
     * Normaliza o valor do Host header:
     * - Remove a porta (ex: "api.example.com:8080" → "api.example.com")
     * - Converte para lowercase
     */
    static String normalizeHost(String hostHeader) {
        String normalized = hostHeader.trim().toLowerCase();
        // Remove porta se presente (IPv4 ou hostname)
        int colonIndex = normalized.lastIndexOf(':');
        if (colonIndex > 0) {
            // Verifica se o que vem depois do ':' são apenas dígitos (porta)
            String afterColon = normalized.substring(colonIndex + 1);
            if (afterColon.chars().allMatch(Character::isDigit)) {
                normalized = normalized.substring(0, colonIndex);
            }
        }
        return normalized;
    }
}
