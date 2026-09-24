package dev.simplified.client.interceptor;

import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.client.Client;
import dev.simplified.client.cache.CachingFeignClient;
import dev.simplified.client.cache.ResponseCache;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.route.RouteDiscovery;
import feign.InvocationContext;
import feign.ResponseInterceptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Feign {@link ResponseInterceptor} that extracts server-advertised rate limit headers from
 * every HTTP response and feeds them back into the {@link RateLimitManager}.
 * <p>
 * When a response is received, this interceptor:
 * <ol>
 *   <li>Resolves the originating route by reading the route identifier header stashed on
 *       the request by {@link InternalRequestInterceptor}, falling back to a longest-prefix
 *       URL match via {@link RouteDiscovery#findMatchingMetadata(String)}.</li>
 *   <li>Applies the standard and common rate limit headers (e.g. {@code RateLimit-Limit},
 *       {@code X-RateLimit-Reset}, {@code X-RateLimit-Remaining}) to the corresponding bucket
 *       through {@link RateLimitManager#updateFromHeaders(String, Map, long, long)}, so the
 *       bucket's window ends when the server's quota resets and its count follows the server's
 *       remaining figure. The sequence number {@link InternalRequestInterceptor} stashed on the
 *       request orders the response, so one that lands after the response to a later request
 *       is ignored; a request without one is applied through
 *       {@link RateLimitManager#updateFromHeaders(String, Map, long)}.</li>
 *   <li>Delegates to the next interceptor in the chain.</li>
 * </ol>
 * <p>
 * This class is the server-side complement to {@link InternalRequestInterceptor}, which
 * enforces the client-side rate limit before each request. Together they form a closed
 * feedback loop that keeps client-tracked quotas synchronized with server-advertised quotas.
 * <p>
 * HTTP cache semantics (fresh-hit short-circuiting, conditional revalidation, 304 header
 * merge) live in {@link CachingFeignClient CachingFeignClient}, which is the client Feign
 * executes requests through, so the cache-hit responses it synthesizes reach this interceptor
 * like live ones. A fresh or stale-if-error replay carries only the rate-limit headers stored
 * with the cache entry, so it is skipped. A replay answering a {@code 304 Not Modified} is
 * applied through the headers the server sent with the 304, exactly as a live response is.
 * <p>
 * This class is instantiated internally by {@link Client} during Feign
 * builder configuration and is not intended for direct use by application code.
 *
 * @see InternalRequestInterceptor
 * @see RateLimitManager
 * @see RouteDiscovery
 * @see RateLimit#fromHeaders(Map, long)
 */
@RequiredArgsConstructor
public final class InternalResponseInterceptor implements ResponseInterceptor {

    /**
     * The manager responsible for tracking and updating per-route rate limits.
     */
    private final @NotNull RateLimitManager rateLimitManager;

    /**
     * The discovery engine used to match response URLs back to their route metadata. Used only on
     * the URL-match fallback path; the normal path reads the already-composed key directly from
     * {@link InternalRequestInterceptor#ROUTE_ID_HEADER}.
     */
    private final @NotNull RouteDiscovery routeDiscovery;

    /**
     * {@inheritDoc}
     */
    @Override
    public Object intercept(@NotNull InvocationContext invocationContext, @NotNull Chain chain) throws Exception {
        this.recordServerLimit(invocationContext.response(), System.currentTimeMillis());
        return chain.next(invocationContext);
    }

    /**
     * Applies a response's rate-limit headers to the bucket of the route it answered.
     *
     * @param response the response to read
     * @param now the epoch-millisecond timestamp the response was received at
     * @see #serverHeaders(Map)
     */
    void recordServerLimit(@NotNull feign.Response response, long now) {
        Map<String, Collection<String>> headers = serverHeaders(response.headers());

        if (headers == null)
            return;

        String bucketKey = this.extractBucketKey(response);
        OptionalLong sequence = extractSequence(response);

        if (sequence.isPresent())
            this.rateLimitManager.updateFromHeaders(bucketKey, headers, now, sequence.getAsLong());
        else
            this.rateLimitManager.updateFromHeaders(bucketKey, headers, now);
    }

    /**
     * Reads the sequence number {@link InternalRequestInterceptor} gave the request a response
     * answered.
     *
     * @param response the Feign response whose originating request carries the sequence header
     * @return the sequence number, or empty for a request the interceptor did not number
     */
    private static @NotNull OptionalLong extractSequence(@NotNull feign.Response response) {
        Collection<String> values = response.request().headers().get(InternalRequestInterceptor.SEQUENCE_HEADER);

        if (values == null || values.isEmpty())
            return OptionalLong.empty();

        try {
            return OptionalLong.of(Long.parseLong(values.iterator().next()));
        } catch (NumberFormatException ex) {
            return OptionalLong.empty();
        }
    }

    /**
     * Selects the headers of a response that the server sent as it answered.
     * <p>
     * A live response's headers all are. A response synthesized by {@link CachingFeignClient},
     * marked with {@link ResponseCache#CACHE_HIT_HEADER}, replays headers stored with the cache
     * entry, which describe the server's quota when the entry was cached: a delta reset would be
     * anchored to the wrong instant and a remaining count would roll the bucket back. A fresh or
     * stale-if-error replay therefore has none. A replay answering a {@code 304 Not Modified}
     * has exactly the headers {@link ResponseCache#REVALIDATED_HEADER} names, which hold the
     * values the server sent with the 304.
     *
     * @param headers the response headers
     * @return the headers the server sent, or {@code null} for a fresh or stale-if-error replay
     */
    private static @Nullable Map<String, Collection<String>> serverHeaders(@NotNull Map<String, Collection<String>> headers) {
        if (!headers.containsKey(ResponseCache.CACHE_HIT_HEADER))
            return headers;

        Collection<String> revalidated = headers.get(ResponseCache.REVALIDATED_HEADER);

        if (revalidated == null)
            return null;

        Map<String, Collection<String>> sent = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        for (String name : revalidated) {
            Collection<String> values = headers.get(name);

            if (values != null)
                sent.put(name, values);
        }

        return sent;
    }

    /**
     * Extracts the rate-limit bucket key from the internal request header stashed by
     * {@link InternalRequestInterceptor}. Falls back to longest-prefix URL matching when the header
     * is absent, reading the precomputed bucket key off the resolved metadata.
     *
     * @param response the Feign response whose originating request carries the route header
     * @return the bucket key string
     */
    private @NotNull String extractBucketKey(@NotNull feign.Response response) {
        Collection<String> values = response.request().headers().get(InternalRequestInterceptor.ROUTE_ID_HEADER);

        if (values != null && !values.isEmpty())
            return values.iterator().next();

        return this.routeDiscovery.findMatchingMetadata(response.request().url()).getBucketKey();
    }

}
