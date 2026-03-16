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
package dev.nishisan.ngate.dashboard.storage;

import dev.nishisan.ngate.configuration.DashboardConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Storage H2 embedded para métricas históricas e eventos do dashboard.
 * <p>
 * Usa JDBC puro (sem JPA) para manter leveza. Schema auto-criado no startup.
 * Suporta retenção configurável com purge automático.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
public class DashboardStorageService {

    private static final Logger logger = LogManager.getLogger(DashboardStorageService.class);

    private static final String CREATE_METRIC_SNAPSHOT_TABLE = """
            CREATE TABLE IF NOT EXISTS metric_snapshot (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                timestamp TIMESTAMP NOT NULL,
                metric_name VARCHAR(255) NOT NULL,
                tags_json VARCHAR(1024),
                value DOUBLE NOT NULL
            )
            """;

    private static final String CREATE_EVENT_LOG_TABLE = """
            CREATE TABLE IF NOT EXISTS event_log (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                timestamp TIMESTAMP NOT NULL,
                event_type VARCHAR(64) NOT NULL,
                source VARCHAR(128) NOT NULL,
                details_json VARCHAR(4096)
            )
            """;

    private static final String CREATE_METRIC_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_metric_snapshot_name_ts
            ON metric_snapshot (metric_name, timestamp)
            """;

    private static final String CREATE_EVENT_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_event_log_ts
            ON event_log (timestamp)
            """;

    private final String jdbcUrl;
    private final int retentionHours;

    /**
     * Inicializa o storage H2 com base na configuração do dashboard.
     *
     * @param config configuração de storage do dashboard
     */
    public DashboardStorageService(DashboardConfiguration.DashboardStorageConfiguration config) {
        // Garante que o diretório existe
        File dataDir = new File(config.getPath());
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }

        this.jdbcUrl = "jdbc:h2:file:" + config.getPath() + "/metrics;AUTO_SERVER=TRUE";
        this.retentionHours = config.getRetentionHours();

