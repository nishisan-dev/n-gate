/*
 * Copyright (C) 2024 Lucas Nishimura <lucas.nishimura at gmail.com>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package dev.nishisan.operation.inventory.adapter.observabitliy.service;

import brave.Tracer;
import brave.Tracing;
import brave.sampler.Sampler;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;
import zipkin2.reporter.brave.AsyncZipkinSpanHandler;
import zipkin2.reporter.okhttp3.OkHttpSender;

/**
 * Gerencia instâncias de Tracing/Tracer do Brave para integração com Zipkin.
 * <p>
 * Cacheia instâncias por serviceName e garante shutdown graceful via
 * {@link DisposableBean}.
 *
 * @author Lucas Nishimura <lucas.nishimura at gmail.com>
 * created 05.09.2024
 */
@Service
public class TracerService implements DisposableBean {

    private static final Logger logger = LogManager.getLogger(TracerService.class);
    private static final String DEFAULT_ZIPKIN_ENDPOINT = "http://zipkin:9411/api/v2/spans";

    private OkHttpSender sender;
    private AsyncZipkinSpanHandler spanHandler;
    private final Map<String, Tracing> tracingInstances = new ConcurrentHashMap<>();

    private String resolveZipkinEndpoint() {
        String envEndpoint = System.getenv("ZIPKIN_ENDPOINT");
        if (envEndpoint != null && !envEndpoint.isBlank()) {
            return envEndpoint;
        }
        String systemPropertyEndpoint = System.getProperty("zipkin.endpoint");
        if (systemPropertyEndpoint != null && !systemPropertyEndpoint.isBlank()) {
            return systemPropertyEndpoint;
        }
        return DEFAULT_ZIPKIN_ENDPOINT;
    }

    private boolean isTracingEnabled() {
        String enabled = System.getenv("TRACING_ENABLED");
        if (enabled != null && enabled.equalsIgnoreCase("false")) {
            return false;
        }
        return true;
    }

    private synchronized void initSender() {
        if (this.sender != null) {
            return; // double-check para evitar re-inicialização
        }
        if (!isTracingEnabled()) {
            logger.info("Tracing is DISABLED (TRACING_ENABLED=false)");
            return;
        }
        String endpoint = resolveZipkinEndpoint();
        logger.info("Initializing Zipkin sender with endpoint: [{}]", endpoint);
        this.sender = OkHttpSender.newBuilder().endpoint(endpoint).build();
        this.spanHandler = AsyncZipkinSpanHandler
                .newBuilder(sender)
                .alwaysReportSpans(true)
                .build();
    }

    /**
     * Retorna a instância de {@link Tracing} cacheada para o serviceName dado.
     * Necessário para context propagation (B3 headers).
     *
     * @param serviceName nome do serviço para identificação no Zipkin
     * @return instância de Tracing
     */
    public Tracing getTracingInstance(String serviceName) {
        if (this.sender == null && isTracingEnabled()) {
            this.initSender();
        }
        return tracingInstances.computeIfAbsent(serviceName, name -> {
            if (!isTracingEnabled()) {
                logger.info("Creating NOOP Tracing instance for service: [{}] (tracing disabled)", name);
                return Tracing.newBuilder()
                        .localServiceName(name)
                        .sampler(Sampler.NEVER_SAMPLE)
                        .build();
            }
            logger.info("Creating new Tracing instance for service: [{}]", name);
            return Tracing.newBuilder()
                    .localServiceName(name)
                    .addSpanHandler(spanHandler)
                    .sampler(Sampler.ALWAYS_SAMPLE)
                    .build();
        });
    }

    /**
     * Retorna o {@link Tracer} associado ao serviceName dado.
     *
     * @param serviceName nome do serviço para identificação no Zipkin
     * @return instância de Tracer
     */
    public Tracer getTracer(String serviceName) {
        return getTracingInstance(serviceName).tracer();
    }

    /**
     * Mantém compatibilidade com código existente.
     *
     * @deprecated Usar {@link #getTracer(String)} para clareza.
     */
    @Deprecated
    public Tracer getTracing(String serviceName) {
        return getTracer(serviceName);
    }

    @Override
    public void destroy() {
        logger.info("Shutting down TracerService — closing {} tracing instances", tracingInstances.size());
        tracingInstances.values().forEach(tracing -> {
            try {
                tracing.close();
            } catch (Exception e) {
                logger.warn("Failed to close Tracing instance", e);
            }
        });
        tracingInstances.clear();

        if (spanHandler != null) {
            try {
                spanHandler.close();
            } catch (Exception e) {
                logger.warn("Failed to close SpanHandler", e);
            }
        }
        if (sender != null) {
            try {
                sender.close();
            } catch (Exception e) {
                logger.warn("Failed to close OkHttpSender", e);
            }
        }
    }
}
