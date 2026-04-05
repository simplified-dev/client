package dev.simplified.client.route;

import dev.simplified.client.ratelimit.RateLimit;
import org.jetbrains.annotations.NotNull;

/**
 * Provider of a dynamic route and its associated rate limit configuration.
 * <p>
 * Implementations supply the host (and optional base path) for an API endpoint at runtime,
 * enabling route selection that cannot be expressed as a compile-time constant in a
 * {@link Route @Route} annotation. Typical implementations are enum constants referenced by
 * a {@link DynamicRoute @DynamicRoute}-annotated custom annotation.
 * <p>
 * Routes must be returned without a protocol prefix (e.g. {@code "api.sbs.dev/v2"}, not
 * {@code "https://api.sbs.dev/v2"}). The {@code https://} scheme is prepended automatically
 * by {@link RouteDiscovery.Metadata#getFullUrl()}.
 *
 * @see DynamicRoute
 * @see RouteDiscovery
 * @see RateLimit
 */
public interface DynamicRouteProvider {

    /** The route string consisting of a host and optional base path, without a protocol prefix. */
    @NotNull String getRoute();

    /**
     * Returns the rate limit policy for this route.
     * <p>
     * The default implementation returns {@link RateLimit#UNLIMITED}, imposing no client-side
     * throttling. Override this method to declare a per-route request cap.
     *
     * @return the {@link RateLimit} configuration for this route
     */
    default @NotNull RateLimit getRateLimit() {
        return RateLimit.UNLIMITED;
    }

    /**
     * Returns a unique identifier used for rate limit bucket tracking.
     * <p>
     * The default implementation returns the route string itself. Overriding this method
     * allows multiple distinct routes to share a single rate limit bucket when they are
     * governed by the same server-side quota.
     *
     * @return the bucket identifier, typically the route string
     */
    default @NotNull String getBucketId() {
        return this.getRoute();
    }

}
