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
package dev.nishisan.ngate.dashboard.collector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nishisan.ngate.dashboard.storage.DashboardStorageService;
import dev.nishisan.ngate.dashboard.storage.DashboardStorageService.MetricSnapshotRecord;
import io.micrometer.core.instrument.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Coleta métricas do {@link MeterRegistry} (in-process, sem HTTP) e persiste
 * snapshots no {@link DashboardStorageService} em intervalos configuráveis.
 * <p>
 * Foca em métricas do n-gate ({@code ngate.*}), Resilience4j ({@code resilience4j.*})
 * e JVM ({@code jvm.*}) para manter o volume de storage controlado.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-16
 */
public class MetricsCollectorService {

    private static final Logger logger = LogManager.getLogger(MetricsCollectorService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Prefixos de métricas que serão coletados.
     */
    private static final List<String> METRIC_PREFIXES = List.of(
            "ngate.",
            "resilience4j.",
            "jvm.memory.",
            "jvm.threads.",
            "jvm.gc.",
            "system.cpu.",
            "process.cpu."
    );

    private final MeterRegistry registry;
    private final DashboardStorageService storage;

    public MetricsCollectorService(MeterRegistry registry, DashboardStorageService storage) {
        this.registry = registry;
        this.storage = storage;
    }

    /**
     * Executa uma coleta completa de métricas e persiste no storage.
     * Chamado periodicamente pelo scheduler do {@code DashboardService}.
     */
    public void collect() {
        List<MetricSnapshotRecord> records = new ArrayList<>();

        registry.getMeters().forEach(meter -> {
            String name = meter.getId().getName();
            if (!isRelevantMetric(name)) {
                return;
            }

            String tagsJson = serializeTags(meter.getId().getTags());

            if (meter instanceof Counter counter) {
                records.add(new MetricSnapshotRecord(Instant.now(), name, tagsJson, counter.count()));
            } else if (meter instanceof Timer timer) {
                // Para Timers, salvamos count e média
                records.add(new MetricSnapshotRecord(Instant.now(), name + ".count", tagsJson, timer.count()));
                records.add(new MetricSnapshotRecord(Instant.now(), name + ".mean", tagsJson, timer.mean(TimeUnit.MILLISECONDS)));
                records.add(new MetricSnapshotRecord(Instant.now(), name + ".max", tagsJson, timer.max(TimeUnit.MILLISECONDS)));
            } else if (meter instanceof Gauge gauge) {
                double value = gauge.value();
                if (Double.isFinite(value)) {
                    records.add(new MetricSnapshotRecord(Instant.now(), name, tagsJson, value));
                }
            } else if (meter instanceof DistributionSummary summary) {
                records.add(new MetricSnapshotRecord(Instant.now(), name + ".count", tagsJson, summary.count()));
                records.add(new MetricSnapshotRecord(Instant.now(), name + ".mean", tagsJson, summary.mean()));
                records.add(new MetricSnapshotRecord(Instant.now(), name + ".max", tagsJson, summary.max()));
            }
        });

        if (!records.isEmpty()) {
            storage.saveRawSnapshotBatch(records);
            logger.debug("Dashboard collector: {} snapshots salvos", records.size());
        }
    }

    /**
     * Retorna as métricas atuais como mapa estruturado para a API REST.
     * Formato otimizado para consumo pelo frontend React.
     */
    public Map<String, Object> getCurrentMetrics() {
        Map<String, Object> result = new LinkedHashMap<>();

        registry.getMeters().forEach(meter -> {
            String name = meter.getId().getName();
            if (!isRelevantMetric(name)) {
                return;
            }

            Map<String, Object> meterData = new LinkedHashMap<>();
            meterData.put("tags", tagsToMap(meter.getId().getTags()));

            if (meter instanceof Counter counter) {
                meterData.put("type", "counter");
                meterData.put("value", counter.count());
            } else if (meter instanceof Timer timer) {
                meterData.put("type", "timer");
                meterData.put("count", timer.count());
                meterData.put("mean", timer.mean(TimeUnit.MILLISECONDS));
                meterData.put("max", timer.max(TimeUnit.MILLISECONDS));
                meterData.put("totalTime", timer.totalTime(TimeUnit.MILLISECONDS));
            } else if (meter instanceof Gauge gauge) {
                double value = gauge.value();
                if (!Double.isFinite(value)) return;
                meterData.put("type", "gauge");
                meterData.put("value", value);
            } else if (meter instanceof DistributionSummary summary) {
                meterData.put("type", "summary");
                meterData.put("count", summary.count());
                meterData.put("mean", summary.mean());
                meterData.put("max", summary.max());
            } else {
                return; // Ignora tipos desconhecidos
            }

            // Usa nome + tags como chave para diferenciar séries
            String key = name + tagsToSuffix(meter.getId().getTags());
            result.put(key, meterData);
        });

        return result;
    }

    // ─── Internal ───────────────────────────────────────────────────────

    private boolean isRelevantMetric(String name) {
        for (String prefix : METRIC_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String serializeTags(List<Tag> tags) {
        if (tags.isEmpty()) return "{}";
        Map<String, String> map = tagsToMap(tags);
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private Map<String, String> tagsToMap(List<Tag> tags) {
        return tags.stream().collect(Collectors.toMap(
                Tag::getKey, Tag::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    private String tagsToSuffix(List<Tag> tags) {
        if (tags.isEmpty()) return "";
        return "{" + tags.stream()
                .map(t -> t.getKey() + "=\"" + t.getValue() + "\"")
                .collect(Collectors.joining(",")) + "}";
    }
}
