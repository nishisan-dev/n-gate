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
package dev.nishisan.operation.inventory.adapter.auth;

import com.auth0.jwt.interfaces.DecodedJWT;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura at gmail.com>
 * created 02.09.2024
 */
public class JWTUserPrincipal extends ABSAuthUserPrincipal {

    public JWTUserPrincipal(String token, String payload, String signature, String subject, String type) {
        this.setToken(token);
        this.setPayload(payload);
        this.setSignature(signature);
        this.setSubject(subject);
        this.setType(type);
    }

    public JWTUserPrincipal(DecodedJWT a) {
        if (!a.getClaims().isEmpty()) {

            a.getClaims().forEach((s, d) -> {
                System.out.println("Name:" + s);

                if (!d.isNull()) {
                    if (s != null) {
                        if (d.asString() != null) {
                            this.addClaim(s, d.asString());
//                            System.out.println("Value [S]:" + d.asString());
                        } else if (d.asMap() != null) {
                            this.addClaim(s, d.asMap());
//                            System.out.println("Value [M]:" + d.asMap());
                        } else if (d.asList(String.class) != null) {
                            this.addClaim(s, d.asList(String.class));
//                            System.out.println("Value [L]:" + d.asList(String.class));
                        } else if (d.asLong() != null) {
                            this.addClaim(s, d.asLong());
//                            System.out.println("Value [Long]:" + d.asLong());
                        } else if (d.asBoolean() != null) {
                            this.addClaim(s, d.asBoolean());
//                            System.out.println("Value [B]:" + d.asBoolean());
                        }

                    } else {

                    }
                }
                System.out.println("-----------------------------");
            });
        }
        this.setToken(a.getToken());
        this.setPayload(a.getPayload());
        this.setSignature(a.getSignature());
        this.setExpireAt(a.getExpiresAt());
        this.setSubject(a.getSubject());
        this.setType(a.getType());
        this.setIssuedAt(a.getIssuedAt());
        this.setIssuer(a.getIssuer());
        this.setId(a.getId());

    }

    public JWTUserPrincipal() {
    }

}
