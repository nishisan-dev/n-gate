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

import brave.Span;
import brave.Tracer;
import brave.Tracing;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import dev.nishisan.ngate.auth.IAuthUserPrincipal;
import dev.nishisan.ngate.auth.ITokenDecoder;
import dev.nishisan.ngate.auth.OAuthClientManager;
import dev.nishisan.ngate.auth.jwt.CustomClosureDecoder;
import dev.nishisan.ngate.configuration.EndPointConfiguration;
import dev.nishisan.ngate.configuration.EndPointListenersConfiguration;
import dev.nishisan.ngate.configuration.SecureProviderConfig;
import dev.nishisan.ngate.exception.TokenDecodeException;
import dev.nishisan.ngate.groovy.ProtectedBinding;
import dev.nishisan.ngate.observabitliy.service.TracerService;
import dev.nishisan.ngate.observabitliy.wrappers.SpanWrapper;
import dev.nishisan.ngate.observabitliy.wrappers.TracerWrapper;
import groovy.util.GroovyScriptEngine;
import groovy.util.ResourceException;
import groovy.util.ScriptException;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.javalin.router.JavalinDefaultRoutingApi;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 06.01.2023
 */
public class EndpointWrapper {

    private Map<String, Javalin> listeners = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, ITokenDecoder> decoders = new ConcurrentHashMap<>();
    private final EndPointConfiguration configuration;
    private final HttpProxyManager proxyManager;
    private final OAuthClientManager oauthManager;
    private final Logger logger = LogManager.getLogger(EndpointWrapper.class);
    private final GroovyScriptEngine customGse;
    private final TracerService tracerService;
    private final SimpleDateFormat sdf = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");

    public EndpointWrapper(OAuthClientManager oauthManager, EndPointConfiguration configuration, GroovyScriptEngine gse, TracerService tracerService) {
        this.configuration = configuration;
        this.oauthManager = oauthManager;
        this.proxyManager = new HttpProxyManager(this.oauthManager, configuration);
        this.customGse = gse;
        this.tracerService = tracerService;
    }

