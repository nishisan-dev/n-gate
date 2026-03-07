/*
 * Copyright (C) 2023 Lucas Nishimura <lucas.nishimura@gmail.com>
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
package dev.nishisan.operation.inventory.adapter.http;

import dev.nishisan.operation.inventory.adapter.http.synth.response.SyntHttpResponse;
import groovy.lang.Closure;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 09.01.2023
 */
public class HttpWorkLoad {

    private final CustomContextWrapper context;
    private final HttpAdapterServletRequest request;
    private final ConcurrentMap<String, Closure<?>> responseProcessors = new ConcurrentHashMap<>();
    private SyntHttpResponse upstreamResponse;
    private HttpAdapterServletResponse clientResponse;
    private String body = "";
    private Boolean returnPipe = false;

    private final ConcurrentMap<String, Object> objects = new ConcurrentHashMap<>();
    
    
    public void addObjects(Map<String,Object> objects){
        this.objects.putAll(objects);
    }

    public SyntHttpResponse createSynthResponse() {
        SyntHttpResponse res = new SyntHttpResponse();

        this.clientResponse = new HttpAdapterServletResponse(res);
        
        return res;
    }

    public SyntHttpResponse upstreamResponse() {

        return this.upstreamResponse;
    }

    public HttpAdapterServletResponse clientResponse() {
        return this.clientResponse;
    }

    public HttpAdapterServletResponse getClientResponse() {
        return clientResponse;
    }

    public void setClientResponse(HttpAdapterServletResponse clientResponse) {
        this.clientResponse = clientResponse;
    }

    public SyntHttpResponse getUpstreamResponse() {
        return upstreamResponse;
    }

    public void setUpstreamResponse(SyntHttpResponse response) {
        this.upstreamResponse = response;
    }

    public void addResponseProcessor(String name, Closure<?> p) {
        this.responseProcessors.put(name, p);
    }

    public ConcurrentMap<String, Closure<?>> getResponseProcessors() {
        return responseProcessors;
    }

    public HttpWorkLoad(CustomContextWrapper context) {
        this.context = context;
        this.request = new HttpAdapterServletRequest(context.req());
    }

    public void addObject(String name, Object obj) {
        this.objects.put(name, obj);
    }

    public ConcurrentMap<String, Object> objects() {
        return this.objects;
    }

    /**
     * @return the context
     */
    public CustomContextWrapper getContext() {
        return context;
    }

    public HttpAdapterServletRequest getRequest() {
        return request;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public Boolean getReturnPipe() {
        return returnPipe;
    }

    public void setReturnPipe(Boolean returnPipe) {
        this.returnPipe = returnPipe;
    }

}
