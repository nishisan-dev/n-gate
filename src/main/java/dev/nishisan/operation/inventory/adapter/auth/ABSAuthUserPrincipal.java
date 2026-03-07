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

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura at gmail.com>
 * created 02.09.2024
 */
public abstract class ABSAuthUserPrincipal implements IAuthUserPrincipal{

    private Map<String, Object> claims = new ConcurrentHashMap<>();
    private String token;
    private String payload;
    private String signature;
    private String subject;
    private String type;
    private Date expireAt;
    private Date issuedAt;
    private String id;
    private String issuer;

    public ABSAuthUserPrincipal(String token, String payload, String signature, String subject, String type) {
        this.token = token;
        this.payload = payload;
        this.signature = signature;
        this.subject = subject;
        this.type = type;
    }

    public ABSAuthUserPrincipal() {
    }

    public Map<String, Object> getClaims() {
        return claims;
    }

    public void setClaims(Map<String, Object> claims) {
        this.claims = claims;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Date getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(Date expireAt) {
        this.expireAt = expireAt;
    }

    public Date getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Date issuedAt) {
        this.issuedAt = issuedAt;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public void addClaim(String claim,Object value){
        this.claims.put(claim, value);
    }
        
}
