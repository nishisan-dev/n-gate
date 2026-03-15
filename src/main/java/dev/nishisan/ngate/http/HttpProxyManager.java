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
package dev.nishisan.ngate.http;

import dev.nishisan.ngate.http.clients.HttpClientUtils;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.nishisan.ngate.auth.OAuthClientManager;
import dev.nishisan.ngate.configuration.BackendConfiguration;
import dev.nishisan.ngate.configuration.EndPointConfiguration;
import dev.nishisan.ngate.configuration.EndPointListenersConfiguration;
import dev.nishisan.ngate.groovy.ProtectedBinding;
import dev.nishisan.ngate.http.circuit.BackendCircuitBreakerManager;
import dev.nishisan.ngate.http.ratelimit.RateLimitManager;
import dev.nishisan.ngate.http.ratelimit.RateLimitResult;
import dev.nishisan.ngate.observability.ProxyMetrics;
import dev.nishisan.ngate.observability.wrappers.SpanWrapper;
import dev.nishisan.ngate.observability.wrappers.TracerWrapper;
import dev.nishisan.ngate.upstream.PassiveHealthChecker;
import dev.nishisan.ngate.upstream.UpstreamMemberState;
import dev.nishisan.ngate.upstream.UpstreamPoolManager;
import groovy.json.JsonSlurper;
import groovy.lang.Binding;
import groovy.util.GroovyScriptEngine;
import groovy.util.ResourceException;
import groovy.util.ScriptException;
import groovy.xml.XmlSlurper;
import org.codehaus.groovy.control.CompilerConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Handles the 2 way binding between clients and backend
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 07.01.2023
 */
public class HttpProxyManager {

