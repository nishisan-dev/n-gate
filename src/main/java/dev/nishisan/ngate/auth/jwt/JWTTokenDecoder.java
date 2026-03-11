/*
 * Copyright (C) 2024 Lucas Nishimura <lucas.nishimura at gmail.com>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package dev.nishisan.ngate.auth.jwt;

import com.auth0.jwk.Jwk;
import com.auth0.jwk.JwkException;
import com.auth0.jwk.JwkProvider;
import com.auth0.jwk.JwkProviderBuilder;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import dev.nishisan.ngate.auth.IAuthUserPrincipal;
import dev.nishisan.ngate.auth.ITokenDecoder;
import dev.nishisan.ngate.auth.JWTUserPrincipal;
import dev.nishisan.ngate.exception.TokenDecodeException;
import dev.nishisan.ngate.http.CustomContextWrapper;
import dev.nishisan.ngate.observability.wrappers.TracerWrapper;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura at gmail.com>
 * created 02.09.2024
 */
public class JWTTokenDecoder implements ITokenDecoder {

    private JwkProvider jwkProvider;
    private String issuerUri;
    private String jwkSetUri;
    private Map<String, String> options;
    private final TracerWrapper tracer;
    private Integer recreateInterval = 0;
    private Date recreateDate;

    public JWTTokenDecoder(TracerWrapper tracer) throws Exception {
        this.tracer = tracer;
    }

    @Override
    public void setOptions(Map<String, String> options) {
        this.options = options;

    }

    private IAuthUserPrincipal decodeToken(String token) throws TokenDecodeException {
        if (token == null) {
            throw new TokenDecodeException("Token Not Found");
        } else {
            if (token.contains("Bearer ")) {
                token = token.split("\\s+")[1];
                System.out.println("TOKEN:" + token);
            } else {
                throw new TokenDecodeException("Bearer Not Found");
            }
        }

        try {
            DecodedJWT jwt = JWT.decode(token);
            // Busca a chave pública usando o kid do token
            Jwk jwk = jwkProvider.get(jwt.getKeyId());
            Algorithm algorithm = Algorithm.RSA256((RSAPublicKey) jwk.getPublicKey(), null);

            JWTVerifier verifier = JWT.require(algorithm)
                    .withIssuer(issuerUri)
                    .build();

            DecodedJWT a = verifier.verify(token);
            return new JWTUserPrincipal(a);
        } catch (IllegalArgumentException | JWTVerificationException | JwkException ex) {
            throw new TokenDecodeException(ex);
        }
    }

    @Override
    public IAuthUserPrincipal decodeToken(CustomContextWrapper context) throws TokenDecodeException {
        return this.decodeToken(context.header("Authorization"));
    }

    @Override
    public void init() throws MalformedURLException {
        this.issuerUri = this.options.get("issuerUri");
        this.jwkSetUri = this.options.get("jwkSetUri");

        this.jwkProvider = new JwkProviderBuilder(new URL(jwkSetUri))
                .cached(10, 24, TimeUnit.HOURS)
                .rateLimited(10, 1, TimeUnit.MINUTES)
                .build();
    }

    @Override
    public Integer getDecoderRecreateInterval() {
        return this.recreateInterval;
    }

    @Override
    public void setDecoderRecreateInterval(Integer l) {
        this.recreateInterval = l;
    }

    @Override
    public Date getRecreateDate() {
        return recreateDate;
    }

    @Override
    public void setRecreateDate(Date recreateDate) {
        this.recreateDate = recreateDate;
    }
}
