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

import com.google.api.client.auth.oauth2.TokenResponse;
import java.io.IOException;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura at gmail.com>
 * @created 13.08.2024
 */
public interface ITokenProvider {

    public TokenResponse getAccessToken(String ssoName) throws IOException;
    
}
