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
package dev.nishisan.ngate.auth;

import com.google.api.client.auth.oauth2.PasswordTokenRequest;
import com.google.api.client.auth.oauth2.RefreshTokenRequest;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.http.BasicAuthentication;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import dev.nishisan.ngate.auth.wrapper.AuthToken;
import dev.nishisan.ngate.auth.wrapper.SerializableTokenData;
import dev.nishisan.ngate.cluster.ClusterService;
import dev.nishisan.ngate.configuration.OauthServerClientConfiguration;
import dev.nishisan.ngate.exception.SSONotFoundException;
import dev.nishisan.ngate.http.CustomHttpRequestInitializer;
import dev.nishisan.utils.ngrid.structures.DistributedMap;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Cliente OAuth inteligente com suporte a cluster mode via NGrid.
 * <p>
 * Em modo standalone: funciona como antes — cada instância faz login/refresh independente.
 * <p>
 * Em modo cluster: apenas o <b>líder</b> faz login/refresh no IdP e publica os tokens
 * num {@link DistributedMap}. Os followers leem tokens do mapa distribuído,
 * eliminando carga redundante no IdP.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 05.01.2023
 */
@Service
public class OAuthClientManager implements ITokenProvider {

    private static final JsonFactory JSON_FACTORY = new GsonFactory();
    private static final String TOKEN_MAP_NAME = "ngate-oauth-tokens";

    private final ConcurrentMap<String, OauthServerClientConfiguration> configuredSso = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AuthToken> currentTokens = new ConcurrentHashMap<>();
    private final Logger logger = LogManager.getLogger(OAuthClientManager.class);

    @Autowired
    private ClusterService clusterService;

    /**
     * Mascara o token para log seguro — exibe apenas os primeiros 8 caracteres.
     */
    private static String maskToken(String token) {
        if (token == null) return "null";
        if (token.length() <= 8) return "***";
        return token.substring(0, 8) + "***";
    }

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread tokenRefreshThread;

    /**
     * DistributedMap para compartilhar tokens entre instâncias do cluster.
     * Null em modo standalone.
     */
    private DistributedMap<String, SerializableTokenData> distributedTokenMap;

    @EventListener(ApplicationReadyEvent.class)
    private void onStartup() {
        logger.debug("Nishisan oAuth2 Client Manager Started... :)");
        this.running.set(true);

        // Inicializar integração com cluster se habilitado
        if (clusterService.isClusterMode()) {
            logger.info("OAuthClientManager: cluster mode detected — initializing distributed token map");
            this.distributedTokenMap = clusterService.getDistributedMap(
                    TOKEN_MAP_NAME, String.class, SerializableTokenData.class);

            // Leadership listener: start/stop refresh thread baseado na liderança
            clusterService.addLeadershipListener((isLeader, leaderId) -> {
                if (isLeader) {
                    logger.info("OAuthClientManager: became leader — starting token refresh thread");
                    startRefreshThread();
                } else {
                    logger.info("OAuthClientManager: lost leadership — stopping token refresh thread");
                    stopRefreshThread();
                }
            });

            // Se já é líder no boot, starta a thread
            if (clusterService.isLeader()) {
                startRefreshThread();
            }
        } else {
            // Standalone: sempre roda a refresh thread
            startRefreshThread();
        }

        java.util.logging.Logger httpLogger = java.util.logging.Logger.getLogger("com.google.gdata.client.http.HttpGDataRequest");
        httpLogger.setLevel(Level.ALL);
    }

    private synchronized void startRefreshThread() {
        if (tokenRefreshThread != null && tokenRefreshThread.isAlive()) {
            return;
        }
        tokenRefreshThread = new Thread(new TokenRefreshThread(), "ngate-token-refresh");
        tokenRefreshThread.setDaemon(true);
        tokenRefreshThread.start();
    }

    private synchronized void stopRefreshThread() {
        running.set(false);
        if (tokenRefreshThread != null) {
            tokenRefreshThread.interrupt();
            tokenRefreshThread = null;
        }
        // Re-enable running para quando voltar a ser líder
        running.set(true);
    }

    public void addSsoConfiguration(OauthServerClientConfiguration configuration) {
        this.configuredSso.put(configuration.getSsoName(), configuration);
    }

