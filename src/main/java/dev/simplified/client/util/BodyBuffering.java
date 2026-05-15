package dev.simplified.client.util;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
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

}
