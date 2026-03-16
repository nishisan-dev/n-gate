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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Storage H2 embedded com modelo RRD (Round Robin Database) para métricas.
 * <p>
 * Organiza dados em tiers de resolução decrescente:
 * <ul>
 *   <li><b>raw</b>   — resolução do scrape (15s default), retenção: 6h</li>
 *   <li><b>5min</b>  — consolidado a cada 5 min (min/avg/max/count), retenção: 7 dias</li>
 *   <li><b>10min</b> — consolidado a cada 10 min, retenção: 30 dias</li>
 *   <li><b>1hour</b> — consolidado a cada 1 hora, retenção: 365 dias</li>
 * </ul>
 * Similar a RRDtool/Graphite Whisper: volume de dados previsível e constante.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
public class DashboardStorageService {

    private static final Logger logger = LogManager.getLogger(DashboardStorageService.class);

    // ─── Tier Definitions ───────────────────────────────────────────────

    /**
     * Define um tier de armazenamento RRD.
     */
    public enum Tier {
        RAW("raw", Duration.ofHours(6)),
        FIVE_MIN("5min", Duration.ofDays(7)),
        TEN_MIN("10min", Duration.ofDays(30)),
        ONE_HOUR("1hour", Duration.ofDays(365));

        private final String name;
        private final Duration retention;

        Tier(String name, Duration retention) {
            this.name = name;
            this.retention = retention;
        }

        public String tierName() {
            return name;
        }

        public Duration getRetention() {
            return retention;
        }
    }

    // ─── Schema ─────────────────────────────────────────────────────────

    private static final String CREATE_METRIC_SERIES_TABLE = """
            CREATE TABLE IF NOT EXISTS metric_series (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                metric_name VARCHAR(255) NOT NULL,
                tier VARCHAR(10) NOT NULL,
                bucket_ts TIMESTAMP NOT NULL,
                val_min DOUBLE NOT NULL,
                val_avg DOUBLE NOT NULL,
                val_max DOUBLE NOT NULL,
                val_count BIGINT NOT NULL
            )
            """;

    private static final String CREATE_METRIC_SERIES_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_series_lookup
            ON metric_series (metric_name, tier, bucket_ts)
            """;

    private static final String CREATE_METRIC_SERIES_UNIQUE = """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_series_unique
            ON metric_series (metric_name, tier, bucket_ts)
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

