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

import brave.Tracer;
import dev.nishisan.operation.inventory.adapter.auth.IAuthUserPrincipal;
import dev.nishisan.operation.inventory.adapter.auth.ITokenDecoder;
import dev.nishisan.operation.inventory.adapter.auth.OAuthClientManager;
import dev.nishisan.operation.inventory.adapter.auth.jwt.CustomClosureDecoder;
import dev.nishisan.operation.inventory.adapter.configuration.EndPointConfiguration;
import dev.nishisan.operation.inventory.adapter.configuration.EndPointListenersConfiguration;
import dev.nishisan.operation.inventory.adapter.configuration.SecureProviderConfig;
import dev.nishisan.operation.inventory.adapter.exception.TokenDecodeException;
import dev.nishisan.operation.inventory.adapter.groovy.ProtectedBinding;
import dev.nishisan.operation.inventory.adapter.observabitliy.service.TracerService;
import dev.nishisan.operation.inventory.adapter.observabitliy.wrappers.SpanWrapper;
import dev.nishisan.operation.inventory.adapter.observabitliy.wrappers.TracerWrapper;
import groovy.util.GroovyScriptEngine;
import groovy.util.ResourceException;
import groovy.util.ScriptException;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
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
     * Faz o Binding do Contexto com os métodos Suporta get,post,put,patch,head
     * e delete. Se precisar implementar suporte as novos métodos deve ser
     * ajustado aqui
     *
     * @param name
     * @param listener
     * @param listenerConfig
     */
    public void addServiceListener(String name, Javalin listener, EndPointListenersConfiguration listenerConfig) {
        this.listeners.put(name, listener);
        this.proxyManager.init();
        try {
            logger.debug("Getting Tracer Method");
            Tracer tracer = tracerService.getTracing(name);

            logger.debug("Tracer OK");

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

                        listener.addHttpHandler(handlerType, urlContext.getContext(), ctx -> {
                            logger.debug("---------------------------------------------------------------------");
                            TracerWrapper traceWrapper = new TracerWrapper(tracer);
                            CustomContextWrapper customCtx = new CustomContextWrapper(ctx, contextName, urlContext, traceWrapper);

                            SpanWrapper rootSpan = traceWrapper.createSpan(urlContext.getMethod().toLowerCase() + "-handler-[" + contextName + "]");

                            try {
                                if (listenerConfig.getSecured()) {
                                    if (urlContext.getSecured()) {
                                        SpanWrapper securedPan = traceWrapper.createSpan(urlContext.getMethod().toLowerCase() + "-handler-secured-[" + contextName + "]");
                                        rootSpan.tag("url-context", urlContext.getContext());
                                        rootSpan.tag("http-method", urlContext.getMethod());
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
                                rootSpan.finish();
                            }
                        });
                    } else if ("ANY".equalsIgnoreCase(urlContext.getMethod())) {

                        methodMapping.values().forEach(type
                                -> listener.addHttpHandler(type, urlContext.getContext(), ctx -> {
                                    logger.debug("---------------------------------------------------------------------");
                                    TracerWrapper traceWrapper = new TracerWrapper(tracer);
                                    CustomContextWrapper customCtx = new CustomContextWrapper(ctx, contextName, urlContext, traceWrapper);

                                    SpanWrapper rootSpan = traceWrapper.createSpan("any-handler-[" + contextName + "]");
                                    rootSpan.tag("url-context", urlContext.getContext());
                                    rootSpan.tag("http-method", urlContext.getMethod());
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
                                        rootSpan.finish();
                                    }
                                })
                        );
                    }
                } catch (Exception ex) {
                    logger.error("Generic EX", ex);
                }
            });

            logger.debug("Trying to start Listener");

            try {
                listener.start(listenerConfig.getListenAddress(), listenerConfig.getListenPort());
                logger.debug("Listener:[" + name + "] Started at: " + listenerConfig.getListenAddress() + "/" + listenerConfig.getListenPort());

            } catch (Exception ex) {
                logger.error("Failed Listener:[" + name + "] Started at: " + listenerConfig.getListenAddress() + "/" + listenerConfig.getListenPort(), ex);
            }
        } catch (Exception ex) {
            logger.error("Generic EX At AddService Method", ex);
        }

    }

    public Map<String, Javalin> getListeners() {
        return listeners;
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

            if (configuration.getProviderClass().equalsIgnoreCase("dev.nishisan.operation.inventory.adapter.auth.jwt.JWTTokenDecoder") || configuration.getProviderClass().equalsIgnoreCase("JWTTokenDecoder")) {
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
}
