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
package dev.nishisan.operation.inventory.adapter.auth.wrapper;

import com.google.api.client.auth.oauth2.TokenResponse;
import dev.nishisan.operation.inventory.adapter.configuration.OauthServerClientConfiguration;
import java.util.Date;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 05.01.2023
 */
public class AuthToken {

    private TokenResponse currentResponse;
//    private 
    private OauthServerClientConfiguration configuration;
    private String oauthName;
    private Date lastTimeTokenRefreshed;
    private Date expiresIn;

    public AuthToken() {
    }

    public AuthToken(TokenResponse currentResponse, OauthServerClientConfiguration configuration, String oauthName, Date lastTimeTokenRefreshed, Date expiresIn) {
        this.currentResponse = currentResponse;
        this.configuration = configuration;
        this.oauthName = oauthName;
        this.lastTimeTokenRefreshed = lastTimeTokenRefreshed;
        this.expiresIn = expiresIn;
    }

    public TokenResponse getCurrentResponse() {
        return currentResponse;
    }

    public void setCurrentResponse(TokenResponse currentResponse) {
        this.currentResponse = currentResponse;
    }

    public OauthServerClientConfiguration getConfiguration() {
        return configuration;
    }

    public void setConfiguration(OauthServerClientConfiguration configuration) {
        this.configuration = configuration;
    }

    public String getOauthName() {
        return oauthName;
    }

    public void setOauthName(String oauthName) {
        this.oauthName = oauthName;
    }

    public Date getLastTimeTokenRefreshed() {
        return lastTimeTokenRefreshed;
    }

    public void setLastTimeTokenRefreshed(Date lastTimeTokenRefreshed) {
        this.lastTimeTokenRefreshed = lastTimeTokenRefreshed;
    }

    /**
     * @param expiresIn the expiresIn to set
     */
    public void setExpiresIn(Date expiresIn) {
        this.expiresIn = expiresIn;
    }

    /**
     * @return the expiresIn
     */
    public Date getExpiresIn() {
        return expiresIn;
    }

}
