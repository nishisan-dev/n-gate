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
package dev.nishisan.operation.inventory.adapter.auth.jwt;

import dev.nishisan.operation.inventory.adapter.auth.CustomUserPrincipal;
import dev.nishisan.operation.inventory.adapter.auth.IAuthUserPrincipal;
import dev.nishisan.operation.inventory.adapter.auth.ITokenDecoder;
import dev.nishisan.operation.inventory.adapter.exception.TokenDecodeException;
import dev.nishisan.operation.inventory.adapter.http.CustomContextWrapper;
import dev.nishisan.operation.inventory.adapter.observabitliy.wrappers.TracerWrapper;
import groovy.lang.Closure;
import java.net.MalformedURLException;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura at gmail.com>
 * created 03.09.2024
 */
public class CustomClosureDecoder implements ITokenDecoder {

    private Closure<?> initClosure;
    private Closure<?> decodeTokenClosure;
    private IAuthUserPrincipal principal;
    private final TracerWrapper tracer;
    private Integer recreateInterval = 0;
    private Date recreateDate = null;

    private final Map<String, String> options = new ConcurrentHashMap<>();
    private final Map<String, Object> utils = new ConcurrentHashMap<>();

    public CustomClosureDecoder(TracerWrapper tracer) {
        this.tracer = tracer;
    }

    @Override
    public IAuthUserPrincipal decodeToken(CustomContextWrapper context) throws TokenDecodeException {
        try {
            this.createUserPrincipal();
            context.setUserPrincipal(principal);
            this.decodeTokenClosure.setProperty("utils", utils);
            this.decodeTokenClosure.call(context);

            return principal;
        } catch (Exception ex) {
            context.setUserPrincipal(null);
            throw new TokenDecodeException(ex);
        }
    }

    @Override
    public void setOptions(Map<String, String> options) {
        this.options.putAll(options);
    }

    @Override
    public void init() throws MalformedURLException {
        if (this.initClosure != null) {
            this.initClosure.call(this);
        }
    }

    public IAuthUserPrincipal createUserPrincipal() {
        this.principal = new CustomUserPrincipal();
        return this.principal;
    }

    public Closure<?> getInitClosure() {
        return initClosure;
    }

    public void setInitClosure(Closure<?> initClosure) {
        this.initClosure = initClosure;
    }

    public Closure<?> getDecodeTokenClosure() {
        return decodeTokenClosure;
    }

    public void setDecodeTokenClosure(Closure<?> decodeTokenClosure) {
        this.decodeTokenClosure = decodeTokenClosure;
    }

    @Override
    public Integer getDecoderRecreateInterval() {
        return this.recreateInterval;
    }

    @Override
    public void setDecoderRecreateInterval(Integer l) {
        this.recreateInterval = l;
    }

    @Override
    public Date getRecreateDate() {
        return recreateDate;
    }

    @Override
    public void setRecreateDate(Date recreateDate) {
        this.recreateDate = recreateDate;
    }

}