        initSchema();
        logger.info("Dashboard storage inicializado: {} (retenção: {}h)", jdbcUrl, retentionHours);
    }

    // ─── Schema ─────────────────────────────────────────────────────────

    private void initSchema() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_METRIC_SNAPSHOT_TABLE);
            stmt.execute(CREATE_EVENT_LOG_TABLE);
            stmt.execute(CREATE_METRIC_INDEX);
            stmt.execute(CREATE_EVENT_INDEX);
        } catch (SQLException e) {
            logger.error("Falha ao inicializar schema do dashboard storage", e);
            throw new RuntimeException("Dashboard storage init failed", e);
        }
    }

    // ─── Métricas ───────────────────────────────────────────────────────

    /**
     * Salva um snapshot de métrica com tags.
     */
    public void saveSnapshot(String metricName, String tagsJson, double value) {
        String sql = "INSERT INTO metric_snapshot (timestamp, metric_name, tags_json, value) VALUES (?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, metricName);
            ps.setString(3, tagsJson);
            ps.setDouble(4, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Falha ao salvar snapshot de métrica '{}': {}", metricName, e.getMessage());
        }
    }

    /**
     * Salva um batch de snapshots de forma eficiente.
     */
    public void saveSnapshotBatch(List<MetricSnapshotRecord> records) {
        String sql = "INSERT INTO metric_snapshot (timestamp, metric_name, tags_json, value) VALUES (?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            Timestamp now = Timestamp.from(Instant.now());
            for (MetricSnapshotRecord record : records) {
                ps.setTimestamp(1, now);
                ps.setString(2, record.name());
                ps.setString(3, record.tagsJson());
                ps.setDouble(4, record.value());
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            logger.warn("Falha ao salvar batch de snapshots: {}", e.getMessage());
        }
    }

    /**
     * Consulta métricas históricas por nome e range temporal.
     */
    public List<MetricSnapshotRecord> queryMetrics(String metricName, Instant from, Instant to) {
        String sql = "SELECT timestamp, metric_name, tags_json, value FROM metric_snapshot " +
                "WHERE metric_name = ? AND timestamp BETWEEN ? AND ? ORDER BY timestamp";
        List<MetricSnapshotRecord> results = new ArrayList<>();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, metricName);
            ps.setTimestamp(2, Timestamp.from(from));
            ps.setTimestamp(3, Timestamp.from(to));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new MetricSnapshotRecord(
                            rs.getTimestamp("timestamp").toInstant(),
                            rs.getString("metric_name"),
                            rs.getString("tags_json"),
                            rs.getDouble("value")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.warn("Falha ao consultar métricas '{}': {}", metricName, e.getMessage());
        }
        return results;
    }

    /**
     * Lista todas as métricas distintas presentes no storage.
     */
    public List<String> listMetricNames() {
        String sql = "SELECT DISTINCT metric_name FROM metric_snapshot ORDER BY metric_name";
        List<String> names = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                names.add(rs.getString("metric_name"));
            }
        } catch (SQLException e) {
            logger.warn("Falha ao listar métricas: {}", e.getMessage());
        }
        return names;
    }

    // ─── Eventos ────────────────────────────────────────────────────────

    /**
     * Salva um evento (transição de circuit breaker, rate limit, pool change, etc.)
     */
    public void saveEvent(String eventType, String source, String detailsJson) {
        String sql = "INSERT INTO event_log (timestamp, event_type, source, details_json) VALUES (?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, eventType);
            ps.setString(3, source);
            ps.setString(4, detailsJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Falha ao salvar evento '{}': {}", eventType, e.getMessage());
        }
    }

    /**
     * Consulta os últimos N eventos.
     */
    public List<EventLogRecord> getRecentEvents(int limit) {
        String sql = "SELECT timestamp, event_type, source, details_json FROM event_log " +
                "ORDER BY timestamp DESC LIMIT ?";
        List<EventLogRecord> results = new ArrayList<>();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new EventLogRecord(
                            rs.getTimestamp("timestamp").toInstant(),
                            rs.getString("event_type"),
                            rs.getString("source"),
                            rs.getString("details_json")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.warn("Falha ao consultar eventos: {}", e.getMessage());
        }
        return results;
    }

    // ─── Purge (Retenção) ───────────────────────────────────────────────

    /**
     * Remove dados mais antigos que o período de retenção configurado.
     */
    public void purgeExpired() {
        Instant cutoff = Instant.now().minusSeconds(retentionHours * 3600L);
        Timestamp cutoffTs = Timestamp.from(cutoff);

        try (Connection conn = getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM metric_snapshot WHERE timestamp < ?")) {
                ps.setTimestamp(1, cutoffTs);
                int deleted = ps.executeUpdate();
                if (deleted > 0) {
                    logger.info("Dashboard storage: purged {} metric snapshots (retenção: {}h)", deleted, retentionHours);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM event_log WHERE timestamp < ?")) {
                ps.setTimestamp(1, cutoffTs);
                int deleted = ps.executeUpdate();
                if (deleted > 0) {
                    logger.info("Dashboard storage: purged {} events (retenção: {}h)", deleted, retentionHours);
                }
            }
        } catch (SQLException e) {
            logger.warn("Falha ao purgar dados expirados: {}", e.getMessage());
        }
    }

    // ─── Internal ───────────────────────────────────────────────────────

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, "sa", "");
    }

    /**
     * Fecha o storage (para shutdown graceful).
     */
    public void shutdown() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SHUTDOWN COMPACT");
            logger.info("Dashboard storage shutdown completo");
        } catch (SQLException e) {
            logger.warn("Falha ao shutdown do storage: {}", e.getMessage());
        }
    }

    // ─── Records ────────────────────────────────────────────────────────

    public record MetricSnapshotRecord(Instant timestamp, String name, String tagsJson, double value) {}

    public record EventLogRecord(Instant timestamp, String eventType, String source, String detailsJson) {}
}
