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
package dev.nishisan.operation.inventory.adapter.http.clients;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura at gmail.com>
 * created 23.08.2024
 */
public class AssyncHTTPClient implements Closeable {

    private final OkHttpClient client;
    private List<CompletableFuture> futures = new ArrayList<>();

    public AssyncHTTPClient(OkHttpClient client) {
        this.client = client;

    }

    private CompletableFuture<Response> executeAsync(Request request) {
        CompletableFuture<Response> future = new CompletableFuture<>();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                future.complete(response);
            }
        });
        futures.add(future);
        Response a ;
        
        return future;
    }

    public CompletableFuture<Response> get(String url, Map<String, String> headers) {
        Request.Builder requestBuilder = new Request.Builder().url(url).get();
        addHeaders(requestBuilder, headers);
        Request request = requestBuilder.build();
        return executeAsync(request);
    }

    public CompletableFuture<Response> post(String url, Map<String, String> headers, RequestBody body) {
        Request.Builder requestBuilder = new Request.Builder().url(url).post(body);
        addHeaders(requestBuilder, headers);
        Request request = requestBuilder.build();
        return executeAsync(request);
    }

    public CompletableFuture<Response> post(String url, Map<String, String> headers, String body) {
        return this.post(url, headers, RequestBody.create(body.getBytes()));
    }

    public CompletableFuture<Response> put(String url, Map<String, String> headers, String body) {
        return this.put(url, headers, RequestBody.create(body.getBytes()));
    }

    public CompletableFuture<Response> put(String url, Map<String, String> headers, RequestBody body) {
        Request.Builder requestBuilder = new Request.Builder().url(url).put(body);
        addHeaders(requestBuilder, headers);
        Request request = requestBuilder.build();
        return executeAsync(request);
    }

    public CompletableFuture<Response> delete(String url, Map<String, String> headers) {
        Request.Builder requestBuilder = new Request.Builder().url(url).delete();
        addHeaders(requestBuilder, headers);
        Request request = requestBuilder.build();
        return executeAsync(request);
    }

    public CompletableFuture<Response> patch(String url, Map<String, String> headers, String body) {
        return this.patch(url, headers, RequestBody.create(body.getBytes()));
    }

    public CompletableFuture<Response> patch(String url, Map<String, String> headers, RequestBody body) {
        Request.Builder requestBuilder = new Request.Builder().url(url).patch(body);
        addHeaders(requestBuilder, headers);
        Request request = requestBuilder.build();
        return executeAsync(request);
    }

    private void addHeaders(Request.Builder requestBuilder, Map<String, String> headers) {
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.addHeader(entry.getKey(), entry.getValue());
            }
        }
    }

    @Override
    public void close() throws IOException {
//        CompletableFuture<Void> allOf = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
//        allOf.join(); 

    }
}
