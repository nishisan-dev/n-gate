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

import dev.nishisan.operation.inventory.adapter.http.clients.HttpClientUtils;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.nishisan.operation.inventory.adapter.auth.OAuthClientManager;
import dev.nishisan.operation.inventory.adapter.configuration.BackendConfiguration;
import dev.nishisan.operation.inventory.adapter.configuration.EndPointConfiguration;
import dev.nishisan.operation.inventory.adapter.configuration.EndPointListenersConfiguration;
import dev.nishisan.operation.inventory.adapter.groovy.ProtectedBinding;
import dev.nishisan.operation.inventory.adapter.observabitliy.wrappers.SpanWrapper;
import dev.nishisan.operation.inventory.adapter.observabitliy.wrappers.TracerWrapper;
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
    private GroovyScriptEngine gse;
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
    private Cache<String, OkHttpClient> transientClients;

    // Connection pool compartilhado entre todos os OkHttpClients
    private final ConnectionPool sharedConnectionPool = new ConnectionPool(50, 5, TimeUnit.MINUTES);

    public HttpProxyManager(OAuthClientManager oauthManager, EndPointConfiguration configuration) {
        this.configuration = configuration;
        this.oauthManager = oauthManager;
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
                                // .header("User-Agent", "TELCOSTACK-" +
                                // backeEndConfiguration.getOauthClientConfig().getSsoName() + " v0.1")
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
                                // .header("Connection", "close")
                                // .headers(original.headers())
                                // .addHeader("User-Agent", "TELCOSTACK-" +
                                // backeEndConfiguration.getOauthClientConfig().getSsoName())
                                // .method(original.method(), original.body())
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
                            // .header("Connection", "close")
                            // .headers(original.headers())
                            // .addHeader("User-Agent", "TELCOSTACK-" +
                            // backeEndConfiguration.getOauthClientConfig().getSsoName())
                            // .method(original.method(), original.body())
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
        this.gse = new GroovyScriptEngine("rules");
        CompilerConfiguration config = this.gse.getConfig();
        config.setRecompileGroovySource(true);
        config.setMinimumRecompilationInterval(60); // 60 segundos
        logger.info("GroovyScriptEngine initialized with recompilation interval: 60s");
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

                Request req = this.httpRequestAdapter.getRequest(backendConfiguration, w, internalUid);

                // Injeta headers B3 de tracing na request para o backend
                SpanWrapper requestSpan = tracerWrapper.createSpan("upstream-request");
                requestSpan.getSpan().kind(brave.Span.Kind.CLIENT);
                requestSpan.tag("upstream-client-name", backendname);
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
                        Response res = this.getHttpClientByListenerName(backendname).newCall(req).execute();

                        // Tags de resposta upstream
                        requestSpan.tag("upstream.status_code", res.code());
                        if (res.header("Content-Type") != null) {
                            requestSpan.tag("upstream.content_type", res.header("Content-Type"));
                        }
                        if (res.header("Content-Length") != null) {
                            requestSpan.tag("upstream.content_length", res.header("Content-Length"));
                        }

                        /**
                         * Aqui de fato pega a response do OkHTTP e a converte
                         * para um response do Javalin, por horas está em short
                         * circuit, nesse ponto é onde devemos inserir a
                         * implementação para chamada da TMF-639 do Netcompass
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
            HttpRequest getRequest = HttpRequest.newBuilder()
                    .uri(URI.create(backendConfiguration.getEndPointUrl() + handler.contextPath()))
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
