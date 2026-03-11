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

import dev.nishisan.ngate.cluster.ClusterService;
import dev.nishisan.ngate.configuration.AdminApiConfiguration;
import dev.nishisan.ngate.configuration.ServerConfiguration;
import dev.nishisan.ngate.manager.ConfigurationManager;
import dev.nishisan.ngate.rules.RulesBundle;
import dev.nishisan.ngate.rules.RulesBundleManager;
import dev.nishisan.utils.ngrid.common.NodeInfo;
import jakarta.servlet.http.HttpServletRequest;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Admin API endpoints para deploy de rules e consulta de versão.
 * <p>
 * Roda no servidor Spring Boot. Em alguns ambientes ele compartilha a mesma porta
 * do management/Actuator; em outros, responde no {@code server.port}.
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

    @Autowired(required = false)
    private ClusterService clusterService;

    private final OkHttpClient httpClient = new OkHttpClient();

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
            ResponseEntity<?> forwarded = maybeForwardDeployToLeader(files, request);
            if (forwarded != null) {
                return forwarded;
            }

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

    private ResponseEntity<?> maybeForwardDeployToLeader(MultipartFile[] files, HttpServletRequest request) throws IOException {
        if (clusterService == null || !clusterService.isClusterMode() || clusterService.isLeader()) {
            return null;
        }

        Optional<NodeInfo> leaderInfo = clusterService.getNGridNode().coordinator().leaderInfo();
        if (leaderInfo.isEmpty()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "Cluster leader is not available",
                    "timestamp", Instant.now().toString()
            ));
        }

        String leaderHost = resolveLeaderHost(leaderInfo.get());
        if (leaderHost == null || leaderHost.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "Unable to resolve leader host",
                    "timestamp", Instant.now().toString()
            ));
        }

        URI forwardUri = URI.create(String.format("%s://%s:%d%s",
                request.getScheme(),
                leaderHost,
                request.getLocalPort(),
                request.getRequestURI()));

        MultipartBody.Builder bodyBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        for (MultipartFile file : files) {
            String filename = file.getOriginalFilename();
            if (filename == null || filename.isBlank()) {
                filename = file.getName();
            }
            okhttp3.MediaType contentType = file.getContentType() != null
                    ? okhttp3.MediaType.parse(file.getContentType())
                    : okhttp3.MediaType.parse("text/plain");
            bodyBuilder.addFormDataPart("scripts", filename,
                    RequestBody.create(file.getBytes(), contentType));
        }

        Request.Builder requestBuilder = new Request.Builder()
                .url(forwardUri.toString())
                .post(bodyBuilder.build());

        String providedKey = request.getHeader("X-API-Key");
        if (providedKey != null && !providedKey.isBlank()) {
            requestBuilder.addHeader("X-API-Key", providedKey);
        }

        logger.info("Forwarding rules deploy to cluster leader [{}] via [{}]", leaderInfo.get().nodeId().value(), forwardUri);

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            org.springframework.http.MediaType responseType = MediaType.APPLICATION_JSON;
            String contentType = response.header("Content-Type");
            if (contentType != null) {
                try {
                    responseType = MediaType.parseMediaType(contentType);
                } catch (Exception ignored) {
                    responseType = MediaType.APPLICATION_JSON;
                }
            }
            return ResponseEntity.status(response.code())
                    .contentType(responseType)
                    .body(responseBody);
        }
    }

    private String resolveLeaderHost(NodeInfo leaderInfo) {
        if (leaderInfo.host() != null
                && !leaderInfo.host().isBlank()
                && !"0.0.0.0".equals(leaderInfo.host())) {
            return leaderInfo.host();
        }
        if (leaderInfo.nodeId() != null) {
            return leaderInfo.nodeId().value();
        }
        return null;
    }
}
