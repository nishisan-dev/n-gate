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
import org.springframework.stereotype.Service;
import zipkin2.Span;
import zipkin2.reporter.AsyncReporter;
import zipkin2.reporter.brave.AsyncZipkinSpanHandler;
import zipkin2.reporter.okhttp3.OkHttpSender;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura at gmail.com>
 * created 05.09.2024
 */
@Service
public class TracerService {

    private static final String DEFAULT_ZIPKIN_ENDPOINT = "http://zipkin:9411/api/v2/spans";

    private OkHttpSender sender;
    private AsyncReporter<Span> reporter;
    private AsyncZipkinSpanHandler spanHandler;

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

    private void initSender() {
        this.sender = OkHttpSender.newBuilder().endpoint(resolveZipkinEndpoint()).build();
        this.reporter = AsyncReporter.create(sender);
        this.spanHandler = AsyncZipkinSpanHandler
                .newBuilder(sender)
                .alwaysReportSpans(true)
                .build(); // don't forget to close!
        
    }

    public Tracer getTracing(String serviceName) {
        if(this.sender ==null){
            this.initSender();
        }
        Tracing tracing = Tracing.newBuilder()
                .localServiceName(serviceName)
                .addSpanHandler(spanHandler)
                .sampler(Sampler.ALWAYS_SAMPLE)
                .build();

        Tracer tracer = tracing.tracer();
        return tracer;

    }

}