    public TokenResponse refreshToken(String ssoName) throws IOException, GeneralSecurityException, SSONotFoundException {
        if (this.currentTokens.containsKey(ssoName)) {
            AuthToken savedResponse = this.currentTokens.get(ssoName);
            return this.refreshToken(savedResponse);
        } else {
            throw new SSONotFoundException("SSO [" + ssoName + "] not found");
        }
    }

    /**
     * Obtém o access token para o SSO especificado.
     * <p>
     * Em cluster mode + follower: lê do DistributedMap.
     * Em standalone ou leader: fluxo normal + publica no DistributedMap.
     */
    @Override
    public TokenResponse getAccessToken(String ssoName) throws IOException {
        // ═══ Cluster mode: follower lê do DistributedMap ═══
        if (clusterService.isClusterMode() && !clusterService.isLeader() && distributedTokenMap != null) {
            return getTokenFromDistributedMap(ssoName);
        }

        // ═══ Standalone ou líder: fluxo normal ═══
        if (this.currentTokens.containsKey(ssoName)) {
            AuthToken savedResponse = this.currentTokens.get(ssoName);

            if (savedResponse.getCurrentResponse().getExpiresInSeconds() != null) {
                Calendar cal = Calendar.getInstance();
                cal.setTime(savedResponse.getLastTimeTokenRefreshed());
                cal.add(Calendar.SECOND, savedResponse.getCurrentResponse().getExpiresInSeconds().intValue());

                Date now = new Date(System.currentTimeMillis());
                if (now.after(cal.getTime())) {
                    // Token expirou, precisa de refresh
                    this.currentTokens.remove(ssoName);
                    try {
                        this.refreshToken(savedResponse);
                        this.currentTokens.put(ssoName, savedResponse);
                        publishTokenToCluster(ssoName, savedResponse);
                        logger.debug("New Token Just Created For:[{}] ,Token:[{}]", ssoName, maskToken(savedResponse.getCurrentResponse().getAccessToken()));
                        return savedResponse.getCurrentResponse();
                    } catch (GeneralSecurityException | IOException ex) {
                        // Não conseguiu refresh, segue no fluxo normal
                    }
                } else {
                    logger.debug("SSO [{}] Using Cached Token From:[{}], Token:[{}]", ssoName, savedResponse.getConfiguration().getTokenServerUrl(), maskToken(savedResponse.getCurrentResponse().getAccessToken()));
                    return savedResponse.getCurrentResponse();
                }
            }
        } else {
            logger.warn("Token Not Found:[{}] Will Request a New One", ssoName);
        }

        // Fluxo Normal
        try {
            OauthServerClientConfiguration oauthServerClientConfiguration = configuredSso.get(ssoName);
            TokenResponse response = this.getAccessTokenFromPassword(oauthServerClientConfiguration);
            Calendar cal = Calendar.getInstance();
            cal.setTime(new Date());
            cal.add(Calendar.SECOND, response.getExpiresInSeconds().intValue());
            AuthToken authToken = new AuthToken(response, oauthServerClientConfiguration, ssoName, new Date(), cal.getTime());
            this.currentTokens.put(ssoName, authToken);
            publishTokenToCluster(ssoName, authToken);
            logger.debug("Sent New Token: [{}]", maskToken(response.getAccessToken()));
            return response;
        } catch (IOException ex) {
            logger.error("Failed to request Token:[{}]", ssoName, ex);
            throw ex;
        }
    }

    /**
     * Lê token do DistributedMap (usado por followers em cluster mode).
     */
    private TokenResponse getTokenFromDistributedMap(String ssoName) throws IOException {
        Optional<SerializableTokenData> opt = distributedTokenMap.get(ssoName);
        if (opt.isPresent()) {
            SerializableTokenData data = opt.get();
            TokenResponse response = new TokenResponse();
            response.setAccessToken(data.accessToken());
            response.setRefreshToken(data.refreshToken());
            response.setExpiresInSeconds(data.expiresInSeconds());
            response.setTokenType(data.tokenType());
            response.setScope(data.scope());
            logger.debug("SSO [{}] Using cluster-replicated token, Token:[{}]", ssoName, maskToken(data.accessToken()));
            return response;
        }
        // Token não está no mapa distribuído — pode ser que o líder ainda não tenha feito login
        logger.warn("Token [{}] not found in distributed map — attempting local login", ssoName);
        // Fallback: fazer login localmente (pode acontecer se o líder ainda está bootando)
        OauthServerClientConfiguration config = configuredSso.get(ssoName);
        if (config == null) {
            throw new IOException("SSO [" + ssoName + "] not configured and not available in cluster");
        }
        return getAccessTokenFromPassword(config);
    }

