package dev.simplified.client.interceptor;

import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.client.Client;
import dev.simplified.client.cache.CachingFeignClient;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.route.RouteDiscovery;
import feign.MethodMetadata;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.jetbrains.annotations.NotNull;

/**
 * Feign {@link RequestInterceptor} that applies route resolution and client-side rate limit
 * enforcement to every outbound request.
 * <p>
 * For each {@link RequestTemplate}, this interceptor performs the following steps in order:
 * <ol>
 *   <li>Resolves the target {@link RouteDiscovery.Metadata} for the invoked endpoint method
 *       via {@link RouteDiscovery}.</li>
 *   <li>Resolves the bucket the endpoint's requests count against through
 *       {@link RateLimitManager#getBucketKey(String, String)}: the quota the endpoint's latest
 *       response named, or the route's own bucket.</li>
 *   <li>Checks whether that bucket is currently rate-limited using {@link RateLimitManager}.
 *       If the limit has been reached, a {@link RateLimitException} is thrown to abort the
 *       request before it leaves the client.</li>
 *   <li>Records the request in the rate limit tracker so future calls can be evaluated
 *       against the configured quota.</li>
 *   <li>Numbers the request with {@link RateLimitManager#nextSequence()}, replacing any number
 *       an earlier attempt of the same template carried, so {@link InternalResponseInterceptor}
 *       can tell a late response from the response to a later request.</li>
 *   <li>Replaces the placeholder target URL on the template with the real HTTPS URL
 *       obtained from the route metadata.</li>
 * </ol>
 * <p>
 * HTTP cache concerns (conditional revalidation header attachment, fresh-hit
 * short-circuiting) live in
 * {@link CachingFeignClient CachingFeignClient}, which wraps
 * the underlying Feign client below this interceptor and handles {@code If-None-Match} /
 * {@code If-Modified-Since} attachment itself.
 * <p>
 * This class is instantiated internally by {@link Client} during Feign
 * builder configuration and is not intended for direct use by application code.
 *
 * @see InternalResponseInterceptor
 * @see RouteDiscovery
 * @see RateLimitManager
 * @see RateLimitException
 * @see dev.simplified.client.cache.CachingFeignClient
 */
@RequiredArgsConstructor
public final class InternalRequestInterceptor implements RequestInterceptor {

    /**
     * The manager responsible for tracking and enforcing per-route rate limits.
     */
    private final @NotNull RateLimitManager rateLimitManager;

    /**
     * The discovery engine that maps endpoint methods to their route metadata. Each
     * {@link RouteDiscovery.Metadata} carries a precomputed
     * {@linkplain RouteDiscovery.Metadata#getBucketKey() bucket key} that this interceptor hands
     * to the manager directly - no per-request composition.
     */
    private final @NotNull RouteDiscovery routeDiscovery;

    /**
     * Internal header key used to carry the request's {@linkplain RateLimitManager#nextSequence()
     * sequence number} from request to response interceptor.
     */
    static final @NotNull String SEQUENCE_HEADER = NetworkDetails.INTERNAL_HEADER_PREFIX + "Request-Sequence";

    /**
     * {@inheritDoc}
     */
    @Override
    public void apply(@NotNull RequestTemplate template) {
        MethodMetadata endpoint = template.methodMetadata();
        RouteDiscovery.Metadata routeMetadata = this.routeDiscovery.getMetadata(endpoint.method());
        String bucketKey = this.rateLimitManager.getBucketKey(routeMetadata.getBucketKey(), endpoint.configKey());
        long now = System.currentTimeMillis();

        if (this.rateLimitManager.isRateLimited(bucketKey, routeMetadata.getRateLimit(), now))
            throw new RateLimitException(template, routeMetadata);

        this.rateLimitManager.trackRequest(bucketKey, routeMetadata.getRateLimit(), now);

        template.removeHeader(SEQUENCE_HEADER);
        template.header(SEQUENCE_HEADER, Long.toString(this.rateLimitManager.nextSequence()));
        template.target(routeMetadata.getFullUrl());
    }

}
