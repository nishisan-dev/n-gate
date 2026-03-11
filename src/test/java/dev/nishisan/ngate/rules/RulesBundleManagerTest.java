/*
 * Copyright (C) 2026 Lucas Nishimura <lucas.nishimura@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package dev.nishisan.ngate.rules;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes unitários para componentes do sistema de rules.
 * <p>
 * Testa:
 * <ul>
 *   <li>{@link RulesBundle} — serialização/desserialização Java, integridade de dados</li>
 *   <li>Lógica de limpeza de diretório (cleanDirectory pattern)</li>
 * </ul>
 * <p>
 * Não testa o ciclo Spring do {@link RulesBundleManager} diretamente,
 * mas valida os blocos funcionais que ele depende.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-11
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RulesBundleManagerTest {

    @TempDir
    Path tempDir;

    // ─── RulesBundle serialization ──────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("T1: RulesBundle roundtrip — serializar e desserializar mantém integridade")
    void testBundleSerializationRoundtrip() throws IOException, ClassNotFoundException {
        Map<String, byte[]> scripts = Map.of(
                "default/Rules.groovy", "println 'hello'".getBytes(),
                "custom/Transform.groovy", "context.setHeader('x-custom', 'value')".getBytes()
        );
        Instant now = Instant.now();
        RulesBundle original = new RulesBundle(42L, now, "unit-test", scripts);

        // Serializar
        Path persistFile = tempDir.resolve("bundle.dat");
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new FileOutputStream(persistFile.toFile()))) {
            oos.writeObject(original);
        }

        assertTrue(Files.exists(persistFile), "File should exist after serialization");
        assertTrue(Files.size(persistFile) > 0, "Serialized file should not be empty");

        // Desserializar
        RulesBundle restored;
        try (ObjectInputStream ois = new ObjectInputStream(
                new FileInputStream(persistFile.toFile()))) {
            restored = (RulesBundle) ois.readObject();
        }

        // Verificar integridade
        assertEquals(42L, restored.version());
        assertEquals(now, restored.deployedAt());
        assertEquals("unit-test", restored.deployedBy());
        assertEquals(2, restored.scripts().size());
        assertArrayEquals("println 'hello'".getBytes(),
                restored.scripts().get("default/Rules.groovy"));
        assertArrayEquals("context.setHeader('x-custom', 'value')".getBytes(),
                restored.scripts().get("custom/Transform.groovy"));
    }

    @Test
    @Order(2)
    @DisplayName("T2: RulesBundle com scripts vazios serializa corretamente")
    void testEmptyBundleSerialization() throws IOException, ClassNotFoundException {
        RulesBundle original = new RulesBundle(1L, Instant.now(), "test", Map.of());

        Path persistFile = tempDir.resolve("empty-bundle.dat");
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new FileOutputStream(persistFile.toFile()))) {
            oos.writeObject(original);
        }

        RulesBundle restored;
        try (ObjectInputStream ois = new ObjectInputStream(
                new FileInputStream(persistFile.toFile()))) {
            restored = (RulesBundle) ois.readObject();
        }

        assertEquals(1L, restored.version());
        assertTrue(restored.scripts().isEmpty(), "Empty scripts map should be preserved");
    }

    // ─── Directory cleanup ──────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("T3: cleanDirectory remove conteúdo mas preserva o diretório raiz")
    void testCleanDirectoryPreservesRoot() throws IOException {
        // Setup: criar estrutura de arquivos
        Path rulesDir = tempDir.resolve("rules");
        Files.createDirectories(rulesDir);

        // Arquivos no nível raiz
        Files.writeString(rulesDir.resolve("Rules.groovy"), "println 'rule1'");
        Files.writeString(rulesDir.resolve("Transform.groovy"), "println 'transform'");

        // Subdiretório com arquivos
        Path subDir = rulesDir.resolve("custom");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("CustomRule.groovy"), "println 'custom'");

        // Subdiretório aninhado
        Path nestedDir = subDir.resolve("nested");
        Files.createDirectories(nestedDir);
        Files.writeString(nestedDir.resolve("Nested.groovy"), "println 'nested'");

        // Verificar que tudo foi criado
        assertEquals(4, countFiles(rulesDir), "Should have 4 files before cleanup");

        // Executar limpeza (mesma lógica que RulesBundleManager.cleanDirectory)
        cleanDirectory(rulesDir);

        // Verificar
        assertTrue(Files.exists(rulesDir), "Root directory should still exist");
        assertTrue(Files.isDirectory(rulesDir), "Root should still be a directory");
        assertEquals(0, countFiles(rulesDir), "Root should be empty after cleanup");
    }

    @Test
    @Order(4)
    @DisplayName("T4: cleanDirectory em diretório já vazio não lança exceção")
    void testCleanEmptyDirectory() throws IOException {
        Path emptyDir = tempDir.resolve("empty-rules");
        Files.createDirectories(emptyDir);

        assertDoesNotThrow(() -> cleanDirectory(emptyDir),
                "Cleaning an empty directory should not throw");

        assertTrue(Files.exists(emptyDir), "Directory should still exist");
    }

    @Test
    @Order(5)
    @DisplayName("T5: Materialização de scripts cria estrutura de diretórios correta")
    void testScriptMaterialization() throws IOException {
        Map<String, byte[]> scripts = Map.of(
                "default/Rules.groovy", "println 'default'".getBytes(),
                "custom/deep/Nested.groovy", "println 'nested'".getBytes()
        );

        Path rulesDir = tempDir.resolve("materialized-rules");
        Files.createDirectories(rulesDir);

        // Materializar (mesma lógica que RulesBundleManager.applyBundleLocally)
        for (Map.Entry<String, byte[]> entry : scripts.entrySet()) {
            Path scriptPath = rulesDir.resolve(entry.getKey());
            Files.createDirectories(scriptPath.getParent());
            Files.write(scriptPath, entry.getValue());
        }

        // Verificar estrutura
        assertTrue(Files.exists(rulesDir.resolve("default/Rules.groovy")));
        assertTrue(Files.exists(rulesDir.resolve("custom/deep/Nested.groovy")));

        assertEquals("println 'default'",
                Files.readString(rulesDir.resolve("default/Rules.groovy")));
        assertEquals("println 'nested'",
                Files.readString(rulesDir.resolve("custom/deep/Nested.groovy")));
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    /**
     * Replica a lógica de {@code RulesBundleManager.cleanDirectory()} para teste.
     */
    private void cleanDirectory(Path dir) throws IOException {
        Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException exc)
                    throws IOException {
                if (!d.equals(dir)) {
                    Files.delete(d);
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private long countFiles(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }
}
