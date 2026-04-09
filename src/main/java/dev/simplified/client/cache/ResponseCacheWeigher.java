package dev.simplified.client.cache;

import com.github.benmanes.caffeine.cache.Weigher;
import dev.simplified.client.response.Response;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Caffeine {@link Weigher} that sums the approximate heap footprint of every cached
 * variant in a {@link CacheKey.UrlKey} bucket, enabling byte-based eviction in
 * {@link ResponseCache}.
 * <p>
 * Per-entry weight is computed as:
 * <ul>
 *   <li>the length of the raw response body bytes captured at decode time, or {@code 0}
 *       if absent</li>
 *   <li>an approximation of the stored response headers' wire size
 *       ({@code sum(key.length() + value.length())})</li>
 *   <li>a fixed {@code 512} byte overhead for the Java object graph (envelope,
 *       {@link dev.simplified.client.response.NetworkDetails}, request record)</li>
 * </ul>
 * <p>
 * The raw-body length dominates real-world usage, so the header and overhead terms are
 * deliberately rough approximations rather than exact byte counts. The total is
 * saturating-clamped to {@link Integer#MAX_VALUE} because Caffeine's weigher contract
 * returns an {@code int}.
 *
 * @see ResponseCache
 */
public final class ResponseCacheWeigher implements Weigher<CacheKey.UrlKey, java.util.concurrent.ConcurrentMap<CacheKey.VaryFingerprint, Response.Cached<?>>> {

    /** Fixed object-graph overhead applied to every cached variant. */
    private static final long OBJECT_OVERHEAD_BYTES = 512L;

    @Override
    public int weigh(
        @NotNull CacheKey.UrlKey key,
        @NotNull java.util.concurrent.ConcurrentMap<CacheKey.VaryFingerprint, Response.Cached<?>> variants
    ) {
        long total = 0L;

        for (Response.Cached<?> cached : variants.values()) {
            total += cached.getRawBody().map(bytes -> (long) bytes.length).orElse(0L);
            total += estimateHeaderBytes(cached.getHeaders());
            total += OBJECT_OVERHEAD_BYTES;
        }

        if (total <= 0L)
            return 0;

        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    /**
     * Approximates the wire size of a response's stored headers as the sum of key and
     * value character lengths. Multi-valued headers contribute the sum of all values.
     *
     * @param headers the response headers to measure
     * @return the approximate header byte footprint
     */
    private static long estimateHeaderBytes(@NotNull ConcurrentMap<String, ConcurrentList<String>> headers) {
        long bytes = 0L;

        for (Map.Entry<String, ConcurrentList<String>> entry : headers.entrySet()) {
            bytes += entry.getKey().length();

            for (String value : entry.getValue())
                bytes += value.length();
        }

        return bytes;
    }

}
