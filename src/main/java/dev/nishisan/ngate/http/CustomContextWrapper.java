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
package dev.nishisan.ngate.http;

import static brave.Tracing.currentTracer;
import dev.nishisan.ngate.auth.IAuthUserPrincipal;
import dev.nishisan.ngate.configuration.EndPointURLContext;
import dev.nishisan.ngate.observability.wrappers.TracerWrapper;
import io.javalin.config.Key;
import io.javalin.config.MultipartConfig;
import io.javalin.http.ContentType;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.HandlerType;
import io.javalin.http.HttpStatus;
import io.javalin.http.UploadedFile;
import io.javalin.http.util.AsyncTaskConfig;
import io.javalin.http.util.CookieStore;
import io.javalin.json.JsonMapper;
import io.javalin.plugin.ContextPlugin;
import io.javalin.router.Endpoint;
import io.javalin.router.Endpoints;
import io.javalin.security.BasicAuthCredentials;
import io.javalin.security.RouteRole;
import io.javalin.util.function.ThrowingRunnable;
import io.javalin.validation.BodyValidator;
import io.javalin.validation.Validator;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import kotlin.jvm.functions.Function1;
import kotlin.reflect.KClass;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura at gmail.com>
 * created 22.08.2024
 */
public class CustomContextWrapper implements Context {

    private final Context context;
    private Boolean hasResponse = false;
    private final String contextName;
    private final EndPointURLContext endPointContext;
    private Map<String, Object> objects = new ConcurrentHashMap<>();
    private IAuthUserPrincipal userPrincipal;
    private final TracerWrapper tracerWrapper;

    
    public void raiseException() throws Exception {
        throw new Exception();
    }

    public void raiseException(String msg) throws Exception {
        throw new Exception(msg);
    }

    public TracerWrapper getTracerWrapper() {
        return tracerWrapper;
    }

    
    public CustomContextWrapper(Context context, String contextName, EndPointURLContext encPointContext, TracerWrapper tracerWrapper) {
        this.context = context;
        this.contextName = contextName;
        this.endPointContext = encPointContext;
        this.tracerWrapper = tracerWrapper;
    }

    public Context getContext() {
        return context;
    }

    public String getContextName() {
        return contextName;
    }

    public EndPointURLContext getEndPointContext() {
        return endPointContext;
    }

    @Override
    public HttpServletRequest req() {
        return context.req();
    }

    private HttpAdapterServletResponse cachedResponse;

    @Override
    public HttpAdapterServletResponse res() {
        if (this.cachedResponse == null) {
            this.cachedResponse = new HttpAdapterServletResponse(context.res());
        }
        return this.cachedResponse;
    }

    @Override
    public Endpoints endpoints() {
        return context.endpoints();
    }

    @Override
    public Endpoint endpoint() {
        return context.endpoint();
    }

    @Override
    public MultipartConfig multipartConfig() {
        return context.multipartConfig();
    }

    @Override
    public boolean strictContentTypes() {
        return context.strictContentTypes();
    }

    @Override
    public <T> T appData(Key<T> key) {
        return context.appData(key);
    }

    @Override
    public JsonMapper jsonMapper() {
        return context.jsonMapper();
    }

    @Override
    public <T> T with(Class<? extends ContextPlugin<?, T>> type) {
        return context.with(type);
    }

    @Override
    public <T> T with(KClass<? extends ContextPlugin<?, T>> clazz) {
        return context.with(clazz);
    }

    @Override
    public int contentLength() {
        return context.contentLength();
    }

    @Override
    public String contentType() {
        return context.contentType();
    }

    @Override
    public HandlerType method() {
        return context.method();
    }

    @Override
    public String path() {
        return context.path();
    }

    @Override
    public int port() {
        return context.port();
    }

    @Override
    public String protocol() {
        return context.protocol();
    }

    @Override
    public String contextPath() {
        return context.contextPath();
    }

    @Override
    public String userAgent() {
        return context.userAgent();
    }

    @Override
    public String characterEncoding() {
        return context.characterEncoding();
    }

    @Override
    public String url() {
        return context.url();
    }

    @Override
    public String fullUrl() {
        return context.fullUrl();
    }

    @Override
    public String scheme() {
        return context.scheme();
    }

    @Override
    public String host() {
        return context.host();
    }

    @Override
    public String ip() {
        return context.ip();
    }

    @Override
    public String body() {
        return context.body();
    }

    @Override
    public byte[] bodyAsBytes() {
        return context.bodyAsBytes();
    }

