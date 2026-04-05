package dev.simplified.client.interceptor;

import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.route.RouteDiscovery;
import feign.InvocationContext;
import feign.ResponseInterceptor;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * Feign {@link ResponseInterceptor} that extracts server-advertised rate limit headers from
 * every HTTP response and feeds them back into the {@link RateLimitManager}.
 * <p>
 * When a response is received, this interceptor:
 * <ol>
 *   <li>Resolves the originating route by performing a longest-prefix URL match via
 *       {@link RouteDiscovery#findMatchingMetadata(String)}.</li>
 *   <li>Parses standard and common rate limit headers (e.g. {@code RateLimit-Limit},
 *       {@code X-RateLimit-Reset}) from the response using
 *       {@link RateLimit#fromHeaders(java.util.Map)}.</li>
 *   <li>Updates the corresponding bucket in the {@link RateLimitManager} with the
 *       server-supplied limits, ensuring that subsequent client-side checks reflect the
 *       most recent server policy.</li>
 *   <li>Delegates to the next interceptor in the chain.</li>
 * </ol>
 * <p>
 * This class is the server-side complement to {@link InternalRequestInterceptor}, which
 * enforces the client-side rate limit before each request. Together they form a closed
 * feedback loop that keeps client-tracked quotas synchronized with server-advertised quotas.
 * <p>
 * This class is instantiated internally by {@link dev.simplified.client.Client} during Feign
 * builder configuration and is not intended for direct use by application code.
 *
 * @see InternalRequestInterceptor
 * @see RateLimitManager
 * @see RouteDiscovery
 * @see RateLimit#fromHeaders(java.util.Map)
 */
@RequiredArgsConstructor
public final class InternalResponseInterceptor implements ResponseInterceptor {

    /** The manager responsible for tracking and updating per-route rate limits. */
    private final @NotNull RateLimitManager rateLimitManager;

    /** The discovery engine used to match response URLs back to their route metadata. */
    private final @NotNull RouteDiscovery routeDiscovery;

    /**
     * {@inheritDoc}
     */
    @Override
    public Object intercept(@NotNull InvocationContext invocationContext, @NotNull Chain chain) throws Exception {
        feign.Response response = invocationContext.response();
        String routeId = extractRouteId(response);

        RateLimit.fromHeaders(response.headers()).ifPresent(serverLimit ->
            this.rateLimitManager.updateRateLimit(routeId, serverLimit)
        );

        return chain.next(invocationContext);
    }

    /**
     * Extracts the route identifier from the internal request header stashed by
     * {@link InternalRequestInterceptor}. Falls back to longest-prefix URL matching
     * if the header is absent.
     *
     * @param response the Feign response whose originating request carries the route header
     * @return the route identifier string
     */
    private @NotNull String extractRouteId(@NotNull feign.Response response) {
        Collection<String> values = response.request().headers().get(InternalRequestInterceptor.ROUTE_ID_HEADER);

        if (values != null && !values.isEmpty())
            return values.iterator().next();

        return this.routeDiscovery.findMatchingMetadata(response.request().url()).getRoute();
    }

}
