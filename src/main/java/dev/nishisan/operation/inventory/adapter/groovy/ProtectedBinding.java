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
package dev.nishisan.operation.inventory.adapter.groovy;

import groovy.lang.Binding;
import java.util.HashSet;
import java.util.Set;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura at gmail.com>
 * created 22.08.2024
 */
public class ProtectedBinding extends Binding {

    private Set<String> protectedVariables = new HashSet<>();

    public void protectVariable(String name) {
        protectedVariables.add(name);
    }

    @Override
    public void setVariable(String name, Object value) {
        if (protectedVariables.contains(name) && getVariables().containsKey(name)) {
            throw new UnsupportedOperationException("Variable '" + name + "' cannot be overwritten.");
        }
        super.setVariable(name, value);
    }

    public void setVariable(String name, Object value, Boolean readOnly) {
        if (protectedVariables.contains(name) && getVariables().containsKey(name)) {
            throw new UnsupportedOperationException("Variable '" + name + "' cannot be overwritten.");
        }
        super.setVariable(name, value);
        
        if (readOnly){
            this.protectVariable(name);
        }
    }
}
