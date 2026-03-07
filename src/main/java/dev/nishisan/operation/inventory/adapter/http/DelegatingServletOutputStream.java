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

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.IOException;
import java.io.OutputStream;
import org.springframework.util.Assert;

/**
 *
 * @author Lucas Nishimura <lucas.nishimura at gmail.com>
 * created 29.08.2024
 */
public class DelegatingServletOutputStream extends ServletOutputStream {

    private final OutputStream targetStream;

    /**
     * Create a DelegatingServletOutputStream for the given target stream.
     *
     * @param targetStream the target stream (never {@code null})
     */
    public DelegatingServletOutputStream(OutputStream targetStream) {
        Assert.notNull(targetStream, "Target OutputStream must not be null");
        this.targetStream = targetStream;
    }

    /**
     * Return the underlying target stream (never {@code null}).
     */
    public final OutputStream getTargetStream() {
        return this.targetStream;
    }

    @Override
    public void write(int b) throws IOException {
        this.targetStream.write(b);
    }

    @Override
    public void flush() throws IOException {
        super.flush();
        this.targetStream.flush();
    }

    @Override
    public void close() throws IOException {
        super.close();
        this.targetStream.close();
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
        throw new UnsupportedOperationException();
    }
}
