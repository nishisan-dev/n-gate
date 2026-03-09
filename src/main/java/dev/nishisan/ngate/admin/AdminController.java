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
package dev.nishisan.ngate.admin;

import dev.nishisan.ngate.configuration.AdminApiConfiguration;
import dev.nishisan.ngate.configuration.ServerConfiguration;
import dev.nishisan.ngate.manager.ConfigurationManager;
import dev.nishisan.ngate.rules.RulesBundle;
import dev.nishisan.ngate.rules.RulesBundleManager;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Admin API endpoints para deploy de rules e consulta de versão.
 * <p>
 * Roda na porta de management do Actuator (9190, Spring Boot / Undertow),
 * separada do tráfego de proxy (Javalin 9090/9091).
 * <p>
 * Autenticação via header {@code X-API-Key} validado contra
 * {@code admin.apiKey} do adapter.yaml.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-09
 */
@RestController
@RequestMapping("/admin/rules")
public class AdminController {

    private static final Logger logger = LogManager.getLogger(AdminController.class);

    @Autowired
    private RulesBundleManager rulesBundleManager;

    @Autowired
    private ConfigurationManager configurationManager;

    /**
     * Trata erros de multipart (ex: request sem Content-Type multipart).
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<?> handleMultipartError(MultipartException ex) {
        logger.warn("Multipart error: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid multipart request: " + ex.getMessage(),
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * Deploy de rules via multipart upload.
     * Aceita um ou mais arquivos .groovy como partes do multipart.
     * O path relativo é reconstituído a partir do nome do arquivo (submittedFileName).
     */
    @PostMapping("/deploy")
    public ResponseEntity<?> deploy(
            @RequestParam("scripts") MultipartFile[] files,
            HttpServletRequest request) {

        // 1. Validar autenticação
        ResponseEntity<?> authError = validateApiKey(request);
        if (authError != null) {
            return authError;
        }

        if (files == null || files.length == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No script files provided",
                    "timestamp", Instant.now().toString()
            ));
        }

        try {
            // 2. Montar mapa de scripts
            Map<String, byte[]> scripts = new HashMap<>();
            for (MultipartFile file : files) {
                String filename = file.getOriginalFilename();
                if (filename == null || filename.isBlank()) {
                    filename = file.getName();
                }
                scripts.put(filename, file.getBytes());
                logger.info("Received script for deploy: [{}] ({} bytes)", filename, file.getSize());
            }

            // 3. Executar deploy
            String deployedBy = request.getRemoteAddr();
            RulesBundle bundle = rulesBundleManager.deploy(scripts, deployedBy);

            logger.info("Rules deploy completed — v{} by [{}] with {} script(s)",
                    bundle.version(), deployedBy, scripts.size());

            return ResponseEntity.ok(Map.of(
                    "status", "deployed",
                    "version", bundle.version(),
                    "deployedAt", bundle.deployedAt().toString(),
                    "deployedBy", bundle.deployedBy(),
                    "scriptCount", bundle.scripts().size()
            ));
        } catch (IOException ex) {
            logger.error("Failed to process deploy request", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Failed to process deploy: " + ex.getMessage(),
                    "timestamp", Instant.now().toString()
            ));
        }
    }

    /**
     * Consulta a versão do bundle ativo.
     */
    @GetMapping("/version")
    public ResponseEntity<?> version(HttpServletRequest request) {
        // Validar autenticação
        ResponseEntity<?> authError = validateApiKey(request);
        if (authError != null) {
            return authError;
        }

        RulesBundle active = rulesBundleManager.getActiveBundle();
        if (active == null) {
            return ResponseEntity.ok(Map.of(
                    "status", "no-bundle",
                    "message", "No rules bundle deployed — using default rules/ directory"
            ));
        }

        return ResponseEntity.ok(Map.of(
                "status", "active",
                "version", active.version(),
                "deployedAt", active.deployedAt().toString(),
                "deployedBy", active.deployedBy(),
                "scriptCount", active.scripts().size()
        ));
    }

    /**
     * Valida o header X-API-Key contra a configuração do admin.
     *
     * @return ResponseEntity com erro se inválido, null se OK
     */
    private ResponseEntity<?> validateApiKey(HttpServletRequest request) {
        AdminApiConfiguration adminConfig = getAdminConfig();

        if (adminConfig == null || !adminConfig.isEnabled()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "Admin API is not enabled",
                    "timestamp", Instant.now().toString()
            ));
        }

        String expectedKey = adminConfig.getApiKey();
        if (expectedKey == null || expectedKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "Admin API key is not configured",
                    "timestamp", Instant.now().toString()
            ));
        }

        String providedKey = request.getHeader("X-API-Key");
        if (providedKey == null || !providedKey.equals(expectedKey)) {
            logger.warn("Admin API authentication failed from [{}]", request.getRemoteAddr());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "Invalid or missing API key",
                    "timestamp", Instant.now().toString()
            ));
        }

        return null; // Auth OK
    }

    /**
     * Obtém a configuração admin a partir do primeiro endpoint configurado.
     */
    private AdminApiConfiguration getAdminConfig() {
        ServerConfiguration serverConfig = configurationManager.getServerConfiguration();
        if (serverConfig != null) {
            return serverConfig.getAdmin();
        }
        return null;
    }
}
