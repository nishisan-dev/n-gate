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
package dev.nishisan.ngate.observability.wrappers;

import brave.Span;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura at gmail.com>
 * created 06.09.2024
 */
public class SpanWrapper {

    private final String name;
    private final Span span;

    public SpanWrapper(String name, Span span) {
        this.name = name;
        this.span = span;
    }

    public String getName() {
        return name;
    }

    public Span getSpan() {
        return span;
    }

    public void finish() {
        this.span.finish();
    }

    public Span error(Throwable ex) {
        return this.span.error(ex);
    }

    public Span tag(String k, String v) {
        return this.span.tag(k, v);
    }

    public Span tag(String k, int v) {
        return this.span.tag(k, String.valueOf(v));
    }

    public Span tag(String k, long v) {
        return this.span.tag(k, String.valueOf(v));
    }
}