    /**
     * Publica token no DistributedMap para replicação no cluster.
     */
    private void publishTokenToCluster(String ssoName, AuthToken authToken) {
        if (distributedTokenMap == null || !clusterService.isLeader()) {
            return;
        }
        try {
            TokenResponse response = authToken.getCurrentResponse();
            SerializableTokenData data = new SerializableTokenData(
                    response.getAccessToken(),
                    response.getRefreshToken(),
                    response.getExpiresInSeconds(),
                    response.getTokenType(),
                    response.getScope() != null ? response.getScope() : "",
                    ssoName,
                    authToken.getLastTimeTokenRefreshed(),
                    authToken.getExpiresIn()
            );
            distributedTokenMap.put(ssoName, data);
            logger.debug("Published token [{}] to distributed map", ssoName);
        } catch (Exception e) {
            logger.error("Failed to publish token [{}] to distributed map", ssoName, e);
        }
    }

    /**
     * Realiza o flow do grant_type password
     */
    public TokenResponse getAccessTokenFromPassword(OauthServerClientConfiguration oauthServerClientConfiguration) throws IOException {
        GenericUrl url = new GenericUrl(oauthServerClientConfiguration.getTokenServerUrl());
        logger.debug("Requesting New Token At:[{}] With Grant Type:[password]", oauthServerClientConfiguration.getTokenServerUrl());
        PasswordTokenRequest passwordTokenRequest
                = new PasswordTokenRequest(new NetHttpTransport(),
                        JSON_FACTORY, url,
                        oauthServerClientConfiguration.getUserName(),
                        oauthServerClientConfiguration.getPassword());

        passwordTokenRequest.setGrantType("password");
        passwordTokenRequest.setScopes(oauthServerClientConfiguration.getAuthScopes());

        Map<String, String> headers = new HashMap<>();
        if (oauthServerClientConfiguration.getOam() != null && oauthServerClientConfiguration.getAppKey() != null) {
            headers.put("app-key", oauthServerClientConfiguration.getAppKey());
            headers.put("oam", oauthServerClientConfiguration.getOam());
        }
        if (oauthServerClientConfiguration.getExtraHeaders() != null
                && !oauthServerClientConfiguration.getExtraHeaders().isEmpty()) {
            headers.putAll(oauthServerClientConfiguration.getExtraHeaders());
        }

        passwordTokenRequest.setRequestInitializer(new CustomHttpRequestInitializer(headers));
        passwordTokenRequest.setClientAuthentication(
                new BasicAuthentication(oauthServerClientConfiguration.getClientId(),
                        oauthServerClientConfiguration.getClientSecret()));
        TokenResponse response = passwordTokenRequest.execute();

        if (!this.currentTokens.containsKey(oauthServerClientConfiguration.getSsoName())) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(new Date());
            cal.add(Calendar.SECOND, response.getExpiresInSeconds().intValue());
            logger.debug("Token [{}] Created New Expiration AT: [{}]", oauthServerClientConfiguration.getSsoName(), cal.getTime());
            AuthToken authToken = new AuthToken(response, oauthServerClientConfiguration, oauthServerClientConfiguration.getSsoName(), new Date(), cal.getTime());
            this.currentTokens.put(oauthServerClientConfiguration.getSsoName(), authToken);
            publishTokenToCluster(oauthServerClientConfiguration.getSsoName(), authToken);
        } else {
            Calendar cal = Calendar.getInstance();
            cal.setTime(new Date());
            cal.add(Calendar.SECOND, response.getExpiresInSeconds().intValue());
            logger.debug("Token [{}] Renewed New Expiration AT: [{}]", oauthServerClientConfiguration.getSsoName(), cal.getTime());
            AuthToken authToken = new AuthToken(response, oauthServerClientConfiguration, oauthServerClientConfiguration.getSsoName(), new Date(), cal.getTime());
            this.currentTokens.replace(oauthServerClientConfiguration.getSsoName(), authToken);
            publishTokenToCluster(oauthServerClientConfiguration.getSsoName(), authToken);
        }

