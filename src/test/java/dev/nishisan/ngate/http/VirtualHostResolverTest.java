/*
 * Copyright (C) 2026 Lucas Nishimura <lucas.nishimura@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dev.nishisan.ngate.http;

import org.junit.jupiter.api.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários para o {@link VirtualHostResolver}.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-15
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VirtualHostResolverTest {

    private static final Map<String, String> VIRTUAL_HOSTS = new LinkedHashMap<>();

    @BeforeAll
    static void setup() {
        VIRTUAL_HOSTS.put("api.example.com", "api-backend");
        VIRTUAL_HOSTS.put("admin.example.com", "admin-backend");
        VIRTUAL_HOSTS.put("*.cdn.example.com", "cdn-backend");
        VIRTUAL_HOSTS.put("*.example.com", "generic-backend");
    }

    @Test
    @Order(1)
    @DisplayName("T1: Exact match resolve corretamente")
    void testExactMatch() {
        Optional<String> result = VirtualHostResolver.resolve("api.example.com", VIRTUAL_HOSTS);
        assertTrue(result.isPresent());
        assertEquals("api-backend", result.get());
    }

    @Test
    @Order(2)
    @DisplayName("T2: Exact match é case-insensitive")
    void testExactMatchCaseInsensitive() {
        Optional<String> result = VirtualHostResolver.resolve("API.Example.COM", VIRTUAL_HOSTS);
        assertTrue(result.isPresent());
        assertEquals("api-backend", result.get());
    }

    @Test
    @Order(3)
    @DisplayName("T3: Host header com porta é normalizado")
    void testHostWithPort() {
        Optional<String> result = VirtualHostResolver.resolve("api.example.com:8080", VIRTUAL_HOSTS);
        assertTrue(result.isPresent());
        assertEquals("api-backend", result.get());
    }

    @Test
    @Order(4)
    @DisplayName("T4: Wildcard *.cdn.example.com resolve corretamente")
    void testWildcardSpecific() {
        Optional<String> result = VirtualHostResolver.resolve("img.cdn.example.com", VIRTUAL_HOSTS);
        assertTrue(result.isPresent());
        assertEquals("cdn-backend", result.get());
    }

    @Test
    @Order(5)
    @DisplayName("T5: Wildcard genérico *.example.com resolve quando mais específico não casa")
    void testWildcardGeneric() {
        Optional<String> result = VirtualHostResolver.resolve("unknown.example.com", VIRTUAL_HOSTS);
        assertTrue(result.isPresent());
        assertEquals("generic-backend", result.get());
    }

    @Test
    @Order(6)
    @DisplayName("T6: Wildcard mais específico tem prioridade sobre genérico")
    void testWildcardSpecificPriority() {
        // "static.cdn.example.com" casa com "*.cdn.example.com" e "*.example.com"
        // Mas "*.cdn.example.com" é mais específico (suffix mais longo)
        Optional<String> result = VirtualHostResolver.resolve("static.cdn.example.com", VIRTUAL_HOSTS);
        assertTrue(result.isPresent());
        assertEquals("cdn-backend", result.get());
    }

    @Test
    @Order(7)
    @DisplayName("T7: Host sem match retorna empty")
    void testNoMatch() {
        Optional<String> result = VirtualHostResolver.resolve("unknown.tld", VIRTUAL_HOSTS);
        assertTrue(result.isEmpty());
    }

    @Test
    @Order(8)
    @DisplayName("T8: Host null retorna empty")
    void testNullHost() {
        Optional<String> result = VirtualHostResolver.resolve(null, VIRTUAL_HOSTS);
        assertTrue(result.isEmpty());
    }

    @Test
    @Order(9)
    @DisplayName("T9: Host vazio retorna empty")
    void testEmptyHost() {
        Optional<String> result = VirtualHostResolver.resolve("", VIRTUAL_HOSTS);
        assertTrue(result.isEmpty());
    }

    @Test
    @Order(10)
    @DisplayName("T10: Mapa de virtualHosts vazio retorna empty")
    void testEmptyVirtualHosts() {
        Optional<String> result = VirtualHostResolver.resolve("api.example.com", Map.of());
        assertTrue(result.isEmpty());
    }

    @Test
    @Order(11)
    @DisplayName("T11: Mapa de virtualHosts null retorna empty")
    void testNullVirtualHosts() {
        Optional<String> result = VirtualHostResolver.resolve("api.example.com", null);
        assertTrue(result.isEmpty());
    }

    @Test
    @Order(12)
    @DisplayName("T12: normalizeHost remove porta corretamente")
    void testNormalizeHost() {
        assertEquals("api.example.com", VirtualHostResolver.normalizeHost("api.example.com:8080"));
        assertEquals("api.example.com", VirtualHostResolver.normalizeHost("API.Example.COM"));
        assertEquals("api.example.com", VirtualHostResolver.normalizeHost("api.example.com"));
        assertEquals("localhost", VirtualHostResolver.normalizeHost("localhost:3000"));
    }
}
