package dev.simplified.client.interceptor;

import dev.simplified.client.Client;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.route.RouteDiscovery;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;

/**
 * Feign {@link RequestInterceptor} that applies route resolution and client-side rate limit
 * enforcement to every outbound request.
 * <p>
 * For each {@link RequestTemplate}, this interceptor performs the following steps in order:
 * <ol>
 *   <li>Resolves the target {@link RouteDiscovery.Metadata} for the invoked endpoint method
 *       via {@link RouteDiscovery}.</li>
 *   <li>Checks whether the route is currently rate-limited using {@link RateLimitManager}.
 *       If the limit has been reached, a {@link RateLimitException} is thrown to abort the
 *       request before it leaves the client.</li>
 *   <li>Records the request in the rate limit tracker so future calls can be evaluated
 *       against the configured quota.</li>
 *   <li>Replaces the placeholder target URL on the template with the real HTTPS URL
 *       obtained from the route metadata.</li>
 * </ol>
 * <p>
 * HTTP cache concerns (conditional revalidation header attachment, fresh-hit
 * short-circuiting) live in
 * {@link dev.simplified.client.cache.CachingFeignClient CachingFeignClient}, which wraps
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

    /** The manager responsible for tracking and enforcing per-route rate limits. */
    private final @NotNull RateLimitManager rateLimitManager;

    /** The discovery engine that maps endpoint methods to their route metadata. */
    private final @NotNull RouteDiscovery routeDiscovery;

    /** Internal header key used to carry the resolved route identifier from request to response interceptor. */
    static final @NotNull String ROUTE_ID_HEADER = NetworkDetails.INTERNAL_HEADER_PREFIX + "Route-Id";

    /**
     * {@inheritDoc}
     */
    @Override
    public void apply(@NotNull RequestTemplate template) {
        Method method = template.methodMetadata().method();
        RouteDiscovery.Metadata routeMetadata = this.routeDiscovery.getMetadata(method);
        long now = System.currentTimeMillis();

        if (this.rateLimitManager.isRateLimited(routeMetadata.getRoute(), routeMetadata.getRateLimit(), now))
            throw new RateLimitException(template, routeMetadata);

        this.rateLimitManager.trackRequest(routeMetadata.getRoute(), routeMetadata.getRateLimit(), now);

        template.header(ROUTE_ID_HEADER, routeMetadata.getRoute());
        template.target(routeMetadata.getFullUrl());
    }

}
