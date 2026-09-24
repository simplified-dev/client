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

import java.util.Collection;
import java.util.Map;

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
 *       through {@link RateLimitManager#updateFromHeaders(String, Map, long)}, so the bucket's
 *       window ends when the server's quota resets and its count follows the server's
 *       remaining figure.</li>
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
 * like live ones. Their rate-limit headers are the ones stored with the cache entry, so they
 * are skipped.
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
     * <p>
     * A response synthesized by {@link CachingFeignClient}, marked with
     * {@link ResponseCache#CACHE_HIT_HEADER}, is skipped. Its rate-limit headers were stored
     * with the cache entry and describe the server's quota at that moment: a delta reset would
     * be anchored to the wrong instant and a remaining count would roll the bucket back.
     *
     * @param response the response to read
     * @param now the epoch-millisecond timestamp the response was received at
     */
    void recordServerLimit(@NotNull feign.Response response, long now) {
        if (response.headers().containsKey(ResponseCache.CACHE_HIT_HEADER))
            return;

        this.rateLimitManager.updateFromHeaders(this.extractBucketKey(response), response.headers(), now);
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
