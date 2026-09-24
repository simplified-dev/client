package dev.simplified.client.interceptor;

import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.client.Client;
import dev.simplified.client.cache.CachingFeignClient;
import dev.simplified.client.cache.ResponseCache;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.route.RouteDiscovery;
import feign.InvocationContext;
import feign.MethodMetadata;
import feign.ResponseInterceptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * Feign {@link ResponseInterceptor} that extracts server-advertised rate limit headers from
 * every HTTP response and feeds them back into the {@link RateLimitManager}.
 * <p>
 * When a response is received, this interceptor:
 * <ol>
 *   <li>Resolves the originating endpoint and route from the Feign template the request was
 *       built from, falling back to a longest-prefix URL match via
 *       {@link RouteDiscovery#findMatchingMetadata(String)} for a request without one.</li>
 *   <li>Applies the standard and common rate limit headers (e.g. {@code RateLimit-Limit},
 *       {@code X-RateLimit-Reset}, {@code X-RateLimit-Remaining}) through
 *       {@link RateLimitManager#updateFromHeaders(RouteDiscovery.Metadata, String, Map, long, long)},
 *       so the bucket's window ends when the server's quota resets and its count follows the
 *       server's remaining figure. The bucket is the quota the response names in
 *       {@code X-RateLimit-Resource}, or the route's own. The sequence number
 *       {@link InternalRequestInterceptor} stashed on the request orders the response, so one
 *       that lands after the response to a later request is ignored.</li>
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
     * The discovery engine that maps a response's endpoint method, or failing that its URL, back
     * to the route metadata of the request it answered.
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
     * Applies a response's rate-limit headers to the bucket of the quota it counted against.
     *
     * @param response the response to read
     * @param now the epoch-millisecond timestamp the response was received at
     * @see #serverHeaders(Map)
     */
    void recordServerLimit(@NotNull feign.Response response, long now) {
        Map<String, Collection<String>> headers = serverHeaders(response.headers());

        if (headers == null)
            return;

        feign.Request request = response.request();
        MethodMetadata endpoint = request.requestTemplate() != null ? request.requestTemplate().methodMetadata() : null;
        boolean known = endpoint != null && endpoint.method() != null;
        RouteDiscovery.Metadata route = known
            ? this.routeDiscovery.getMetadata(endpoint.method())
            : this.routeDiscovery.findMatchingMetadata(request.url());

        this.rateLimitManager.updateFromHeaders(route, known ? endpoint.configKey() : null, headers, now, this.extractSequence(request));
    }

    /**
     * Reads the sequence number {@link InternalRequestInterceptor} gave a request.
     * <p>
     * A request the interceptor did not number is given the next number now, which orders its
     * response as the newest the manager has seen.
     *
     * @param request the request whose sequence header to read
     * @return the request's sequence number
     */
    private long extractSequence(@NotNull feign.Request request) {
        Collection<String> values = request.headers().get(InternalRequestInterceptor.SEQUENCE_HEADER);

        if (values != null && !values.isEmpty()) {
            try {
                return Long.parseLong(values.iterator().next());
            } catch (NumberFormatException ignore) { }
        }

        return this.rateLimitManager.nextSequence();
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

}
