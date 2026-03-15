/*
 * Copyright (C) 2026 Lucas Nishimura <lucas.nishimura@gmail.com>
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
package dev.nishisan.ngate.http.clients;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Cliente fluent para chamadas HTTP a backends configurados no n-gate.
 * <p>
 * Encapsula a resolução de base URL e oferece métodos síncronos e assíncronos
 * para todos os verbos HTTP. Projetado para ergonomia máxima no Groovy:
 * <pre>
 * // Síncrono
 * def res = utils.backend("validator").post("/check", body)
 *
 * // Assíncrono (paralelo)
 * def f1 = utils.backend("svc-a").getAsync("/data")
 * def f2 = utils.backend("svc-b").getAsync("/data")
 * def resA = f1.join()
 * def resB = f2.join()
 * </pre>
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-15
 */
public class BackendClient {

    private final OkHttpClient client;
    private final String baseUrl;

    public BackendClient(OkHttpClient client, String baseUrl) {
        this.client = client;
        // Remove trailing slash para evitar URLs com //
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private String resolveUrl(String path) {
        if (path == null || path.isEmpty()) {
            return baseUrl;
        }
        return path.startsWith("/") ? baseUrl + path : baseUrl + "/" + path;
    }

    private void addHeaders(Request.Builder builder, Map<String, String> headers) {
        if (headers != null) {
            headers.forEach(builder::addHeader);
        }
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
        return future;
    }

    private Response executeSync(Request request) throws IOException {
        return client.newCall(request).execute();
    }

    // ─── GET ─────────────────────────────────────────────────────────────

    public Response get(String path) throws IOException {
        return get(path, null);
    }

    public Response get(String path, Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder().url(resolveUrl(path)).get();
        addHeaders(builder, headers);
        return executeSync(builder.build());
    }

    public CompletableFuture<Response> getAsync(String path) {
        return getAsync(path, null);
    }

    public CompletableFuture<Response> getAsync(String path, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder().url(resolveUrl(path)).get();
        addHeaders(builder, headers);
        return executeAsync(builder.build());
    }

    // ─── POST ────────────────────────────────────────────────────────────

    public Response post(String path, String body) throws IOException {
        return post(path, body, null);
    }

    public Response post(String path, String body, Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(resolveUrl(path))
                .post(RequestBody.create(body.getBytes()));
        addHeaders(builder, headers);
        return executeSync(builder.build());
    }

    public Response post(String path, byte[] body) throws IOException {
        return post(path, body, null);
    }

    public Response post(String path, byte[] body, Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(resolveUrl(path))
                .post(RequestBody.create(body));
        addHeaders(builder, headers);
        return executeSync(builder.build());
    }

    public CompletableFuture<Response> postAsync(String path, String body) {
        return postAsync(path, body, null);
    }

    public CompletableFuture<Response> postAsync(String path, String body, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder()
                .url(resolveUrl(path))
                .post(RequestBody.create(body.getBytes()));
        addHeaders(builder, headers);
        return executeAsync(builder.build());
    }

    public CompletableFuture<Response> postAsync(String path, byte[] body) {
        return postAsync(path, body, null);
    }

    public CompletableFuture<Response> postAsync(String path, byte[] body, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder()
                .url(resolveUrl(path))
                .post(RequestBody.create(body));
        addHeaders(builder, headers);
        return executeAsync(builder.build());
    }

    // ─── PUT ─────────────────────────────────────────────────────────────

    public Response put(String path, String body) throws IOException {
        return put(path, body, null);
    }

    public Response put(String path, String body, Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(resolveUrl(path))
                .put(RequestBody.create(body.getBytes()));
        addHeaders(builder, headers);
        return executeSync(builder.build());
    }

    public Response put(String path, byte[] body) throws IOException {
        return put(path, body, null);
    }

    public Response put(String path, byte[] body, Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(resolveUrl(path))
                .put(RequestBody.create(body));
        addHeaders(builder, headers);
        return executeSync(builder.build());
    }

    public CompletableFuture<Response> putAsync(String path, String body) {
        return putAsync(path, body, null);
    }

    public CompletableFuture<Response> putAsync(String path, String body, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder()
                .url(resolveUrl(path))
                .put(RequestBody.create(body.getBytes()));
        addHeaders(builder, headers);
        return executeAsync(builder.build());
    }

    public CompletableFuture<Response> putAsync(String path, byte[] body) {
        return putAsync(path, body, null);
    }

    public CompletableFuture<Response> putAsync(String path, byte[] body, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder()
                .url(resolveUrl(path))
                .put(RequestBody.create(body));
        addHeaders(builder, headers);
        return executeAsync(builder.build());
    }

    // ─── PATCH ───────────────────────────────────────────────────────────

    public Response patch(String path, String body) throws IOException {
        return patch(path, body, null);
    }

    public Response patch(String path, String body, Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(resolveUrl(path))
                .patch(RequestBody.create(body.getBytes()));
        addHeaders(builder, headers);
        return executeSync(builder.build());
    }

    public Response patch(String path, byte[] body) throws IOException {
        return patch(path, body, null);
    }

    public Response patch(String path, byte[] body, Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(resolveUrl(path))
                .patch(RequestBody.create(body));
        addHeaders(builder, headers);
        return executeSync(builder.build());
    }

    public CompletableFuture<Response> patchAsync(String path, String body) {
        return patchAsync(path, body, null);
    }

    public CompletableFuture<Response> patchAsync(String path, String body, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder()
                .url(resolveUrl(path))
                .patch(RequestBody.create(body.getBytes()));
        addHeaders(builder, headers);
        return executeAsync(builder.build());
    }

    public CompletableFuture<Response> patchAsync(String path, byte[] body) {
        return patchAsync(path, body, null);
    }

    public CompletableFuture<Response> patchAsync(String path, byte[] body, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder()
                .url(resolveUrl(path))
                .patch(RequestBody.create(body));
        addHeaders(builder, headers);
        return executeAsync(builder.build());
    }

    // ─── DELETE ──────────────────────────────────────────────────────────

    public Response delete(String path) throws IOException {
        return delete(path, null);
    }

    public Response delete(String path, Map<String, String> headers) throws IOException {
        Request.Builder builder = new Request.Builder().url(resolveUrl(path)).delete();
        addHeaders(builder, headers);
        return executeSync(builder.build());
    }

    public CompletableFuture<Response> deleteAsync(String path) {
        return deleteAsync(path, null);
    }

    public CompletableFuture<Response> deleteAsync(String path, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder().url(resolveUrl(path)).delete();
        addHeaders(builder, headers);
        return executeAsync(builder.build());
    }
}
