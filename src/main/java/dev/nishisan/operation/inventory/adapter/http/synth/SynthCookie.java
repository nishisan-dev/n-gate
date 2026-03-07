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
package dev.nishisan.operation.inventory.adapter.http.synth;

import java.time.DateTimeException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import jakarta.servlet.http.Cookie;

import org.springframework.core.style.ToStringCreator;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura at gmail.com>
 * created 29.08.2024
 */
@SuppressWarnings("removal")
public class SynthCookie extends Cookie {

//    private static final long serialVersionUID = 4312531139502726325L;
    private static final String SAME_SITE = "SameSite";
    private static final String EXPIRES = "Expires";

    @Nullable
    private ZonedDateTime expires;

    public SynthCookie(String name, String value) {
        super(name, value);
    }

    public void setExpires(@Nullable ZonedDateTime expires) {
        setAttribute(EXPIRES, (expires != null ? expires.format(DateTimeFormatter.RFC_1123_DATE_TIME) : null));
    }

    @Nullable
    public ZonedDateTime getExpires() {
        return this.expires;
    }

    public void setSameSite(@Nullable String sameSite) {
        setAttribute(SAME_SITE, sameSite);
    }

    @Nullable
    public String getSameSite() {
        return getAttribute(SAME_SITE);
    }

    public void setPartitioned(boolean partitioned) {
        if (partitioned) {
            setAttribute("Partitioned", "");
        } else {
            setAttribute("Partitioned", null);
        }
    }

    public boolean isPartitioned() {
        return getAttribute("Partitioned") != null;
    }

    public static SynthCookie parse(String setCookieHeader) {
        Assert.notNull(setCookieHeader, "Set-Cookie header must not be null");
        String[] cookieParts = setCookieHeader.split("\\s*=\\s*", 2);
        Assert.isTrue(cookieParts.length == 2, () -> "Invalid Set-Cookie header '" + setCookieHeader + "'");

        String name = cookieParts[0];
        String[] valueAndAttributes = cookieParts[1].split("\\s*;\\s*", 2);
        String value = valueAndAttributes[0];
        String[] attributes
                = (valueAndAttributes.length > 1 ? valueAndAttributes[1].split("\\s*;\\s*") : new String[0]);

        SynthCookie cookie = new SynthCookie(name, value);
        for (String attribute : attributes) {
            if (StringUtils.startsWithIgnoreCase(attribute, "Domain")) {
                cookie.setDomain(extractAttributeValue(attribute, setCookieHeader));
            } else if (StringUtils.startsWithIgnoreCase(attribute, "Max-Age")) {
                cookie.setMaxAge(Integer.parseInt(extractAttributeValue(attribute, setCookieHeader)));
            } else if (StringUtils.startsWithIgnoreCase(attribute, EXPIRES)) {
                try {
                    cookie.setExpires(ZonedDateTime.parse(extractAttributeValue(attribute, setCookieHeader),
                            DateTimeFormatter.RFC_1123_DATE_TIME));
                } catch (DateTimeException ex) {
                    // ignore invalid date formats
                }
            } else if (StringUtils.startsWithIgnoreCase(attribute, "Path")) {
                cookie.setPath(extractAttributeValue(attribute, setCookieHeader));
            } else if (StringUtils.startsWithIgnoreCase(attribute, "Secure")) {
                cookie.setSecure(true);
            } else if (StringUtils.startsWithIgnoreCase(attribute, "HttpOnly")) {
                cookie.setHttpOnly(true);
            } else if (StringUtils.startsWithIgnoreCase(attribute, SAME_SITE)) {
                cookie.setSameSite(extractAttributeValue(attribute, setCookieHeader));
            } else if (StringUtils.startsWithIgnoreCase(attribute, "Comment")) {
                cookie.setComment(extractAttributeValue(attribute, setCookieHeader));
            } else if (!attribute.isEmpty()) {
                cookie.setAttribute(attribute, extractOptionalAttributeValue(attribute, setCookieHeader));
            }
        }
        return cookie;
    }

    private static String extractAttributeValue(String attribute, String header) {
        String[] nameAndValue = attribute.split("=");
        Assert.isTrue(nameAndValue.length == 2,
                () -> "No value in attribute '" + nameAndValue[0] + "' for Set-Cookie header '" + header + "'");
        return nameAndValue[1];
    }

    private static String extractOptionalAttributeValue(String attribute, String header) {
        String[] nameAndValue = attribute.split("=");
        return nameAndValue.length == 2 ? nameAndValue[1] : "";
    }

    @Override
    public void setAttribute(String name, @Nullable String value) {
        if (EXPIRES.equalsIgnoreCase(name)) {
            this.expires = (value != null ? ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME) : null);
        }
        super.setAttribute(name, value);

    }

    @Override
    public String toString() {
        return new ToStringCreator(this)
                .append("name", getName())
                .append("value", getValue())
                .append("Path", getPath())
                .append("Domain", getDomain())
                .append("Version", getVersion())
                .append("Comment", getComment())
                .append("Secure", getSecure())
                .append("HttpOnly", isHttpOnly())
                .append("Partitioned", isPartitioned())
                .append(SAME_SITE, getSameSite())
                .append("Max-Age", getMaxAge())
                .append(EXPIRES, getAttribute(EXPIRES))
                .toString();
    }

}