    /**
     * Registra rotas/handlers no RoutesConfig durante Javalin.create().
     * Chamado DENTRO do lambda de Javalin.create() — NÃO recebe Javalin (ainda não existe).
     */
    public void registerRoutes(String name, JavalinDefaultRoutingApi routes, EndPointListenersConfiguration listenerConfig) {
        this.proxyManager.init();
        try {
            logger.debug("Getting Tracer Method");
            Tracing tracing = tracerService.getTracingInstance(name);
            Tracer tracer = tracing.tracer();

            // Extractor para propagar contexto B3 a partir dos headers HTTP de entrada
            TraceContext.Extractor<HttpServletRequest> extractor = tracing.propagation()
                    .extractor(HttpServletRequest::getHeader);

            logger.debug("Tracer OK with B3 propagation support");

            Map<String, HandlerType> methodMapping = Map.of(
                    "GET", HandlerType.GET,
                    "POST", HandlerType.POST,
                    "PUT", HandlerType.PUT,
                    "PATCH", HandlerType.PATCH,
                    "HEAD", HandlerType.HEAD,
                    "DELETE", HandlerType.DELETE,
                    "OPTIONS", HandlerType.OPTIONS,
                    "TRACE", HandlerType.TRACE
            );

            logger.debug("Setting Up Listeners");

            listenerConfig.getUrlContexts().forEach((contextName, urlContext) -> {
                try {
                    logger.debug("Setting UP Listener[{}] for context:{}", name, urlContext);
                    HandlerType handlerType = methodMapping.get(urlContext.getMethod().toUpperCase());
                    if (handlerType != null) {

                        routes.addHttpHandler(handlerType, urlContext.getContext(), ctx -> {
                            logger.debug("---------------------------------------------------------------------");
                            TracerWrapper traceWrapper = new TracerWrapper(tracer, tracing);
                            CustomContextWrapper customCtx = new CustomContextWrapper(ctx, contextName, urlContext, traceWrapper);

                            // Extrai contexto B3 da request de entrada (se presente)
                            SpanWrapper rootSpan = createServerSpan(traceWrapper, tracer, extractor, ctx.req(),
                                    urlContext.getMethod().toLowerCase() + "-handler-[" + contextName + "]");

                            // Tags HTTP semânticas — request inbound
                            rootSpan.tag("url-context", urlContext.getContext());
                            rootSpan.tag("http-method", urlContext.getMethod());
                            rootSpan.tag("http.method", ctx.method().name());
                            rootSpan.tag("http.url", ctx.fullUrl());
                            rootSpan.tag("http.path", ctx.path());
                            if (ctx.queryString() != null) {
                                rootSpan.tag("http.query", ctx.queryString());
                            }
                            rootSpan.tag("http.client_ip", ctx.ip());
                            if (ctx.userAgent() != null) {
                                rootSpan.tag("http.user_agent", ctx.userAgent());
                            }
                            if (ctx.contentType() != null) {
                                rootSpan.tag("http.request.content_type", ctx.contentType());
                            }
                            if (ctx.contentLength() > 0) {
                                rootSpan.tag("http.request.content_length", ctx.contentLength());
                            }

                            try {
                                if (listenerConfig.getSecured()) {
                                    if (urlContext.getSecured()) {
                                        SpanWrapper securedPan = traceWrapper.createSpan(urlContext.getMethod().toLowerCase() + "-handler-secured-[" + contextName + "]");
                                        try {
                                            IAuthUserPrincipal userPrincipal = getTokenDecoder(listenerConfig.getSecureProvider(), traceWrapper).decodeToken(customCtx);
                                            if (userPrincipal == null) {
                                                logger.error("User Principal is null");
                                            } else {
                                                customCtx.addObject("USER_PRINCIPAL", userPrincipal);
                                                logger.debug("Added User Principal Information:[{}]", customCtx.getObjects().size());
                                            }
                                            logger.debug("Handling {}: [{}] For:[{}]", handlerType, urlContext.getContext(), contextName);
                                            this.proxyManager.handleRequest(name, customCtx);
                                            return;
                                        } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | MalformedURLException ex) {
                                            //
                                            // Mesmo que seja um erro em caso de falha por ser uma api segura vamos dar um 401
                                            //
                                            logger.error("Failed to Create Token Decoder", ex);
                                            securedPan.error(ex);
                                            customCtx.header("x-trace-id", traceWrapper.getTraceId());
                                            customCtx.status(401);

                                            return;
                                        } catch (TokenDecodeException ex) {
                                            //
                                            // Mesmo que seja um erro em caso de falha por ser uma api segura vamos dar um 401
                                            //
                                            logger.error("Failed to Decoded Token", ex);
                                            securedPan.error(ex);
                                            customCtx.header("x-trace-id", traceWrapper.getTraceId());
                                            customCtx.status(401);
                                            return;
                                        } finally {
                                            securedPan.finish();
                                        }

                                    } else {
                                        logger.debug("Handling {}: [{}] For:[{}]", handlerType, urlContext.getContext(), contextName);
                                        this.proxyManager.handleRequest(name, customCtx);
                                    }
                                } else {
                                    logger.debug("Handling {}: [{}] For:[{}]", handlerType, urlContext.getContext(), contextName);
                                    this.proxyManager.handleRequest(name, customCtx);
                                }
                            } finally {
                                rootSpan.tag("http.status_code", ctx.statusCode());
                                ctx.header("x-trace-id", traceWrapper.getTraceId());
                                rootSpan.finish();
                            }
                        });
                    } else if ("ANY".equalsIgnoreCase(urlContext.getMethod())) {

                        methodMapping.values().forEach(type
                                -> routes.addHttpHandler(type, urlContext.getContext(), ctx -> {
                                    logger.debug("---------------------------------------------------------------------");
                                    TracerWrapper traceWrapper = new TracerWrapper(tracer, tracing);
                                    CustomContextWrapper customCtx = new CustomContextWrapper(ctx, contextName, urlContext, traceWrapper);

                                    // Extrai contexto B3 da request de entrada (se presente)
                                    SpanWrapper rootSpan = createServerSpan(traceWrapper, tracer, extractor, ctx.req(),
                                            "any-handler-[" + contextName + "]");

                                    // Tags HTTP semânticas — request inbound (ANY handler)
                                    rootSpan.tag("url-context", urlContext.getContext());
                                    rootSpan.tag("http-method", urlContext.getMethod());
                                    rootSpan.tag("http.method", ctx.method().name());
                                    rootSpan.tag("http.url", ctx.fullUrl());
                                    rootSpan.tag("http.path", ctx.path());
                                    if (ctx.queryString() != null) {
                                        rootSpan.tag("http.query", ctx.queryString());
                                    }
                                    rootSpan.tag("http.client_ip", ctx.ip());
                                    if (ctx.userAgent() != null) {
                                        rootSpan.tag("http.user_agent", ctx.userAgent());
                                    }
                                    if (ctx.contentType() != null) {
                                        rootSpan.tag("http.request.content_type", ctx.contentType());
                                    }
                                    if (ctx.contentLength() > 0) {
                                        rootSpan.tag("http.request.content_length", ctx.contentLength());
                                    }
                                    try {
                                        if (listenerConfig.getSecured()) {
                                            if (urlContext.getSecured()) {
                                                SpanWrapper securedPan = traceWrapper.createSpan("any-handler-secured-[" + contextName + "]");
                                                try {
                                                    IAuthUserPrincipal userPrincipal = getTokenDecoder(listenerConfig.getSecureProvider(), traceWrapper).decodeToken(customCtx);
                                                    customCtx.addObject("USER_PRINCIPAL", userPrincipal);
                                                    if (userPrincipal == null) {
                                                        logger.error("User Principal is null");
                                                    } else {
                                                        customCtx.addObject("USER_PRINCIPAL", userPrincipal);
                                                        logger.debug("Added User Principal Information:[{}]", customCtx.getObjects().size());
                                                    }
                                                    logger.debug("Handling {}: [{}] For:[{}]", type, urlContext.getContext(), contextName);
                                                    this.proxyManager.handleRequest(name, customCtx);
                                                    return;
                                                } catch (ClassNotFoundException | IllegalAccessException | InstantiationException | MalformedURLException ex) {
                                                    logger.error("Failed to Create Token Decoder", ex);
                                                    //
                                                    // Mesmo que seja um erro em caso de falha por ser uma api segura vamos dar um 401
                                                    //
                                                    securedPan.error(ex);
                                                    customCtx.header("x-trace-id", traceWrapper.getTraceId());
                                                    customCtx.status(401);
                                                    return;
                                                } catch (TokenDecodeException ex) {
                                                    //
                                                    // Mesmo que seja um erro em caso de falha por ser uma api segura vamos dar um 401
                                                    //
                                                    logger.error("Failed to Decoded Token", ex);
                                                    securedPan.error(ex);
                                                    customCtx.header("x-trace-id", traceWrapper.getTraceId());
                                                    customCtx.status(401);
                                                    return;
                                                } finally {
                                                    securedPan.finish();
                                                }

                                            } else {
                                                logger.debug("Handling {}: [{}] For:[{}]", type, urlContext.getContext(), contextName);
                                                this.proxyManager.handleRequest(name, customCtx);
                                            }
                                        } else {
                                            logger.debug("Handling {}: [{}] For:[{}]", type, urlContext.getContext(), contextName);
                                            this.proxyManager.handleRequest(name, customCtx);
                                        }
                                    } finally {
                                        rootSpan.tag("http.status_code", ctx.statusCode());
                                        ctx.header("x-trace-id", traceWrapper.getTraceId());
                                        rootSpan.finish();
                                    }
                                })
                        );
                    }
                } catch (Exception ex) {
                    logger.error("Generic EX", ex);
                }
            });

        } catch (Exception ex) {
            logger.error("Generic EX At RegisterRoutes Method", ex);
        }
    }

    /**
     * Registra o listener Javalin e inicia-o.
     * Chamado APÓS Javalin.create() retornar.
     */
    public void startListener(String name, Javalin listener, EndPointListenersConfiguration listenerConfig) {
        this.listeners.put(name, listener);
        logger.debug("Trying to start Listener");
        try {
            listener.start(listenerConfig.getListenAddress(), listenerConfig.getListenPort());
            logger.debug("Listener:[" + name + "] Started at: " + listenerConfig.getListenAddress() + "/" + listenerConfig.getListenPort());
        } catch (Exception ex) {
            logger.error("Failed Listener:[" + name + "] Started at: " + listenerConfig.getListenAddress() + "/" + listenerConfig.getListenPort(), ex);
        }
    }

    public Map<String, Javalin> getListeners() {
        return listeners;
    }

    /**
     * Para gracefully todos os listeners Javalin deste endpoint.
     * Javalin 7 já implementa drain nativo em {@code stop()}.
     */
    public void stopAllListeners() {
        listeners.forEach((name, javalin) -> {
            try {
                logger.info("Stopping listener: [{}]", name);
                javalin.stop();
                logger.info("Listener [{}] stopped successfully", name);
            } catch (Exception e) {
                logger.warn("Failed to stop listener [{}]: {}", name, e.getMessage(), e);
            }
        });
    }

    public void setListeners(Map<String, Javalin> listeners) {
        this.listeners = listeners;
    }

    public ITokenDecoder getTokenDecoder(SecureProviderConfig configuration, TracerWrapper tracer) throws InvocationTargetException, NoSuchMethodException, ClassNotFoundException, InstantiationException, IllegalAccessException, MalformedURLException, ResourceException, ScriptException {
        SpanWrapper getTokenDecoderSpan = tracer.createSpan("token-decoder-[" + configuration.getName() + "]");
        try {
            if (decoders.containsKey(configuration.getName())) {
                ITokenDecoder decoder = this.decoders.get(configuration.getName());

                Date currentTime = new Date();

                logger.debug("Now is:[{}]", sdf.format(currentTime));
                logger.debug("Expires at:[{}]", sdf.format(decoder.getRecreateDate()));

                if (currentTime.after(decoder.getRecreateDate())) {
                    //
                    // Expirou!
                    //                                        
                    logger.debug("Decoder Has Expired, recreating");
                    decoders.remove(configuration.getName());
                } else {
                    logger.debug("Using Previous Created Decoder. Expires At:" + decoder.getRecreateDate() + " Now is:" + currentTime);
                    getTokenDecoderSpan.tag("obj-reused", "true");
                    return decoder;
                }

            } else {
                getTokenDecoderSpan.tag("obj-reused", "false");
            }
            getTokenDecoderSpan.tag("obj-class", configuration.getProviderClass());

            if (configuration.getProviderClass().equalsIgnoreCase("dev.nishisan.ngate.auth.jwt.JWTTokenDecoder") || configuration.getProviderClass().equalsIgnoreCase("JWTTokenDecoder")) {
                /**
                 * Cria a partir do classpath, usando o Decoder Padrão
                 */

                Class<?> clazz;
                try {
                    clazz = Class.forName(configuration.getProviderClass());
                    Constructor<?> cons = clazz.getConstructor(TracerWrapper.class);
                    Object object = cons.newInstance(tracer);
                    ITokenDecoder tokenProvider = (ITokenDecoder) object;
                    tokenProvider.setOptions(configuration.getOptions());
                    tokenProvider.init();
                    decoders.put(configuration.getName(), tokenProvider);
                    return tokenProvider;
                } catch (InvocationTargetException | ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException | MalformedURLException ex) {
                    logger.error("Error Creating Instance of TokenDecoder" + configuration.getProviderClass() + " With Name: [" + configuration.getName() + "]", ex);
                    getTokenDecoderSpan.error(ex);
                    throw ex;
                }
            } else {
                /**
                 * Cria a partir do Groovy Utilizando o Contexto do GSE:
                 */
                ProtectedBinding bindings = new ProtectedBinding();
                CustomClosureDecoder decoder = new CustomClosureDecoder(tracer);
                bindings.setVariable("decoder", decoder, true);
                this.customGse.run(configuration.getProviderClass(), bindings);
                logger.debug("Custom Token Decoder Created");
                if (decoder.getInitClosure() != null) {
                    logger.debug(" Custom Init Closure Is present");
                    decoder.init();
                } else {
                    logger.debug(" Custom Init Closure Is not present");
                }

                if (decoder.getDecodeTokenClosure() != null) {
                    logger.debug(" Custom Token Closure Is present");
                } else {
                    logger.debug(" Custom Token Closure Is not present");
                }
                decoders.put(configuration.getName(), decoder);

                if (decoder.getDecoderRecreateInterval() > 0L) {
                    Date toRecreate = new Date();
                    Calendar cal = Calendar.getInstance();
                    cal.setTime(toRecreate);
                    cal.add(Calendar.SECOND, decoder.getDecoderRecreateInterval());
                    decoder.setRecreateDate(cal.getTime());
                }

                return decoder;
            }
        } finally {
            getTokenDecoderSpan.finish();
        }
    }

    /**
     * Cria um span SERVER, extraindo contexto B3 dos headers HTTP de entrada
     * quando presente (permitindo distributed tracing entre serviços).
     */
    private SpanWrapper createServerSpan(TracerWrapper traceWrapper, Tracer tracer,
            TraceContext.Extractor<HttpServletRequest> extractor, HttpServletRequest req, String spanName) {
        TraceContextOrSamplingFlags extracted = extractor.extract(req);
        Span braveSpan = tracer.nextSpan(extracted).name(spanName).kind(Span.Kind.SERVER);
        braveSpan.start();
        SpanWrapper wrapper = new SpanWrapper(spanName, braveSpan);
        traceWrapper.addSpan(wrapper);
        traceWrapper.setCurrentSpan(wrapper);
        return wrapper;
    }
}
