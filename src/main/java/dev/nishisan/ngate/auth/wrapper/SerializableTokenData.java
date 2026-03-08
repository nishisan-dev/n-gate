/*
 * Copyright (C) 2026 Lucas Nishimura <lucas.nishimura@gmail.com>
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
package dev.nishisan.ngate.auth.wrapper;

import java.io.Serializable;
import java.util.Date;

/**
 * Versão serializável dos dados de um token OAuth.
 * <p>
 * A classe {@link com.google.api.client.auth.oauth2.TokenResponse} do Google OAuth
 * não implementa {@link Serializable}, portanto este record captura apenas os dados
 * essenciais para replicação via NGrid {@link dev.nishisan.utils.ngrid.structures.DistributedMap}.
 * <p>
 * O líder do cluster converte entre {@code TokenResponse} e este record;
 * followers usam os dados deste record diretamente para servir requests.
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 2026-03-08
 */
public record SerializableTokenData(
        String accessToken,
        String refreshToken,
        Long expiresInSeconds,
        String tokenType,
        String scope,
        String oauthName,
        Date lastTimeTokenRefreshed,
        Date expiresIn
) implements Serializable {

    private static final long serialVersionUID = 1L;
}
