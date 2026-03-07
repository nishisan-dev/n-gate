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
package dev.nishisan.operation.inventory.adapter.http;

import dev.nishisan.operation.inventory.adapter.configuration.BackendConfiguration;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura@gmail.com>
 * @created 19.01.2023
 */
public class HttpRequestAdapter {

    private final Logger logger = LogManager.getLogger(HttpRequestAdapter.class);

    public Request getRequest(BackendConfiguration backeEndConfiguration, HttpWorkLoad workload, String reqUID) {
        Long start = System.currentTimeMillis();
        Request.Builder builder = new Request.Builder();
        RequestBody requestBody = null;
        HttpAdapterServletRequest request = workload.getRequest();

        /**
         * Add Adapter Headers to upstream
         */
//        builder.addHeader("x-netcompass-uid", reqUID);
        try {
            if (request.getBodyAsBytes() != null
                    && request.getBodyAsBytes().length > 0) {
                logger.debug("Body Size: [{}]", request.getBodyAsBytes().length);

                //
                // Temos um Body para Copiar.
                //
                //
                // Copia o Body e  Media Type
                //
                String contentType = request.getContentType();
                String charSet = request.getCharacterEncoding();
                if (contentType != null) {
                    String mediaTypeString = contentType;
                    if (charSet != null) {
                        mediaTypeString = mediaTypeString + "charset=" + charSet;
                    }
                    MediaType mediaType
                            = MediaType.parse(request.getContentType() + "charset=" + request.getCharacterEncoding());
                    requestBody = RequestBody.create(mediaType, request.getBodyAsBytes());
                } else {
                    requestBody = RequestBody.create(request.getBodyAsBytes());
                }

                if (logger.isDebugEnabled()) {
                    logger.debug("Body: ContentType:[{}]", request.getContentType() + "charset=" + request.getCharacterEncoding());
                    logger.debug("Body content length: [{}]", request.getBodyAsBytes().length);
                }
            } else {

                logger.info("Request BODY IS EMPTY");

            }
        } catch (IOException ex) {
            logger.warn("Failed to Copy Body");
        }

        /**
         * Tratamento de cookies:
         */
        if (request.getCookies() != null) {
            if (request.getCookies().length > 0) {
                Cookie[] cookies = request.getCookies();
                StringBuilder cookieHeader = new StringBuilder();
                for (Cookie cookie : cookies) {
                    if (cookieHeader.length() > 0) {
                        cookieHeader.append("; ");
                    }
                    cookieHeader.append(cookie.getName()).append("=").append(cookie.getValue());
                    logger.debug("Adding Cookie:[{}]", cookie.getName());
                }
                //
                // Repassa os Cookies  para o novo request
                //
                builder.addHeader("Cookie", cookieHeader.toString());
            }
        }

        builder.method(request.getMethod(), requestBody);

        //
        // Copia os headers
        //
        request.getHeaderNames().asIterator().forEachRemaining((header) -> {
            logger.debug("Adding Header:[{}]:=[{}]", header, request.getHeader(header));
            builder.addHeader(header, request.getHeader(header));

        });
        logger.debug("Adding UID:[{}] to Request:", reqUID);

        /**
         * Default header will Always be sent!
         */
        backeEndConfiguration.getDefaultHeaders().forEach((h, v) -> {
            /**
             * Prevents duplicates...
             */
            builder.removeHeader(h);
            builder.addHeader(h, v);
        });

        //
        // Seta a URL do Destino
        //
        String url = backeEndConfiguration.getEndPointUrl();
        url += request.getRequestURI();
        if (request.getQueryString() != null) {
            url += "?" + request.getQueryString();
        }
        builder.url(url);

//        }       
        Request req = builder.build();

        Long end = System.currentTimeMillis();
        Long took = end - start;
        logger.debug("Request Method[{}] TO URL:[{}] Took:[{}]", req.method(), req.url(), took);
        return req;
    }

}
