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
package dev.nishisan.operation.inventory.adapter.auth;

import com.google.api.client.auth.oauth2.PasswordTokenRequest;
import com.google.api.client.auth.oauth2.RefreshTokenRequest;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.http.BasicAuthentication;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import dev.nishisan.operation.inventory.adapter.auth.wrapper.AuthToken;
import dev.nishisan.operation.inventory.adapter.configuration.OauthServerClientConfiguration;
import dev.nishisan.operation.inventory.adapter.exception.SSONotFoundException;
import dev.nishisan.operation.inventory.adapter.http.CustomHttpRequestInitializer;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Calendar;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.apache.logging.log4j.LogManager;

import org.apache.logging.log4j.Logger;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Um Cliente OAUTH que é bonito de ver funcionar :) Este cliente é inteligente
 * para lidar com todas as questoes de gestão do ciclo de vida do token, de
 * forma que reaproveitamos o token até sua expiração. Este cliente também
 * consegue gerenciar multiplos SSO's, para isso basta adicionar uma nova
 * configuração através do método:
 * addSsoConfiguration(OauthServerClientConfiguration configuration)
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 05.01.2023
 */
@Service
public class OAuthClientManager implements ITokenProvider {

    private static final JsonFactory JSON_FACTORY = new GsonFactory();
    private final ConcurrentMap<String, OauthServerClientConfiguration> configuredSso = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AuthToken> currentTokens = new ConcurrentHashMap<>();
    private final Logger logger = LogManager.getLogger(OAuthClientManager.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Thread tokenRefreshThread = new Thread(new TokenRefreshThread());

    @EventListener(ApplicationReadyEvent.class)
    private void onStartup() {
        logger.debug("Nishisan oAuth2 Client Manager Started... :)");
        this.running.set(true);
        tokenRefreshThread.start();
        //
        // Faz o Debug do client do Oauth do google
        //
        java.util.logging.Logger httpLogger = java.util.logging.Logger.getLogger("com.google.gdata.client.http.HttpGDataRequest");
        httpLogger.setLevel(Level.ALL);
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
     * Get the Access TOKEN using the OLD password flow
     *
     * @param ssoName
     * @return
     * @throws IOException
     */
    @Override
    public TokenResponse getAccessToken(String ssoName) throws IOException {
        if (this.currentTokens.containsKey(ssoName)) {
            //
            // Opa temos um token salvo
            //
            AuthToken savedResponse = this.currentTokens.get(ssoName);

            if (savedResponse.getCurrentResponse().getExpiresInSeconds() != null) {
                //
                // Vamos calcular a data de expiração
                // 
                Calendar cal = Calendar.getInstance();

                cal.setTime(savedResponse.getLastTimeTokenRefreshed());
                cal.add(Calendar.SECOND, savedResponse.getCurrentResponse().getExpiresInSeconds().intValue());

                Date now = new Date(System.currentTimeMillis());
                if (now.after(cal.getTime())) {
                    //
                    // Token Expirou , precisa de um refresh
                    //
                    this.currentTokens.remove(ssoName);
                    try {
                        //
                        // Tenta fazer o Refresh
                        //
                        this.refreshToken(savedResponse);
                        //
                        // Se conseguiu o refresh atualiza o cache
                        //
                        this.currentTokens.put(ssoName, savedResponse);
                        logger.debug("New Token Just Created For:[{}] ,Token:[{}]", ssoName, savedResponse.getCurrentResponse().getAccessToken());
                        return savedResponse.getCurrentResponse();
                    } catch (GeneralSecurityException | IOException ex) {
                        //
                        // Não conseguiu fazer o Refresh, vai seguir no fluxo normal.
                        //
                    }

                } else {
                    logger.debug("SSO [{}] Using Cached Token From:[{}], Token:[{}]", ssoName, savedResponse.getConfiguration().getTokenServerUrl(), savedResponse.getCurrentResponse().getAccessToken());
                    return savedResponse.getCurrentResponse();
                }
            }
        } else {
            logger.warn("Token Not Found:[{}] Will Request a New One", ssoName);
        }
        //
        // Fluxo Normal
        //
        try {
            OauthServerClientConfiguration oauthServerClientConfiguration = configuredSso.get(ssoName);
            TokenResponse response = this.getAccessTokenFromPassword(oauthServerClientConfiguration);
            Calendar cal = Calendar.getInstance();
            cal.setTime(new Date());
            cal.add(Calendar.SECOND, response.getExpiresInSeconds().intValue());
            this.currentTokens.put(ssoName, new AuthToken(response, oauthServerClientConfiguration, ssoName, new Date(), cal.getTime()));
            logger.debug("Sent New Token: [{}]", response.getAccessToken());
            return response;
        } catch (IOException ex) {
            logger.error("Failed to request Token:[{}]", ssoName, ex);
            throw ex;
        }

    }

    /**
     * Realiza o flow do grant_type password no OAM
     *
     * @param oauthServerClientConfiguration
     * @return
     * @throws IOException
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
        //
        // Tokens exclusicos da Telefonica
        //
        if (oauthServerClientConfiguration.getOam()
                != null && oauthServerClientConfiguration.getAppKey() != null) {
            headers.put("app-key", oauthServerClientConfiguration.getAppKey());
            headers.put("oam", oauthServerClientConfiguration.getOam());

        }

        /**
         * Cuida para que o Autenticador também possa ter headers customizados
         */
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
            this.currentTokens.put(oauthServerClientConfiguration.getSsoName(), new AuthToken(response, oauthServerClientConfiguration, oauthServerClientConfiguration.getSsoName(), new Date(), cal.getTime()));
        } else {
            Calendar cal = Calendar.getInstance();
            cal.setTime(new Date());
            cal.add(Calendar.SECOND, response.getExpiresInSeconds().intValue());
            logger.debug("Token [{}] Renewed New Expiration AT: [{}]", oauthServerClientConfiguration.getSsoName(), cal.getTime());
            this.currentTokens.replace(oauthServerClientConfiguration.getSsoName(), new AuthToken(response, oauthServerClientConfiguration, oauthServerClientConfiguration.getSsoName(), new Date(), cal.getTime()));

        }

        return response;
    }

    /**
     * Solicita um Refresh do token
     *
     * @param previousToken
     * @return
     * @throws IOException
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
        //
        // Tokens exclusicos da Telefonica
        //
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
        return response;
    }

    /**
     * Esta classizinha fica verificando se temos algum token próximo de
     * expiração e pró ativamente faz o refresh do mesmo
     */
    private class TokenRefreshThread implements Runnable {

        @Override
        public void run() {

            Calendar cal = Calendar.getInstance();
            List<String> tokensToBeRemoved = new ArrayList<>();
            while (running.get()) {

//                logger.debug("Total Tokens Found: [{}]", currentTokens.size());
                currentTokens.forEach((tokenName, tokenAuth) -> {
                    if (tokenAuth.getCurrentResponse() != null) {
                        if (tokenAuth.getConfiguration().getUseRefreshToken()) {
                            if (tokenAuth.getCurrentResponse().getExpiresInSeconds() != null) {
                                //
                                // Verifica se temos tokens vencendos nos próxios 30s
                                //
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
                            //
                            // Refresh Token Not Enabled for 
                            //
                            if (tokenAuth.getCurrentResponse().getExpiresInSeconds() != null) {
                                //
                                // Verifica se temos tokens vencendos nos próxios 30s
                                //
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

                //
                // Descansa um pouquinho para poupar CPU xD
                //
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                }
            }
        }
    }
}
