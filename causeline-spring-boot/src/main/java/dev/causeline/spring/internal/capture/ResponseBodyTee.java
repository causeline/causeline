// SPDX-License-Identifier: Apache-2.0
package dev.causeline.spring.internal.capture;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Lets the response through untouched while keeping a copy of its first {@code limit} bytes for
 * display. Unlike Spring's ContentCachingResponseWrapper nothing is held back: bytes reach the
 * client as soon as the application writes them, so streaming and large downloads work as usual,
 * and memory use is bounded by the limit.
 */
public final class ResponseBodyTee extends HttpServletResponseWrapper {

    private final int limit;
    private final ByteArrayOutputStream copy = new ByteArrayOutputStream();
    private long total;
    private ServletOutputStream stream;
    private PrintWriter writer;

    public ResponseBodyTee(HttpServletResponse response, int limit) {
        super(response);
        this.limit = limit;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (stream == null) {
            ServletOutputStream target = super.getOutputStream();
            stream = new ServletOutputStream() {
                @Override
                public void write(int b) throws IOException {
                    target.write(b);
                    keep(new byte[] {(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    target.write(bytes, offset, length);
                    keep(bytes, offset, length);
                }

                @Override
                public void flush() throws IOException {
                    target.flush();
                }

                @Override
                public void close() throws IOException {
                    target.close();
                }

                @Override
                public boolean isReady() {
                    return target.isReady();
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                    target.setWriteListener(listener);
                }
            };
        }
        return stream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (writer == null) {
            // Text written through the writer goes through the same tee, encoded as the response says.
            writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), charset()), true);
        }
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        super.flushBuffer();
    }

    /** Pushes out anything still buffered in the writer. Call once the application is done. */
    public void finish() {
        if (writer != null) {
            writer.flush();
        }
    }

    public byte[] copy() {
        return copy.toByteArray();
    }

    /** Bytes the application wrote in total, including those beyond the copy limit. */
    public long totalBytes() {
        return total;
    }

    public Charset charset() {
        String name = getCharacterEncoding();
        try {
            return name == null ? StandardCharsets.UTF_8 : Charset.forName(name);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private void keep(byte[] bytes, int offset, int length) {
        total += length;
        int room = limit - copy.size();
        if (room > 0) {
            copy.write(bytes, offset, Math.min(room, length));
        }
    }
}
