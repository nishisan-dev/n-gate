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
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.util.List;
import java.util.Map;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura at gmail.com>
 * created 08.08.2024
 */
public class HttpAdapterServletResponse extends HttpServletResponseWrapper {

    private SyntHttpResponse synthResponse;

    private Map<String, String> headers;
    private String contentType;
    private String characterEncoding;
    private byte[] body;
    private List<Cookie> cookies;
    private final HttpServletResponse baseResponse;
    private String requestURI = null;
    private String queryString = null;
    private String backend = null;

    public HttpAdapterServletResponse(HttpServletResponse response) {
        super(response);
        this.baseResponse = response;
        if (response instanceof SyntHttpResponse) {
            this.synthResponse = (SyntHttpResponse) response;
        }
    }

    public SyntHttpResponse getSynthResponse() {
        return synthResponse;
    }


}
