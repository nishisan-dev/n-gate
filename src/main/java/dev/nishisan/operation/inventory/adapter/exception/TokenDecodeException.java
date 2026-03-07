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

package dev.nishisan.operation.inventory.adapter.exception;

/**
 *
 * @author  Lucas Nishimura <lucas.nishimura at gmail.com> 
 * created 02.09.2024
 */
public class TokenDecodeException extends Exception{

    public TokenDecodeException() {
    }

    public TokenDecodeException(String message) {
        super(message);
    }

    public TokenDecodeException(String message, Throwable cause) {
        super(message, cause);
    }

    public TokenDecodeException(Throwable cause) {
        super(cause);
    }

    public TokenDecodeException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

    
}
