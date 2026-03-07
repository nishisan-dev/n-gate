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
package dev.nishisan.operation.inventory.adapter.http.clients;

import java.io.IOException;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura at gmail.com>
 * created 08.08.2024
 */
public class HttpClientWrapper {

    private final OkHttpClient client;

    public HttpClientWrapper(OkHttpClient client) {
        this.client = client;
    }

    public Response get(String url, Map<String, String> headers) throws IOException {
        Request.Builder requestBuilder = new Request.Builder().url(url);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.addHeader(entry.getKey(), entry.getValue());
            }
        }
        Request request = requestBuilder.build();
        return client.newCall(request).execute();
    }

    public Response postJson(String url, String jsonPayload, Map<String, String> headers) throws IOException {
        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(JSON, jsonPayload);
        Request.Builder requestBuilder = new Request.Builder().url(url).post(body);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.addHeader(entry.getKey(), entry.getValue());
            }
        }
        Request request = requestBuilder.build();
        return client.newCall(request).execute();
    }

    public Response post(String url, String jsonPayload, Map<String, String> headers) throws IOException {
        RequestBody body = RequestBody.create(jsonPayload.getBytes());
        Request.Builder requestBuilder = new Request.Builder().url(url).post(body);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.addHeader(entry.getKey(), entry.getValue());
            }
        }
        Request request = requestBuilder.build();
        return client.newCall(request).execute();
    }

    public Response putJson(String url, String jsonPayload, Map<String, String> headers) throws IOException {
        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(JSON, jsonPayload);
        Request.Builder requestBuilder = new Request.Builder().url(url).put(body);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.addHeader(entry.getKey(), entry.getValue());
            }
        }
        Request request = requestBuilder.build();
        return client.newCall(request).execute();
    }

    public Response put(String url, String jsonPayload, Map<String, String> headers) throws IOException {
        RequestBody body = RequestBody.create(jsonPayload.getBytes());
        Request.Builder requestBuilder = new Request.Builder().url(url).put(body);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.addHeader(entry.getKey(), entry.getValue());
            }
        }
        Request request = requestBuilder.build();
        return client.newCall(request).execute();
    }

    public Response patchJson(String url, String jsonPayload, Map<String, String> headers) throws IOException {
        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(JSON, jsonPayload);
        Request.Builder requestBuilder = new Request.Builder().url(url).patch(body);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.addHeader(entry.getKey(), entry.getValue());
            }
        }
        Request request = requestBuilder.build();
        return client.newCall(request).execute();
    }

    public Response patch(String url, String jsonPayload, Map<String, String> headers) throws IOException {

        RequestBody body = RequestBody.create(jsonPayload.getBytes());
        Request.Builder requestBuilder = new Request.Builder().url(url).patch(body);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.addHeader(entry.getKey(), entry.getValue());
            }
        }
        Request request = requestBuilder.build();
        return client.newCall(request).execute();
    }

    public Response delete(String url, Map<String, String> headers) throws IOException {
        Request.Builder requestBuilder = new Request.Builder().url(url).delete();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.addHeader(entry.getKey(), entry.getValue());
            }
        }
        Request request = requestBuilder.build();
        return client.newCall(request).execute();
    }

    
    
}
