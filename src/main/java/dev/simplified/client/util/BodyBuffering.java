package dev.simplified.client.util;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.client.factory.ApacheClientFactory;
import org.apache.hc.core5.http.io.EofSensorInputStream;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

/**
 * Helpers for materialising HTTP response bodies into {@code byte[]} with a
 * {@code Content-Length}-informed initial allocation.
 * <p>
 * The Feign-provided {@link feign.Util#toByteArray(InputStream)} always opens its
 * {@link ByteArrayOutputStream} with the JDK default 32-byte capacity and additionally
 * allocates a 2 KB read buffer. A 16 KB JSON body therefore triggers a chain of internal
 * array doublings (32 -> 64 -> 128 ...) plus a final {@link Arrays#copyOf}
 * when the bytes are extracted - roughly ten array allocations per medium-size body.
 * <p>
 * When the server advertises a usable {@code Content-Length}, this helper pre-allocates
 * the final {@code byte[]} once and reads the stream directly into it - skipping both the
 * {@link ByteArrayOutputStream} and the intermediate read buffer entirely. The hint is
 * clamped to {@link #MAX_INITIAL_BUFFER} so that a hostile {@code Content-Length} cannot
 * force a gigabyte preallocation; streams exceeding the clamp or lacking a hint fall back
 * to natural {@code ByteArrayOutputStream} growth.
 * <p>
 * A body can be read up to a cap: the read stops one byte past it and aborts the exchange the body
 * arrived on rather than draining the rest, so a caller holding bodies to a cap downloads no more
 * of a larger one than the cap.
 */
@UtilityClass
public final class BodyBuffering {

    /**
     * Default initial capacity for the growable fallback when no usable hint is available.
     */
    private static final int DEFAULT_INITIAL_BUFFER = 1024;

    /**
     * Upper bound on a single pre-allocated buffer regardless of advertised
     * {@code Content-Length}. Bodies that turn out to be larger still drain correctly via
     * {@link ByteArrayOutputStream} growth; the cap exists to keep an unbounded or
     * malicious {@code Content-Length} from preallocating gigabytes.
     */
    private static final int MAX_INITIAL_BUFFER = 64 * 1024;

    /**
     * Read buffer size for the growable fallback drain loop.
     */
    private static final int FALLBACK_READ_BUFFER = 2048;

    /**
     * The canonical {@code Content-Length} header name (case-insensitive lookup).
     */
    private static final @NotNull String CONTENT_LENGTH = "Content-Length";

    /**
     * Drains the given Feign response body into a {@code byte[]}, pre-allocating from the
     * {@code Content-Length} hint extracted from the response headers when possible.
     *
     * @param body the feign response body to drain
     * @param responseHeaders the feign response headers, consulted for {@code Content-Length}
     * @return the fully drained body bytes
     * @throws IOException if the underlying stream throws
     */
    public static byte @NotNull [] toByteArray(@NotNull feign.Response.Body body, @NotNull Map<String, Collection<String>> responseHeaders) throws IOException {
        int hint = parseContentLength(responseHeaders);
        return toByteArray(body.asInputStream(), hint);
    }

    /**
     * Drains the given Feign response body into a {@code byte[]} as
     * {@link #toByteArray(feign.Response.Body, Map)} does, reading no more than one byte past a
     * cap.
     * <p>
     * A body that passes the cap is read no further. When it is read through an
     * {@link EofSensorInputStream}, as every body of the transport {@link ApacheClientFactory}
     * configures is, that stream is aborted: the exchange's connection is closed at once rather
     * than drained for reuse, so none of the body still to come is downloaded, and closing the
     * body then reads at most what the connection had already buffered. The answer is then the
     * body's first {@code maxBytes + 1} bytes, so a caller tells a body past the cap by its
     * length. A body that fits the cap is read to its end. A cap of {@link Long#MAX_VALUE} holds
     * no body to a cap, and the body is read whole.
     *
     * @param body the feign response body to drain
     * @param responseHeaders the feign response headers, consulted for {@code Content-Length}
     * @param maxBytes the cap in bytes
     * @return the body, or its first {@code maxBytes + 1} bytes when it is larger than
     *         {@code maxBytes}
     * @throws IOException if the underlying stream throws
     */
    public static byte @NotNull [] toByteArray(
        @NotNull feign.Response.Body body,
        @NotNull Map<String, Collection<String>> responseHeaders,
        long maxBytes
    ) throws IOException {
        if (maxBytes == Long.MAX_VALUE)
            return toByteArray(body, responseHeaders);

        InputStream in = body.asInputStream();
        long limit = maxBytes + 1;
        byte[] read = toByteArray(new Bounded(in, limit), (int) Math.min(parseContentLength(responseHeaders), limit));

        if (read.length > maxBytes && in instanceof EofSensorInputStream exchange)
            exchange.abort();

        return read;
    }