    @Override
    public <T> T bodyAsClass(Type type) {
        return context.bodyAsClass(type);
    }

    @Override
    public <T> T bodyAsClass(Class<T> clazz) {
        return context.bodyAsClass(clazz);
    }

    @Override
    public <T> T bodyStreamAsClass(Type type) {
        return context.bodyStreamAsClass(type);
    }

    @Override
    public InputStream bodyInputStream() {
        return context.bodyInputStream();
    }

    @Override
    public <T> BodyValidator<T> bodyValidator(Class<T> clazz) {
        return context.bodyValidator(clazz);
    }

    @Override
    public String formParam(String key) {
        return context.formParam(key);
    }

    @Override
    public <T> Validator<T> formParamAsClass(String key, Class<T> clazz) {
        return context.formParamAsClass(key, clazz);
    }

    @Override
    public List<String> formParams(String key) {
        return context.formParams(key);
    }

    @Override
    public Map<String, List<String>> formParamMap() {
        return context.formParamMap();
    }



    @Override
    public String pathParam(String string) {
        return context.pathParam(string);
    }

    @Override
    public <T> Validator<T> pathParamAsClass(String key, Class<T> clazz) {
        return context.pathParamAsClass(key, clazz);
    }

    @Override
    public Map<String, String> pathParamMap() {
        return context.pathParamMap();
    }

    @Override
    public String queryParam(String key) {
        return context.queryParam(key);
    }

    @Override
    public <T> Validator<T> queryParamAsClass(String key, Class<T> clazz) {
        return context.queryParamAsClass(key, clazz);
    }

    @Override
    public List<String> queryParams(String key) {
        return context.queryParams(key);
    }

    @Override
    public Map<String, List<String>> queryParamMap() {
        return context.queryParamMap();
    }

    @Override
    public String queryString() {
        return context.queryString();
    }

    @Override
    public void sessionAttribute(String key, Object value) {
        context.sessionAttribute(key, value);
    }

    @Override
    public <T> T sessionAttribute(String key) {
        return context.sessionAttribute(key);
    }

    @Override
    public <T> T consumeSessionAttribute(String key) {
        return context.consumeSessionAttribute(key);
    }

    @Override
    public void cachedSessionAttribute(String key, Object value) {
        context.cachedSessionAttribute(key, value);
    }

    @Override
    public <T> T cachedSessionAttribute(String key) {
        return context.cachedSessionAttribute(key);
    }

    @Override
    public <T> T cachedSessionAttributeOrCompute(String key, Function1<? super Context, ? extends T> callback) {
        return context.cachedSessionAttributeOrCompute(key, callback);
    }

    @Override
    public Map<String, Object> sessionAttributeMap() {
        return context.sessionAttributeMap();
    }

    @Override
    public void attribute(String key, Object value) {
        context.attribute(key, value);
    }

    @Override
    public <T> T attribute(String key) {
        return context.attribute(key);
    }

    @Override
    public <T> T attributeOrCompute(String key, Function1<? super Context, ? extends T> callback) {
        return context.attributeOrCompute(key, callback);
    }

    @Override
    public Map<String, Object> attributeMap() {
        return context.attributeMap();
    }

    @Override
    public CookieStore cookieStore() {
        return context.cookieStore();
    }

    @Override
    public String cookie(String name) {
        return context.cookie(name);
    }

    @Override
    public Map<String, String> cookieMap() {
        return context.cookieMap();
    }

    @Override
    public String header(String header) {
        return context.header(header);
    }

    @Override
    public <T> Validator<T> headerAsClass(String header, Class<T> clazz) {
        return context.headerAsClass(header, clazz);
    }

    @Override
    public Map<String, String> headerMap() {
        return context.headerMap();
    }

    @Override
    public BasicAuthCredentials basicAuthCredentials() {
        return context.basicAuthCredentials();
    }

    @Override
    public boolean isMultipart() {
        return context.isMultipart();
    }

    @Override
    public boolean isMultipartFormData() {
        return context.isMultipartFormData();
    }

    @Override
    public boolean isFormUrlencoded() {
        return context.isFormUrlencoded();
    }

    @Override
    public boolean isJson() {
        return context.isJson();
    }

    @Override
    public UploadedFile uploadedFile(String fileName) {
        return context.uploadedFile(fileName);
    }

    @Override
    public List<UploadedFile> uploadedFiles(String fileName) {
        return context.uploadedFiles(fileName);
    }

    @Override
    public List<UploadedFile> uploadedFiles() {
        return context.uploadedFiles();
    }

