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
package dev.nishisan.operation.inventory.adapter.api;

import com.google.api.client.auth.oauth2.TokenResponse;
import dev.nishisan.operation.inventory.adapter.auth.OAuthClientManager;
import dev.nishisan.operation.inventory.adapter.exception.SSONotFoundException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 05.01.2023
 */
//@RestController
//@RequestMapping("adapter/v1/auth")
//@Service
public class TokenApi {

    @Autowired
    private OAuthClientManager oAUthClient;

    /**
     * Recupera o Token de TI pelo escopo
     *
     * @param scope
     * @return
     * @throws IOException
     */
    @GetMapping(path = "/token/{id}", produces = "application/json")
    public TokenResponse getTokenByName(@PathVariable String id) throws IOException {
        TokenResponse r = oAUthClient.getAccessToken(id);
        return r;
    }

    @GetMapping(path = "/token/{id}/refresh", produces = "application/json")
    public TokenResponse refreshToken(@PathVariable String id) throws IOException, GeneralSecurityException, SSONotFoundException {

        TokenResponse r = oAUthClient.refreshToken(id);
        return r;
    }
}
