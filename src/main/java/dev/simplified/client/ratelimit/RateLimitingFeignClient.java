package dev.simplified.client.ratelimit;

import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.client.cache.CachingFeignClient;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.interceptor.InternalRequestInterceptor;
import dev.simplified.client.interceptor.InternalResponseInterceptor;
import dev.simplified.client.route.RouteDiscovery;
import feign.Client;
import feign.MethodMetadata;
import feign.Request;
import feign.RequestTemplate;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Feign {@link Client} wrapper that enforces the client-side rate limit on every request it
 * sends and counts each one against its bucket.
 * <p>
 * Sits below {@link CachingFeignClient}, wrapping the transport, so only a request that leaves
 * the process reaches it: a request the cache answers without revalidation is neither refused
 * nor counted, while a conditional request revalidating a stale entry is both, as a request
 * the cache cannot answer is. For each request it:
 * <ol>
 *   <li>Resolves the request's {@link RouteDiscovery.Metadata route} from the Feign template
 *       the request was built from, falling back to a longest-prefix URL match via
 *       {@link RouteDiscovery#findMatchingMetadata(String)} for a request without one, as
 *       {@link InternalResponseInterceptor} resolves the route of the response.</li>
 *   <li>Resolves the bucket the request counts against through
 *       {@link RateLimitManager#getBucketKey(String, String)}: the quota the endpoint's latest
 *       response named, or the route's own bucket; a request without an endpoint resolves
 *       through {@link RateLimitManager#getBucketKey(String)}.</li>
 *   <li>Admits the request and records it against that bucket in one atomic step, through
 *       {@link RateLimitManager#tryAcquire(String, RateLimit, long)}, so requests sent together
 *       are admitted no further than the bucket's limit allows; throws a
 *       {@link RateLimitException} before the request is sent when the bucket is currently
 *       rate-limited.</li>
 *   <li>Sends the admitted request through the delegate.</li>
 * </ol>
 * <p>
 * The {@link InternalRequestInterceptor} resolves the request's target URL and numbers it
 * before the cache is consulted; this client only gates and counts the requests the cache
 * sends on.
 * <p>
 * This class is instantiated internally by {@link dev.simplified.client.Client} during Feign
 * builder configuration and is not intended for direct use by application code.
 *
 * @see RateLimitManager
 * @see RateLimitException
 * @see CachingFeignClient
 */
@RequiredArgsConstructor
public final class RateLimitingFeignClient implements Client {

    /**
     * The underlying Feign client every admitted request is sent through.
     */
    private final @NotNull Client delegate;

    /**
     * The manager responsible for tracking and enforcing per-route rate limits.
     */
    private final @NotNull RateLimitManager rateLimitManager;

    /**
     * The discovery engine that maps a request's endpoint method, or failing that its URL, to its
     * route metadata.
     */
    private final @NotNull RouteDiscovery routeDiscovery;

    /**
     * {@inheritDoc}
     */
    @Override
    public feign.Response execute(@NotNull Request request, @NotNull Request.Options options) throws IOException {
        RequestTemplate template = request.requestTemplate();
        MethodMetadata endpoint = template != null ? template.methodMetadata() : null;
        boolean known = endpoint != null && endpoint.method() != null;
        RouteDiscovery.Metadata route = known
            ? this.routeDiscovery.getMetadata(endpoint.method())
            : this.routeDiscovery.findMatchingMetadata(request.url());
        String bucketKey = known
            ? this.rateLimitManager.getBucketKey(route.getBucketKey(), endpoint.configKey())
            : this.rateLimitManager.getBucketKey(route.getBucketKey());

        if (!this.rateLimitManager.tryAcquire(bucketKey, route.getRateLimit(), System.currentTimeMillis()))
            throw new RateLimitException(request, route);

        return this.delegate.execute(request, options);
    }

}
