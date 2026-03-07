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
package dev.nishisan.operation.inventory.adapter.http;

import dev.nishisan.operation.inventory.adapter.http.synth.response.SyntHttpResponse;
import okhttp3.Response;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura at gmail.com>
 * created 29.08.2024
 */
public class SynthHttpResponseAdapter {

    public SyntHttpResponse getResponse(Response res) {
        SyntHttpResponse response = new SyntHttpResponse(res);
        res.headers().forEach(n -> {
            response.addHeader(n.getFirst(), n.getSecond());
        });
//        try {
////            response.setContent(res.body().string());
//        } catch (IOException ex) {
//            Logger.getLogger(SynthHttpResponseAdapter.class.getName()).log(Level.SEVERE, null, ex);
//        }
        return response;
    }
}
