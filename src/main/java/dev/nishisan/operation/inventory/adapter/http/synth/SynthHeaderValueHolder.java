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
package dev.nishisan.operation.inventory.adapter.http.synth;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import org.springframework.lang.Nullable;
import org.springframework.util.CollectionUtils;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura at gmail.com>
 * created 29.08.2024
 */
public class SynthHeaderValueHolder {

    private final List<Object> values = new LinkedList<>();

    public void setValue(@Nullable Object value) {
        this.values.clear();
        if (value != null) {
            this.values.add(value);
        }
    }

    public void addValue(Object value) {
        this.values.add(value);
    }

    public void addValues(Collection<?> values) {
        this.values.addAll(values);
    }

    public void addValueArray(Object values) {
        CollectionUtils.mergeArrayIntoCollection(values, this.values);
    }

    public List<Object> getValues() {
        return Collections.unmodifiableList(this.values);
    }

    public List<String> getStringValues() {
        return this.values.stream().map(Object::toString).toList();
    }

    @Nullable
    public Object getValue() {
        return (!this.values.isEmpty() ? this.values.get(0) : null);
    }

    @Nullable
    public String getStringValue() {
        return (!this.values.isEmpty() ? String.valueOf(this.values.get(0)) : null);
    }

    @Override
    public String toString() {
        return this.values.toString();
    }
}
