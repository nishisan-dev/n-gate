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
package dev.nishisan.operation.inventory.adapter.observabitliy.wrappers;

import brave.Span;
import brave.Tracer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura at gmail.com>
 * created 06.09.2024
 */
public class TracerWrapper {

    private final Tracer currentTracer;
    private SpanWrapper currentSpan;
    private Map<String, SpanWrapper> allSpans = new ConcurrentHashMap<>();

    public TracerWrapper(Tracer currentTracer) {
        this.currentTracer = currentTracer;
    }

    public void addSpan(SpanWrapper span) {
        if (this.allSpans.containsKey(span.getName())) {
            this.allSpans.put(span.getName(), span);
        }
    }

    public Tracer getCurrentTracer() {
        return currentTracer;
    }

    public SpanWrapper getCurrentSpan() {
        return currentSpan;
    }

    public void setCurrentSpan(SpanWrapper currentSpan) {
        this.currentSpan = currentSpan;
    }

    public Map<String, SpanWrapper> getAllSpans() {
        return allSpans;
    }

    public SpanWrapper createSpan(String name) {
        if (this.currentSpan == null) {
            Span span = this.currentTracer.newTrace().name(name);
            SpanWrapper wrapper = new SpanWrapper(name, span);
            this.addSpan(wrapper);
            this.setCurrentSpan(wrapper);
            span.start();
            return wrapper;
        } else {
            Span span = this.currentTracer.newChild(this.currentSpan.getSpan().context()).name(name);
            SpanWrapper wrapper = new SpanWrapper(name, span);
            span.start();
            return wrapper;
        }
    }

    public SpanWrapper createChildSpan(String name) {
        Span span = this.currentTracer.newChild(this.currentSpan.getSpan().context()).name(name);
        SpanWrapper wrapper = new SpanWrapper(name, span);
        span.start();
        return wrapper;
    }

    public SpanWrapper createChildSpan(String name, SpanWrapper parent) {
        return this.createChildSpan(name, parent.getName());
    }

    public SpanWrapper createChildSpan(String name, String parent) {
        SpanWrapper parentSpan = this.allSpans.get(parent);
        if (parentSpan == null) {
            parentSpan = currentSpan;
        }
        Span span = this.currentTracer.newChild(parentSpan.getSpan().context()).name(name);

        SpanWrapper wrapper = new SpanWrapper(name, span);

        span.start();
        return wrapper;
    }

    public String getTraceId() {
        return this.currentSpan.getSpan().context().spanIdString();
    }
}