    private static final String CREATE_EVENT_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_event_log_ts
            ON event_log (timestamp)
            """;

    private final String jdbcUrl;

    public DashboardStorageService(DashboardConfiguration.DashboardStorageConfiguration config) {
        File dataDir = new File(config.getPath());
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }

        this.jdbcUrl = "jdbc:h2:file:" + config.getPath() + "/metrics;AUTO_SERVER=TRUE";

        initSchema();
        logger.info("Dashboard RRD storage inicializado: {} (tiers: raw=6h, 5min=7d, 10min=30d, 1hour=365d)",
                jdbcUrl);
    }

    private void initSchema() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            // Migração: drop tabela antiga se existir
            stmt.execute("DROP TABLE IF EXISTS metric_snapshot");

            stmt.execute(CREATE_METRIC_SERIES_TABLE);
            stmt.execute(CREATE_METRIC_SERIES_INDEX);
            stmt.execute(CREATE_METRIC_SERIES_UNIQUE);
            stmt.execute(CREATE_EVENT_LOG_TABLE);
            stmt.execute(CREATE_EVENT_INDEX);
        } catch (SQLException e) {
            logger.error("Falha ao inicializar schema do dashboard RRD storage", e);
            throw new RuntimeException("Dashboard storage init failed", e);
        }
    }

    // ─── Raw Metric Insert ──────────────────────────────────────────────

    /**
     * Salva um snapshot raw de métrica (tier 'raw').
     * Cada chamada corresponde a um scrape do MeterRegistry.
     */
    public void saveRawSnapshot(String metricName, double value) {
        insertSeries(metricName, Tier.RAW.tierName(), Instant.now(), value, value, value, 1);
    }

    /**
     * Salva batch de snapshots raw.
     */
    public void saveRawSnapshotBatch(List<MetricSnapshotRecord> records) {
        String sql = "INSERT INTO metric_series (metric_name, tier, bucket_ts, val_min, val_avg, val_max, val_count) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            Timestamp now = Timestamp.from(Instant.now());
            for (MetricSnapshotRecord record : records) {
                ps.setString(1, record.name());
                ps.setString(2, Tier.RAW.tierName());
                ps.setTimestamp(3, now);
                ps.setDouble(4, record.value());
                ps.setDouble(5, record.value());
                ps.setDouble(6, record.value());
                ps.setLong(7, 1);
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            logger.warn("Falha ao salvar batch de snapshots raw: {}", e.getMessage());
        }
    }

    // ─── Consolidation Engine ───────────────────────────────────────────

    /**
     * Consolida dados de um tier fonte para um tier destino.
     * Agrupa por metric_name e bucket temporal, calculando min/avg/max/count.
     *
     * @param sourceTier     tier fonte (ex: RAW)
     * @param targetTier     tier destino (ex: FIVE_MIN)
     * @param bucketMinutes  tamanho do bucket em minutos (ex: 5)
     */
    public void consolidate(Tier sourceTier, Tier targetTier, int bucketMinutes) {
        logger.debug("Consolidando {} → {} (bucket={}min)", sourceTier.tierName(), targetTier.tierName(), bucketMinutes);

        // Calcula o bucket temporal truncando ao intervalo
        // Usa DATEADD + DATEDIFF para alinhar ao bucket
        String sql = """
                MERGE INTO metric_series (metric_name, tier, bucket_ts, val_min, val_avg, val_max, val_count)
                KEY (metric_name, tier, bucket_ts)
                SELECT
                    metric_name,
                    ? AS tier,
                    DATEADD('MINUTE',
                        (DATEDIFF('MINUTE', TIMESTAMP '2000-01-01', bucket_ts) / ?) * ?,
                        TIMESTAMP '2000-01-01'
                    ) AS bucket_ts,
                    MIN(val_min) AS val_min,
                    CASE WHEN SUM(val_count) > 0
                         THEN SUM(val_avg * val_count) / SUM(val_count)
                         ELSE 0 END AS val_avg,
                    MAX(val_max) AS val_max,
                    SUM(val_count) AS val_count
                FROM metric_series
                WHERE tier = ?
                  AND bucket_ts >= ?
                GROUP BY metric_name,
                         DATEADD('MINUTE',
                             (DATEDIFF('MINUTE', TIMESTAMP '2000-01-01', bucket_ts) / ?) * ?,
                             TIMESTAMP '2000-01-01'
                         )
                """;

        // Consolida dados da última janela relevante (2x o bucket para capturar edges)
        Instant since = Instant.now().minus(bucketMinutes * 3L, ChronoUnit.MINUTES);

        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, targetTier.tierName());
            ps.setInt(2, bucketMinutes);
            ps.setInt(3, bucketMinutes);
            ps.setString(4, sourceTier.tierName());
            ps.setTimestamp(5, Timestamp.from(since));
            ps.setInt(6, bucketMinutes);
            ps.setInt(7, bucketMinutes);

            int merged = ps.executeUpdate();
            if (merged > 0) {
                logger.debug("Consolidação {} → {}: {} buckets atualizados", sourceTier.tierName(),
                        targetTier.tierName(), merged);
            }
        } catch (SQLException e) {
            logger.warn("Falha na consolidação {} → {}: {}", sourceTier.tierName(), targetTier.tierName(),
                    e.getMessage());
        }
    }

    /**
     * Executa todas as consolidações: raw→5min, 5min→10min, 10min→1hour.
     * Chamado pelo scheduler no DashboardServer.
     */
    public void runFullConsolidation() {
        consolidate(Tier.RAW, Tier.FIVE_MIN, 5);
        consolidate(Tier.FIVE_MIN, Tier.TEN_MIN, 10);
        consolidate(Tier.TEN_MIN, Tier.ONE_HOUR, 60);
    }

    // ─── Query com Tier Automático ──────────────────────────────────────

    /**
     * Consulta métricas no tier mais adequado para o range temporal solicitado:
     * <ul>
     *   <li>≤ 6h → raw</li>
     *   <li>≤ 24h → 5min</li>
     *   <li>≤ 7d → 10min</li>
     *   <li>&gt; 7d → 1hour</li>
     * </ul>
     */
    public List<SeriesRecord> queryMetrics(String metricName, Instant from, Instant to) {
        Duration range = Duration.between(from, to);
        String tier = resolveTier(range);
        return queryMetricsWithTier(metricName, tier, from, to);
    }

    /**
     * Consulta métricas forçando um tier específico.
     */
    public List<SeriesRecord> queryMetricsWithTier(String metricName, String tier, Instant from, Instant to) {
        String sql = """
                SELECT bucket_ts, val_min, val_avg, val_max, val_count
                FROM metric_series
                WHERE metric_name = ? AND tier = ? AND bucket_ts BETWEEN ? AND ?
                ORDER BY bucket_ts
                """;
        List<SeriesRecord> results = new ArrayList<>();
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, metricName);
            ps.setString(2, tier);
            ps.setTimestamp(3, Timestamp.from(from));
            ps.setTimestamp(4, Timestamp.from(to));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new SeriesRecord(
                            rs.getTimestamp("bucket_ts").toInstant(),
                            rs.getDouble("val_min"),
                            rs.getDouble("val_avg"),
                            rs.getDouble("val_max"),
                            rs.getLong("val_count")
                    ));
                }
            }
        } catch (SQLException e) {
            logger.warn("Falha ao consultar série '{}' tier '{}': {}", metricName, tier, e.getMessage());
        }
        return results;
    }

    /**
     * Resolve o melhor tier para um range temporal.
     */
    public static String resolveTier(Duration range) {
        if (range.toHours() <= 6) return Tier.RAW.tierName();
        if (range.toHours() <= 24) return Tier.FIVE_MIN.tierName();
        if (range.toDays() <= 7) return Tier.TEN_MIN.tierName();
        return Tier.ONE_HOUR.tierName();
    }

    /**
     * Lista todas as métricas distintas.
     */
    public List<String> listMetricNames() {
        String sql = "SELECT DISTINCT metric_name FROM metric_series ORDER BY metric_name";
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

    // ─── Purge per-Tier ─────────────────────────────────────────────────

    /**
     * Remove dados mais antigos que a retenção de cada tier.
     */
    public void purgeExpired() {
        for (Tier tier : Tier.values()) {
            Instant cutoff = Instant.now().minus(tier.getRetention());
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "DELETE FROM metric_series WHERE tier = ? AND bucket_ts < ?")) {
                ps.setString(1, tier.tierName());
                ps.setTimestamp(2, Timestamp.from(cutoff));
                int deleted = ps.executeUpdate();
                if (deleted > 0) {
                    logger.info("RRD purge [{}]: {} registros removidos (retenção: {})",
                            tier.tierName(), deleted, tier.getRetention());
                }
            } catch (SQLException e) {
                logger.warn("Falha ao purgar tier '{}': {}", tier.tierName(), e.getMessage());
            }
        }

        // Purge eventos (30 dias)
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM event_log WHERE timestamp < ?")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                logger.info("Event purge: {} eventos removidos", deleted);
            }
        } catch (SQLException e) {
            logger.warn("Falha ao purgar eventos: {}", e.getMessage());
        }
    }

    // ─── Internal ───────────────────────────────────────────────────────

    private void insertSeries(String metricName, String tier, Instant bucketTs,
                              double min, double avg, double max, long count) {
        String sql = "INSERT INTO metric_series (metric_name, tier, bucket_ts, val_min, val_avg, val_max, val_count) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, metricName);
            ps.setString(2, tier);
            ps.setTimestamp(3, Timestamp.from(bucketTs));
            ps.setDouble(4, min);
            ps.setDouble(5, avg);
            ps.setDouble(6, max);
            ps.setLong(7, count);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("Falha ao inserir série '{}' tier '{}': {}", metricName, tier, e.getMessage());
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, "sa", "");
    }

    public void shutdown() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("SHUTDOWN COMPACT");
            logger.info("Dashboard RRD storage shutdown completo");
        } catch (SQLException e) {
            logger.warn("Falha ao shutdown do storage: {}", e.getMessage());
        }
    }

    // ─── Records ────────────────────────────────────────────────────────

    /** Record para inserção raw (compatibilidade com MetricsCollectorService). */
    public record MetricSnapshotRecord(Instant timestamp, String name, String tagsJson, double value) {}

    /** Record para resultados de query com agregações RRD. */
    public record SeriesRecord(Instant timestamp, double min, double avg, double max, long count) {}

    /** Record para eventos. */
    public record EventLogRecord(Instant timestamp, String eventType, String source, String detailsJson) {}
}