    /**
     * Drains the given stream into a {@code byte[]}. When {@code sizeHint} is a usable
     * positive value, the stream is read directly into a pre-allocated buffer of that
     * size (clamped to {@link #MAX_INITIAL_BUFFER}); otherwise the helper falls back to a
     * standard {@link ByteArrayOutputStream} drain.
     *
     * @param in the input stream to drain
     * @param sizeHint advisory body length, typically {@code Content-Length};
     *                 non-positive values trigger the growable fallback
     * @return the fully drained bytes
     * @throws IOException if the underlying stream throws
     */
    public static byte @NotNull [] toByteArray(@NotNull InputStream in, int sizeHint) throws IOException {
        if (sizeHint <= 0) return drainGrowable(in, DEFAULT_INITIAL_BUFFER);

        int sized = Math.min(sizeHint, MAX_INITIAL_BUFFER);
        byte[] buffer = new byte[sized];
        int total = 0;
        int read;
        while (total < sized && (read = in.read(buffer, total, sized - total)) != -1)
            total += read;

        if (total < sized) {
            byte[] truncated = new byte[total];
            System.arraycopy(buffer, 0, truncated, 0, total);
            return truncated;
        }

        int peek = in.read();
        if (peek == -1) return buffer;

        return drainOverflow(buffer, peek, in);
    }

    private static byte @NotNull [] drainGrowable(@NotNull InputStream in, int initialCapacity) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(initialCapacity);
        byte[] buffer = new byte[FALLBACK_READ_BUFFER];
        int read;
        while ((read = in.read(buffer)) != -1)
            out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private static byte @NotNull [] drainOverflow(byte @NotNull [] head, int firstExtraByte, @NotNull InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(head.length + FALLBACK_READ_BUFFER);
        out.write(head);
        out.write(firstExtraByte);
        byte[] buffer = new byte[FALLBACK_READ_BUFFER];
        int read;
        while ((read = in.read(buffer)) != -1)
            out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private static int parseContentLength(@NotNull Map<String, Collection<String>> headers) {
        for (Map.Entry<String, Collection<String>> entry : headers.entrySet()) {
            if (!CONTENT_LENGTH.equalsIgnoreCase(entry.getKey())) continue;

            Collection<String> values = entry.getValue();
            if (values == null || values.isEmpty()) return -1;

            try {
                return Integer.parseInt(values.iterator().next().trim());
            } catch (NumberFormatException nfe) {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Stream that answers the end of the stream once a limit of bytes has been read through it,
     * leaving the rest of the stream it reads unread.
     */
    private static final class Bounded extends FilterInputStream {

        /**
         * The bytes still to be read before the limit.
         */
        private long remaining;

        /**
         * Constructs a new {@code Bounded} over a stream, reading at most {@code limit} bytes of it.
         *
         * @param in the stream to read
         * @param limit the most bytes to read
         */
        Bounded(@NotNull InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (this.remaining <= 0)
                return -1;

            int read = super.read();

            if (read != -1)
                this.remaining--;

            return read;
        }

        @Override
        public int read(byte @NotNull [] buffer, int offset, int length) throws IOException {
            if (this.remaining <= 0)
                return -1;

            int read = super.read(buffer, offset, (int) Math.min(length, this.remaining));

            if (read > 0)
                this.remaining -= read;

            return read;
        }

    }

}