    // --- SSL estático compartilhado (evita recriação criptográfica por request)
    // ---
    private static final X509TrustManager TRUST_ALL_MANAGER = new X509TrustManager() {
        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType)
                throws CertificateException {
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType)
                throws CertificateException {
        }

        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            return new java.security.cert.X509Certificate[] {};
        }
    };

    private static final SSLContext SHARED_SSL_CONTEXT;
    private static final SSLSocketFactory SHARED_SSL_FACTORY;

    static {
        try {
            SHARED_SSL_CONTEXT = SSLContext.getInstance("SSL");
            SHARED_SSL_CONTEXT.init(null, new TrustManager[] { TRUST_ALL_MANAGER }, new java.security.SecureRandom());
            SHARED_SSL_FACTORY = SHARED_SSL_CONTEXT.getSocketFactory();
        } catch (NoSuchAlgorithmException | KeyManagementException ex) {
            throw new ExceptionInInitializerError("Failed to initialize shared SSLContext: " + ex.getMessage());
        }
    }

    private static final HostnameVerifier TRUST_ALL_HOSTNAMES = (hostname, session) -> true;

    private final EndPointConfiguration configuration;
    private volatile GroovyScriptEngine gse;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ThreadPoolExecutor threadPool;
    private final Logger logger = LogManager.getLogger(HttpProxyManager.class);
    private final Gson gson = new GsonBuilder().create();
    private final ProtectedBinding bindings = new ProtectedBinding();
    private final Map<String, Object> utils = new ConcurrentHashMap<>();
    private final Map<String, OkHttpClient> httpClients = new ConcurrentHashMap<>();
    private final OAuthClientManager oauthManager;
    private final HttpRequestAdapter httpRequestAdapter = new HttpRequestAdapter();
    private final HttpResponseAdapter httpResponseAdapter = new HttpResponseAdapter();
    private final HttpClientUtils httpClientUtils = new HttpClientUtils(this);
    private final SynthHttpResponseAdapter okHttpResponseAdapter = new SynthHttpResponseAdapter();
    private final ProxyMetrics proxyMetrics;
    private final BackendCircuitBreakerManager circuitBreakerManager;
    private final RateLimitManager rateLimitManager;
    private final UpstreamPoolManager upstreamPoolManager;
    private final PassiveHealthChecker passiveHealthChecker;
    private Cache<String, OkHttpClient> transientClients;

    // Connection pool compartilhado entre todos os OkHttpClients (configurável via adapter.yaml)
    private final ConnectionPool sharedConnectionPool;

    // Dispatcher compartilhado com Virtual Threads (Java 21)
    private final Dispatcher sharedDispatcher;

    public HttpProxyManager(OAuthClientManager oauthManager, EndPointConfiguration configuration, ProxyMetrics proxyMetrics, BackendCircuitBreakerManager circuitBreakerManager, RateLimitManager rateLimitManager, UpstreamPoolManager upstreamPoolManager, PassiveHealthChecker passiveHealthChecker) {
        this.configuration = configuration;
        this.oauthManager = oauthManager;
        this.proxyMetrics = proxyMetrics;
        this.circuitBreakerManager = circuitBreakerManager;
        this.rateLimitManager = rateLimitManager;
        this.upstreamPoolManager = upstreamPoolManager;
        this.passiveHealthChecker = passiveHealthChecker;
        this.sharedConnectionPool = new ConnectionPool(
                configuration.getConnectionPoolSize(),
                configuration.getConnectionPoolKeepAliveMinutes(),
                TimeUnit.MINUTES);
        this.sharedDispatcher = new Dispatcher(Executors.newVirtualThreadPerTaskExecutor());
        this.sharedDispatcher.setMaxRequests(configuration.getDispatcherMaxRequests());
        this.sharedDispatcher.setMaxRequestsPerHost(configuration.getDispatcherMaxRequestsPerHost());
        logger.info("OkHttp Dispatcher configured: maxRequests={}, maxRequestsPerHost={}",
                configuration.getDispatcherMaxRequests(), configuration.getDispatcherMaxRequestsPerHost());
        logger.info("OkHttp ConnectionPool configured: size={}, keepAlive={}min",
                configuration.getConnectionPoolSize(), configuration.getConnectionPoolKeepAliveMinutes());
    }

    public EndPointConfiguration getConfiguration() {
        return this.configuration;
    }

    /**
     * Cria ou Recupera o HTTP Client Associado ao Backend com as devidas
     * configurações de segurança
     *
     * @param name
     * @return
     * @throws NoSuchAlgorithmException
     * @throws KeyManagementException
     */
    public OkHttpClient getHttpClientByListenerName(String name)
            throws NoSuchAlgorithmException, KeyManagementException {
        // Usa SSLContext e ConnectionPool compartilhados (inicializados estaticamente)
        if (this.httpClients.containsKey(name)) {
            logger.debug("Reusing Client:[{}]", name);
            return this.httpClients.get(name);
        } else {
            //
            // Vamos criar um novo Client
            //

            OkHttpClient cachedClient = this.transientClients.getIfPresent(name);
            if (cachedClient != null) {
                return cachedClient;
            }

            logger.debug("Create New Client:[{}]", name);
            BackendConfiguration backeEndConfiguration = this.configuration.getBackends().get(name);
            if (backeEndConfiguration != null) {
                logger.debug("Backend Configuration Found");
            }

            //
            // Vamos criar um novo Client (usa SSL e pool estáticos)
            //
            if (backeEndConfiguration != null) {
                if (backeEndConfiguration.getOauthClientConfig() != null) {

                    //
                    // Se tivermos uma configuração de Oauth para o back
                    // Cria o interceptor para carimbar o header
                    //
                    OkHttpClient newClient = new OkHttpClient.Builder().addInterceptor((chain) -> {
                        Request original = chain.request();

                        /**
                         * Monta um novo request no interceptor para poder
                         * adicionar o cabeçaho de autenticação
                         */
                        Request newRequest = original.newBuilder()
                                .header("Authorization",
                                        "Bearer " + oauthManager
                                                .getAccessToken(
                                                        backeEndConfiguration.getOauthClientConfig().getSsoName())
                                                .getAccessToken())
                                // .header("Connection", "close")
                                .build();

                        logger.debug("Interceptor Called Authenticated");
                        logger.debug("Calling Backend:[{}]", name);
                        if (newRequest.body() != null) {
                            if (newRequest.body().contentLength() > 0) {
                                logger.debug("Body contentLength :[{}]", newRequest.body().contentLength());
                            }
                        }

                        logger.debug("Target: Authenticated URL:[{}] Method:[{}]", newRequest.url().uri(),
                                newRequest.method());
                        if (logger.isDebugEnabled()) {
                            for (String header : newRequest.headers().names()) {
                                String value = header.equalsIgnoreCase("Authorization")
                                        ? "Bearer ***"
                                        : newRequest.header(header);
                                logger.debug("Header OUT: [{}]:=[{}]", header, value);
                            }
                        }
                        return chain.proceed(newRequest);
                    }).sslSocketFactory(SHARED_SSL_FACTORY, TRUST_ALL_MANAGER)
                            .hostnameVerifier(TRUST_ALL_HOSTNAMES)
                            .dispatcher(sharedDispatcher)
                            .connectionPool(sharedConnectionPool)
                            .connectTimeout(configuration.getSocketTimeout(), TimeUnit.SECONDS)
                            .readTimeout(configuration.getSocketTimeout(), TimeUnit.SECONDS)
                            .callTimeout(configuration.getSocketTimeout(), TimeUnit.SECONDS)
                            .writeTimeout(configuration.getSocketTimeout(), TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .build();

                    this.httpClients.put(name, newClient);
                    logger.debug("New Authenticated HTTP Client Created:[{}]", name);
                    return newClient;
                } else {
                    //
                    // Chamada sem autenticação para o backend
                    //
                    OkHttpClient newClient = new OkHttpClient.Builder().addInterceptor((chain) -> {
                        Request original = chain.request();
                        Request request = original.newBuilder()
                                .build();
                        logger.debug("Interceptor Called");
                        logger.debug("Calling Backend:[{}]", name);
                        logger.debug("Target URL:[{}] Method:[{}]", request.url().uri(), request.method());
                        if (logger.isDebugEnabled()) {
                            for (String header : request.headers().names()) {
                                logger.debug("   [{}]:[{}]", header, request.header(header));
                            }
                        }
                        return chain.proceed(request);
                    }).sslSocketFactory(SHARED_SSL_FACTORY, TRUST_ALL_MANAGER)
                            .hostnameVerifier(TRUST_ALL_HOSTNAMES)
                            .dispatcher(sharedDispatcher)
                            .connectionPool(sharedConnectionPool)
                            .connectTimeout(configuration.getSocketTimeout(), TimeUnit.SECONDS)
                            .readTimeout(configuration.getSocketTimeout(), TimeUnit.SECONDS)
                            .callTimeout(configuration.getSocketTimeout(), TimeUnit.SECONDS)
                            .writeTimeout(configuration.getSocketTimeout(), TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .build();
                    this.httpClients.put(name, newClient);
                    return newClient;
                }
            } else {

                CookieJar cookies = new CookieJar() {
                    private final Map<String, List<Cookie>> cookieStore = new HashMap<>();

                    @Override
                    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                        cookieStore.put(url.host(), cookies);
                    }

                    @Override
                    public List<Cookie> loadForRequest(HttpUrl url) {
                        List<Cookie> cookies = cookieStore.get(url.host());
                        return cookies != null ? cookies : new ArrayList<>();
                    }
                };

                OkHttpClient newClient = new OkHttpClient.Builder().addInterceptor((chain) -> {
                    Request original = chain.request();
                    Request request = original.newBuilder()
                            .build();

                    logger.debug("Target URL:[{}] Method:[{}]", request.url().uri(), request.method());
                    if (logger.isDebugEnabled()) {
                        for (String header : request.headers().names()) {
                            logger.debug("   [{}]:[{}]", header, request.header(header));
                        }
                    }
                    return chain.proceed(request);
                }).sslSocketFactory(SHARED_SSL_FACTORY, TRUST_ALL_MANAGER)
                        .hostnameVerifier(TRUST_ALL_HOSTNAMES)
                        .dispatcher(sharedDispatcher)
                        .connectionPool(sharedConnectionPool)
                        .connectTimeout(configuration.getSocketTimeout(), TimeUnit.SECONDS)
                        .readTimeout(configuration.getSocketTimeout(), TimeUnit.SECONDS)
                        .callTimeout(configuration.getSocketTimeout(), TimeUnit.SECONDS)
                        .writeTimeout(configuration.getSocketTimeout(), TimeUnit.SECONDS)
                        .retryOnConnectionFailure(true)
                        .cookieJar(cookies)
                        .build();
                if (name.startsWith("AUTO") && name.endsWith("-TMP")) {
                } else {
                    this.transientClients.put(name, newClient);
                }
                return newClient;
            }

        }
    }

    public void init() {
        if (!this.running.get()) {

            this.transientClients = CacheBuilder.newBuilder().maximumSize(1000).expireAfterWrite(5, TimeUnit.MINUTES)
                    .build();

            JsonSlurper jsonParser = new JsonSlurper();

            running.set(true);
            try {
                utils.put("gson", gson);
                utils.put("json", jsonParser);

                try {
                    XmlSlurper xmlSluper = new XmlSlurper();
                    utils.put("xml", xmlSluper);
                } catch (Exception ex) {
                    logger.error("Failed to Start XML Sluper", ex);
                }

                utils.put("httpClient", this.httpClientUtils);

                this.initGse();
                this.threadPool = (ThreadPoolExecutor) Executors
                        .newFixedThreadPool(configuration.getRuleMappingThreads());

            } catch (IOException ex) {
                logger.error("Failed to start groovy context");
            }
        }
    }

    private void initGse() throws IOException {
        String rulesPath = configuration.getRulesBasePath();
        this.gse = new GroovyScriptEngine(rulesPath);
        CompilerConfiguration config = this.gse.getConfig();
        config.setRecompileGroovySource(true);
        config.setMinimumRecompilationInterval(60); // 60 segundos
        logger.info("GroovyScriptEngine initialized from [{}] with recompilation interval: 60s", rulesPath);
    }

    /**
     * Swap atômico do GroovyScriptEngine — usado pelo RulesBundleManager
     * para aplicar um novo bundle de rules sem parar o proxy.
     * <p>
     * Thread-safe: o campo {@code gse} é {@code volatile}, garantindo
     * que todas as threads veem a nova referência imediatamente.
     *
     * @param newGse novo GroovyScriptEngine já inicializado
     */
    public void swapGroovyEngine(GroovyScriptEngine newGse) {
        GroovyScriptEngine old = this.gse;
        this.gse = newGse;
        logger.info("GroovyScriptEngine swapped — old: [{}], new: [{}]",
                old != null ? old.getGroovyClassLoader().getURLs().length + " URLs" : "null",
                newGse.getGroovyClassLoader().getURLs().length + " URLs");
    }

    /**
     * Executa as regras do contexto no groovy
     *
     * @param listenerName
     * @param handler
     * @return
     */
    private HttpWorkLoad evalDynamicRules(String listenerName, CustomContextWrapper handler) {
        TracerWrapper tracerWrapper = handler.getTracerWrapper();
        SpanWrapper dynamicSpan = tracerWrapper.createSpan("dynamic-rules");
        Long start = System.currentTimeMillis();
        HttpWorkLoad workLoad = new HttpWorkLoad(handler);
        if (!handler.getObjects().isEmpty()) {
            logger.info("Adding Objects From Handler:[{}]", handler.getObjects().size());
            workLoad.addObjects(handler.getObjects());
        } else {
            logger.debug("Adding Objects From Handler:[{}]", handler.getObjects().size());
        }
        try {

            ProtectedBinding localBindings = new ProtectedBinding();
            localBindings.setVariable("context", workLoad.getContext(), true);
            localBindings.setVariable("contextName", handler.getContextName(), true);
            localBindings.setVariable("requestMethod", handler.getEndPointContext().getMethod(), true);
            localBindings.setVariable("workload", workLoad, true);
            localBindings.setVariable("utils", utils, true);
            localBindings.setVariable("include", "");
            localBindings.setVariable("listener", listenerName, true);
            localBindings.setVariable("upstreamRequest", workLoad.getRequest(), true);

            // workLoad.clientResponse()
            String runningScript = configuration.getRuleMapping();
            if (handler.getEndPointContext().getRuleMapping() != null) {
                if (!handler.getEndPointContext().getRuleMapping().trim().equals("")) {
                    runningScript = handler.getEndPointContext().getRuleMapping().trim();
                    logger.debug("Running Script Changed for Context:[{}] Script:[{}]", handler.getContextName(),
                            handler.getEndPointContext().getRuleMapping().trim());
                }
            }

            while (!runningScript.trim().equals("")) {

                SpanWrapper scriptSpan = tracerWrapper.createSpan("rules-execution");
                scriptSpan.tag("script", runningScript);
                logger.debug("Running Script: [{}] ", runningScript);
                try {
                    gse.run(runningScript, localBindings);
                } catch (ResourceException | ScriptException ex) {
                    //
                    // Erro de Groovy...
                    //
                    logger.error("Failed to Process Groovy Script:[{}]", runningScript, ex);

                } finally {
                    scriptSpan.finish();
                }
                runningScript = "";
                runningScript = (String) localBindings.getVariable("include");
            }
        } finally {
            logger.debug("Done Request");

        }

        Long end = System.currentTimeMillis();
        Long took = end - start;
        dynamicSpan.finish();
        logger.debug("Eval Dynamic Rules Took:[{}] ms", took);
        return workLoad;

    }

    /**
     * O Método que pega a request original do cliente, e encaminha para o
     * backend
     *
     * @param listenerName
     * @param handler
     */
    public void handleRequest(String listenerName, CustomContextWrapper handler) {
        TracerWrapper tracerWrapper = handler.getTracerWrapper();
        SpanWrapper requestRootSpan = tracerWrapper.createChildSpan("request-handler");
        requestRootSpan.tag("http.path", handler.path());
        try {

            handler.headerMap().forEach((k, v) -> {
                requestRootSpan.tag("header-" + k, v);
            });

            EndPointListenersConfiguration endPointConfiguration = this.configuration.getListeners().get(listenerName);

            String internalUid = UUID.randomUUID().toString();

            /**
             * Avalia a request, no groovy, é últil para fazermos modificações
             * em tempo de execução ou transformar os requests ou responses, não
             * foi muito testado
             */
            HttpWorkLoad w = this.evalDynamicRules(listenerName, handler);

            if (endPointConfiguration.getScriptOnly()) {

                try {
                    this.handleResponse(w);
                } catch (IOException serverEx) {
                    /**
                     * Acontece quando escrevendo a resposta para o cliente
                     */
                    handler.status(500);
                    handler.header("x-client-id", listenerName);
                    handler.result(serverEx.getMessage());
                }

            } else if (w.getClientResponse() != null) {
                //
                // Faz a resposta statica do script ?
                //
                if (w.getClientResponse().getSynthResponse() != null) {
                    w.setUpstreamResponse(w.getClientResponse().getSynthResponse());
                }

                // w.getUpstreamResponse()
                logger.debug("Synthetic Response Is Present Going to send it to Client");

                try {
                    this.handleResponse(w);
                } catch (IOException serverEx) {
                    /**
                     * Acontece quando escrevendo a resposta para o cliente
                     */
                    handler.status(500);
                    handler.header("x-client-id", listenerName);
                    handler.result(serverEx.getMessage());
                }
            } else {
                /**
                 * Request que veio do usuario é convertido para um request para
                 * o Backend, o Request de entrada é convertido em um request
                 * compátivel com o OKHttp
                 */
                String backendname = listenerName;
                /**
                 * Se o default backend for diferente de null vai respeitar a
                 * configuração passada exceto, se alguma opção diferente foi
                 * setada no contexto de regras
                 */

                if (w.getRequest().getBackend() != null
                        && !w.getRequest().getBackend().trim().equals("")) {
                    backendname = w.getRequest().getBackend().trim();
                    logger.debug("Backend Changed to:[{}]", backendname);
                } else if (endPointConfiguration.getVirtualHosts() != null
                        && !endPointConfiguration.getVirtualHosts().isEmpty()) {
                    // Virtual host: resolve pelo Host header
                    String hostHeader = handler.header("Host");
                    Optional<String> vhostBackend = VirtualHostResolver.resolve(
                            hostHeader, endPointConfiguration.getVirtualHosts());
                    if (vhostBackend.isPresent()) {
                        backendname = vhostBackend.get();
                        requestRootSpan.tag("vhost.match", backendname);
                        requestRootSpan.tag("http.host", hostHeader);
                        logger.debug("Virtual host matched: Host=[{}] → Backend=[{}]", hostHeader, backendname);
                    } else if (endPointConfiguration.getDefaultBackend() != null
                            && !endPointConfiguration.getDefaultBackend().trim().equals("")) {
                        backendname = endPointConfiguration.getDefaultBackend().trim();
                        requestRootSpan.tag("http.host", hostHeader);
                        logger.debug("Virtual host no match, using defaultBackend: [{}]", backendname);
                    }
                } else if (endPointConfiguration.getDefaultBackend() != null) {
                    if (!endPointConfiguration.getDefaultBackend().trim().equals("")) {
                        backendname = endPointConfiguration.getDefaultBackend().trim();
                    }
                }
                logger.debug("Backed Applied:[{}]", backendname);

                BackendConfiguration backendConfiguration = this.configuration.getBackends().get(backendname);
                if (backendConfiguration == null) {
                    logger.error("Backend configuration not found for backend name:[{}]", backendname);
                    handler.status(500);
                    handler.header("x-generic-id", listenerName);
                    handler.result("Backend not configured: " + backendname);
                    return;
                }

                // ─── Rate Limit: Backend level ───────────────────────────────
                if (rateLimitManager != null && rateLimitManager.isEnabled()
                        && backendConfiguration.getRateLimit() != null) {
                    RateLimitResult rlResult = rateLimitManager.acquirePermission(
                            "backend:" + backendname, backendConfiguration.getRateLimit());
                    requestRootSpan.tag("rate.limit.backend", rlResult.name());
                    if (proxyMetrics != null) {
                        proxyMetrics.recordRateLimitEvent("backend",
                                backendConfiguration.getRateLimit().getZone(), rlResult.name());
                    }
                    if (rlResult == RateLimitResult.REJECTED) {
                        logger.debug("Rate limit REJECTED for backend [{}]", backendname);
                        handler.status(429);
                        handler.header("x-rate-limit", "REJECTED");
                        handler.header("x-rate-limit-scope", "backend");
                        handler.header("x-rate-limit-zone", backendConfiguration.getRateLimit().getZone());
                        handler.header("x-upstream-id", backendname);
                        handler.header("Retry-After",
                                String.valueOf(rateLimitManager.getZoneTimeoutSeconds(
                                        backendConfiguration.getRateLimit().getZone())));
                        handler.result("Too Many Requests — rate limit for backend " + backendname);
                        return;
                    }
                    if (rlResult == RateLimitResult.DELAYED) {
                        handler.header("x-rate-limit", "DELAYED");
                        handler.header("x-rate-limit-scope", "backend");
                    }
                }

                // Upstream Pool Selection
                Optional<UpstreamMemberState> selectedMember = upstreamPoolManager.selectMember(backendname);
                if (selectedMember.isEmpty()) {
                    logger.warn("No healthy upstream members for backend [{}]", backendname);
                    handler.status(503);
                    handler.header("x-upstream-pool", backendname);
                    handler.result("Service Unavailable — no healthy upstream for " + backendname);
                    return;
                }

                String memberUrl = selectedMember.get().getUrl();
                Request req = this.httpRequestAdapter.getRequest(backendConfiguration, memberUrl, w, internalUid);

                // Injeta headers B3 de tracing na request para o backend
                SpanWrapper requestSpan = tracerWrapper.createSpan("upstream-request");
                requestSpan.getSpan().kind(brave.Span.Kind.CLIENT);
                requestSpan.tag("upstream-client-name", backendname);
                requestSpan.tag("upstream-member-url", memberUrl);
                requestSpan.tag("upstream-req-url", req.url().uri().toASCIIString());
                requestSpan.tag("upstream-req-method", handler.method().name());

                if (tracerWrapper.getTracing() != null) {
                    Request.Builder b3Builder = req.newBuilder();
                    tracerWrapper.getTracing().propagation()
                            .injector((Request.Builder carrier, String key, String value) -> carrier.header(key, value))
                            .inject(requestSpan.getSpan().context(), b3Builder);
                    req = b3Builder.build();
                    logger.debug("B3 tracing headers injected into upstream request");
                }

                logger.debug("UID[{}] Start Execute Request: [" + req.url().uri().toASCIIString() + "] Method:"
                        + handler.method().name(), internalUid);
                try {
                    /**
                     * Aqui vamos executar o request do usuário no backend, o
                     * método getHttpClientByListener name vai criar um cliente
                     * http para o backend em questão, levando em consideração
                     * as questões de autenticação
                     *
                     */
                    Long start = System.currentTimeMillis();
                    try {
                        // Circuit breaker: envolve a chamada upstream
                        Response res;
                        if (circuitBreakerManager != null) {
                            var cb = circuitBreakerManager.getOrCreate(backendname);
                            final Request finalReq = req;
                            final String finalBackendname = backendname;
                            try {
                                res = cb.executeCallable(() ->
                                    this.getHttpClientByListenerName(finalBackendname).newCall(finalReq).execute()
                                );
                            } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException cbEx) {
                                logger.warn("Circuit breaker OPEN for backend [{}] — rejecting request immediately", backendname);
                                handler.status(503);
                                handler.header("x-circuit-breaker", "OPEN");
                                handler.header("x-upstream-id", backendname);
                                handler.result("Service Unavailable — circuit breaker open for " + backendname);
                                if (proxyMetrics != null) {
                                    proxyMetrics.recordUpstreamError(backendname, handler.method().name());
                                }
                                requestSpan.tag("circuit.breaker", "OPEN");
                                requestSpan.finish();
                                return;
                            } catch (Exception cbWrappedEx) {
                                // Unwrap para não perder o IOException original
                                if (cbWrappedEx.getCause() instanceof IOException ioEx) {
                                    throw ioEx;
                                }
                                throw new IOException("Circuit breaker wrapped exception", cbWrappedEx);
                            }
                        } else {
                            res = this.getHttpClientByListenerName(backendname).newCall(req).execute();
                        }

                        // Tags de resposta upstream
                        requestSpan.tag("upstream.status_code", res.code());
                        if (res.header("Content-Type") != null) {
                            requestSpan.tag("upstream.content_type", res.header("Content-Type"));
                        }
                        if (res.header("Content-Length") != null) {
                            requestSpan.tag("upstream.content_length", res.header("Content-Length"));
                        }

                        // Métricas upstream
                        if (proxyMetrics != null) {
                            long upstreamDuration = System.currentTimeMillis() - start;
                            proxyMetrics.recordUpstreamRequest(backendname, handler.method().name(), res.code(), upstreamDuration);
                        }

                        // Passive health check: reporta status code observado
                        if (passiveHealthChecker != null) {
                            passiveHealthChecker.reportStatusCode(backendname, memberUrl, res.code());
                        }

                        /**
                         * Converte a response do OkHTTP para o response do
                         * Javalin. Ponto de extensão para transformações
                         * adicionais na response (e.g. TMF-639).
                         */
                        w.setUpstreamResponse(this.okHttpResponseAdapter.getResponse(res));
                        try {
                            this.handleResponse(w);
                        } catch (IOException serverEx) {
                            /**
                             * Acontece quando escrevendo a resposta para o
                             * cliente
                             */
                            logger.error("Failed To Write Request: Destination:[{}] Timed Out After:[{}] seconds",
                                    req.url().uri().toASCIIString(), configuration.getSocketTimeout(), serverEx);
                            handler.status(500);
                            handler.header("x-client-id", listenerName);
                            handler.result(serverEx.getMessage());
                        }
                    } catch (IOException clientEx) {
                        /**
                         * Acontece quando obtendo a resposta do UpStream Server
                         */
                        logger.error("Failed To Process Request: Destination:[{}] Timed Out After:[{}] seconds",
                                req.url().uri().toASCIIString(), configuration.getSocketTimeout(), clientEx);
                        handler.status(500);
                        handler.header("x-upstream-id", listenerName);
                        handler.result(clientEx.getMessage());
                        requestSpan.error(clientEx);
                        // Métricas upstream error
                        if (proxyMetrics != null) {
                            proxyMetrics.recordUpstreamError(backendname, handler.method().name());
                        }
                    } finally {
                        requestSpan.finish();
                    }
                    Long end = System.currentTimeMillis();
                    Long took = end - start;
                    logger.debug("UID[{}] Took [{}] ms to Execute Request: [" + req.url().uri().toASCIIString()
                            + "] Method:" + handler.method().name(), internalUid, took);

                    //
                    // Short Circuit
                    //
                } catch (KeyManagementException | NoSuchAlgorithmException ex) {
                    logger.error("Failed To Process Request Security", ex);
                    handler.result(ex.getMessage());
                } catch (Exception ex) {
                    handler.status(500);
                    handler.header("x-generic-id", listenerName);
                    handler.result("{}");
                    logger.error("Failed To Process Request Generic", ex);
                }
            }
        } finally {
            requestRootSpan.finish();
        }
    }

    /**
     * Converte a response para response do Javalin no context
     *
     * @param handler
     * @param res
     * @throws IOException
     */
    public void handleResponse(HttpWorkLoad w) throws IOException {
        //
        // Converte o response do back para o response do javalin
        //
        this.httpResponseAdapter.writeResponse(w);
    }

    /**
     * This is a GET Request
     *
     * @deprecated
     * @param handler
     */
    public void handleGet(String listenerName, CustomContextWrapper handler) {

        if (this.configuration.getRuleMapping() != null) {
            //
            // There is a dynamic rule
            //

            this.evalDynamicRules(listenerName, handler);
        } else {
            this.evalDynamicRules(listenerName, handler);

            //
            // No Rules using default backend reference
            //
            EndPointListenersConfiguration listenerConfig = this.configuration.getListeners().get(listenerName);
            BackendConfiguration backendConfiguration = this.configuration.getBackends()
                    .get(listenerConfig.getDefaultBackend());

            //
            // Aqui vai seguir fazendo o remaping
            //
            // @deprecated — dead code, mantido para referência
            String legacyUrl = backendConfiguration.getMembers().isEmpty()
                    ? "http://localhost" : backendConfiguration.getMembers().get(0).getUrl();
            HttpRequest getRequest = HttpRequest.newBuilder()
                    .uri(URI.create(legacyUrl + handler.contextPath()))
                    .GET()
                    .build();
        }
        //
        // this is the request receiveid from the user in the endpoint
        //

    }

    private class ContextRunnerThread implements Runnable {

        private Binding bindings = new Binding();

        private final HttpWorkLoad workLoad;

        public ContextRunnerThread(HttpWorkLoad workLoad) {
            this.workLoad = workLoad;
        }

        @Override
        public void run() {

            utils.put("gson", gson);

            try {
                // try {

                if (workLoad != null) {
                    try {
                        bindings.getVariables().clear();
                        bindings.setVariable("context", workLoad.getContext());
                        bindings.setVariable("utils", utils);
                        bindings.setVariable("include", "");
                        String runningScript = configuration.getRuleMapping();
                        while (!runningScript.trim().equals("")) {
                            logger.debug("Running Script: [{}] ", runningScript);
                            gse.run(runningScript, bindings);
                            runningScript = "";
                            runningScript = (String) bindings.getVariable("include");
                        }
                    } finally {
                        try {
                            workLoad.getContext().resultInputStream().close();
                        } catch (IOException ex) {
                            logger.warn("Close Handler Failed");
                        }

                        logger.debug("Done Request");
                    }

                }

            } catch (Exception ex) {
                logger.error("OMG ", ex);
            }

        }
    }

}