        return response;
    }

    /**
     * Solicita um Refresh do token
     */
    public TokenResponse refreshToken(AuthToken previousToken) throws IOException, GeneralSecurityException {
        GenericUrl tokenServerUrl = new GenericUrl(previousToken.getConfiguration().getTokenServerUrl());

        RefreshTokenRequest refreshTokenRequest
                = new RefreshTokenRequest(new NetHttpTransport.Builder().doNotValidateCertificate().build(),
                        JSON_FACTORY, tokenServerUrl,
                        previousToken.getCurrentResponse().getRefreshToken());

        refreshTokenRequest.setClientAuthentication(
                new BasicAuthentication(previousToken.getConfiguration().getClientId(),
                        previousToken.getConfiguration().getClientSecret()));
        Map<String, String> headers = new HashMap<>();
        if (previousToken.getConfiguration().getOam() != null && previousToken.getConfiguration().getAppKey() != null) {
            headers.put("app-key", previousToken.getConfiguration().getAppKey());
            headers.put("oam", previousToken.getConfiguration().getOam());
        }
        HttpRequestInitializer requestInitializer = new CustomHttpRequestInitializer(headers);
        refreshTokenRequest.setRequestInitializer(requestInitializer);

        TokenResponse response = refreshTokenRequest.execute();
        previousToken.setCurrentResponse(response);
        previousToken.setLastTimeTokenRefreshed(new Date());
        Calendar cal = Calendar.getInstance();
        cal.setTime(new Date());
        cal.add(Calendar.SECOND, response.getExpiresInSeconds().intValue());
        previousToken.setExpiresIn(cal.getTime());
        previousToken.setLastTimeTokenRefreshed(new Date());

        // Publicar token atualizado no cluster
        publishTokenToCluster(previousToken.getOauthName(), previousToken);

        return response;
    }

    /**
     * Thread que verifica tokens próximos de expiração e faz refresh proativo.
     * Em cluster mode, roda <b>apenas no líder</b>.
     */
    private class TokenRefreshThread implements Runnable {

        @Override
        public void run() {
            Calendar cal = Calendar.getInstance();
            List<String> tokensToBeRemoved = new ArrayList<>();
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                currentTokens.forEach((tokenName, tokenAuth) -> {
                    if (tokenAuth.getCurrentResponse() != null) {
                        if (tokenAuth.getConfiguration().getUseRefreshToken()) {
                            if (tokenAuth.getCurrentResponse().getExpiresInSeconds() != null) {
                                cal.setTime(new Date());
                                cal.add(Calendar.SECOND, tokenAuth.getConfiguration().getRenewBeforeSecs());
                                if (cal.getTime().after(tokenAuth.getExpiresIn())) {
                                    logger.debug("Token [{}] Will Expire in 30s, refreshing", tokenName);
                                    try {
                                        refreshToken(tokenAuth);
                                        logger.debug("Token [{}] Refreshed New Expiration AT: [{}]", tokenName, tokenAuth.getExpiresIn());
                                    } catch (IOException | GeneralSecurityException ex) {
                                        logger.error("Failed to Refresh Token:[" + tokenName + "]", ex);
                                        tokensToBeRemoved.add(tokenName);
                                    }
                                }
                            }
                        } else {
                            if (tokenAuth.getCurrentResponse().getExpiresInSeconds() != null) {
                                cal.setTime(new Date());
                                cal.add(Calendar.SECOND, tokenAuth.getConfiguration().getRenewBeforeSecs());
                                if (cal.getTime().after(tokenAuth.getExpiresIn())) {
                                    logger.warn("Refresh Token Not Enabled for :[{}]", tokenName);
                                    tokensToBeRemoved.add(tokenName);
                                }
                            }
                        }
                    }
                });

                if (!tokensToBeRemoved.isEmpty()) {
                    tokensToBeRemoved.forEach(e -> {
                        currentTokens.remove(e);
                        logger.warn("Token Removed due to errors");
                    });
                    tokensToBeRemoved.clear();
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
