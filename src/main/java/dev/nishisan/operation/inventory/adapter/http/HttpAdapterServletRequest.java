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

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura at gmail.com>
 * created 26.07.2024
 */
public class HttpAdapterServletRequest extends HttpServletRequestWrapper {

    private Map<String, String> headers;
    private String contentType;
    private String characterEncoding;
    private byte[] body;
    private List<Cookie> cookies;
    private final HttpServletRequest baseRequest;
    private String requestURI = null;
    private String queryString = null;
    private String backend = null;

    public HttpAdapterServletRequest(HttpServletRequest request) {
        super(request);
        this.baseRequest = request;
        this.headers = new HashMap<>();
        this.contentType = request.getContentType();
        this.characterEncoding = request.getCharacterEncoding();
        this.copyHeaders();
        this.copyBody();
        if (request.getCookies() != null) {
            this.cookies = List.of(request.getCookies());
        }

    }

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }

    @Override
    public String getCharacterEncoding() {
        return this.characterEncoding;
    }

    private void copyHeaders() {
        Enumeration<String> originalHeaders = super.getHeaderNames();
        while (originalHeaders.hasMoreElements()) {
            String headerName = originalHeaders.nextElement();
            headers.putIfAbsent(headerName, super.getHeader(headerName));
        }
    }

    private void copyBody() {
        try {
            InputStream inputStream = this.getInputStream();
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, bytesRead);
            }
            this.body = byteArrayOutputStream.toByteArray();
        } catch (IOException ex) {
            //
            // Omite
            //
        }

    }

    public void addHeader(String name, String value) {
        if (headers.containsKey(name)) {
            headers.remove(name);
        }
        headers.put(name, value);
    }

    @Override
    public String getHeader(String name) {
        String headerValue = headers.get(name);
        return headerValue;

    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return Collections.enumeration(headers.keySet());
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        return Collections.enumeration(Collections.singleton(headers.get(name)));
    }

    /**
     * Return the Body as Byte
     *
     * @return
     * @throws IOException
     */
    public byte[] getBodyAsBytes() throws IOException {
        if (this.body == null) {
            InputStream inputStream = this.getInputStream();
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, bytesRead);
            }
            return byteArrayOutputStream.toByteArray();
        } else {
            return this.body;
        }
    }

    public void addCookie(Cookie cookie) {
        this.cookies.add(cookie);
    }

    @Override
    public Cookie[] getCookies() {
        if (this.cookies != null) {
            Cookie[] c = new Cookie[this.cookies.size()];
            this.cookies.toArray(c);
            return c;
        }
        return null;
    }

    @Override
    public String getContentType() {
        return this.contentType != null ? this.contentType : super.getContentType();
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    @Override
    public String getRequestURI() {
        if (this.requestURI == null) {
            return super.getRequestURI();
        } else {
            return this.requestURI;
        }
    }

    public String getQueryString() {
        if (this.queryString == null) {
            return super.getQueryString();
        } else {
            return queryString;
        }
    }

    public void setQueryString(String queryString) {
        this.queryString = queryString;
    }

    public void setRequestURI(String requestURI) {
        this.requestURI = requestURI;
    }

}