    @Override
    public Map<String, List<UploadedFile>> uploadedFileMap() {
        return context.uploadedFileMap();
    }

    @Override
    public Charset responseCharset() {
        return context.responseCharset();
    }

    @Override
    public ServletOutputStream outputStream() {
        return context.outputStream();
    }

    @Override
    public Context minSizeForCompression(int i) {
        return context.minSizeForCompression(i);
    }

    @Override
    public void writeSeekableStream(InputStream inputStream, String contentType, long totalBytes) {
        context.writeSeekableStream(inputStream, contentType, totalBytes);
    }

    @Override
    public void writeSeekableStream(InputStream inputStream, String contentType) {
        context.writeSeekableStream(inputStream, contentType);
    }

    @Override
    public Context result(String resultString) {
        this.hasResponse = true;
        return context.result(resultString);
    }

    @Override
    public Context result(byte[] resultBytes) {
        this.hasResponse = true;
        return context.result(resultBytes);
    }

    @Override
    public Context result(InputStream in) {
        return context.result(in);
    }

    @Override
    public String result() {
        return context.result();
    }

    @Override
    public InputStream resultInputStream() {
        return context.resultInputStream();
    }

    @Override
    public void async(Consumer<AsyncTaskConfig> config, ThrowingRunnable<Exception> task) {
        context.async(config, task);
    }

    @Override
    public void async(ThrowingRunnable<Exception> task) {
        context.async(task);
    }

    @Override
    public void future(Supplier<? extends CompletableFuture<?>> splr) {
        context.future(splr);
    }

    @Override
    public Context contentType(String contentType) {
        return context.contentType(contentType);
    }

    @Override
    public Context contentType(ContentType contentType) {
        return context.contentType(contentType);
    }

    @Override
    public Context header(String name, String value) {
        return context.header(name, value);
    }

    @Override
    public Context removeHeader(String name) {
        return context.removeHeader(name);
    }

    @Override
    public void redirect(String string, HttpStatus hs) {
        context.redirect(string, hs);
    }

    @Override
    public void redirect(String location) {
        context.redirect(location);
    }

    @Override
    public Context status(HttpStatus status) {
        return context.status(status);
    }

    @Override
    public Context status(int status) {
        return context.status(status);
    }

    @Override
    public HttpStatus status() {
        return context.status();
    }

    @Override
    public int statusCode() {
        return context.statusCode();
    }

    @Override
    public Context cookie(String name, String value) {
        return context.cookie(name, value);
    }

    @Override
    public Context cookie(String name, String value, int maxAge) {
        return context.cookie(name, value, maxAge);
    }

    @Override
    public Context cookie(Cookie cookie) {
        return context.cookie(cookie);
    }

    @Override
    public Context removeCookie(String name, String path) {
        return context.removeCookie(name, path);
    }

    @Override
    public Context removeCookie(String name) {
        return context.removeCookie(name);
    }

    @Override
    public Context json(Object obj, Type type) {
        this.hasResponse = true;
        return context.json(obj, type);
    }

    @Override
    public Context json(Object obj) {
        this.hasResponse = true;
        return context.json(obj);
    }

    @Override
    public Context jsonStream(Object obj, Type type) {
        return context.jsonStream(obj, type);
    }

    @Override
    public Context jsonStream(Object obj) {
        return context.jsonStream(obj);
    }

    @Override
    public void writeJsonStream(Stream<?> stream) {
        context.writeJsonStream(stream);
    }

    @Override
    public Context html(String html) {
        return context.html(html);
    }

    @Override
    public Context render(String filePath, Map<String, ? extends Object> model) {
        return context.render(filePath, model);
    }

    @Override
    public Context render(String filePath) {
        return context.render(filePath);
    }

    @Override
    public Context skipRemainingHandlers() {
        return context.skipRemainingHandlers();
    }

    @Override
    public Set<RouteRole> routeRoles() {
        return context.routeRoles();
    }

    public Boolean getHasResponse() {
        return hasResponse;
    }

    public Map<String, Object> getObjects() {
        return objects;
    }

    public void setObjects(Map<String, Object> objects) {
        this.objects = objects;
    }

    public void addObject(String key, Object obj) {
        this.objects.put(key, obj);
    }

    public void clearObjects() {
        this.objects.clear();
    }

    public IAuthUserPrincipal getUserPrincipal() {
        return userPrincipal;
    }

    public void setUserPrincipal(IAuthUserPrincipal userPrincipal) {
        this.userPrincipal = userPrincipal;
    }

}
