package dev.simplified.client.route;

import dev.simplified.client.ratelimit.RateLimitConfig;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation that declares a static route (host and optional base path) for a Feign endpoint interface.
 * <p>
 * When placed on a type, it defines the default route for all methods in that interface.
 * When placed on a method, it overrides the type-level default for that single method.
 * {@link RouteDiscovery} scans for this annotation during client initialization to resolve
 * the full URL and associated {@link RateLimitConfig} for each endpoint method.
 * <p>
 * The value should be specified without a protocol prefix; the {@code https://} scheme is
 * prepended automatically by {@link RouteDiscovery.Metadata#getFullUrl()}.
 * <pre>{@code
 * @Route("api.sbs.dev")              // simple host
 * @Route("api.sbs.dev/v2")           // host with base path
 * @Route("sessionserver.mojang.com") // different subdomain
 * }</pre>
 *
 * @see DynamicRoute
 * @see RouteDiscovery
 * @see RateLimitConfig
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Route {

    /**
     * The route string consisting of a host and optional base path, without a protocol prefix
     * (e.g. {@code "api.sbs.dev/v2"}, not {@code "https://api.sbs.dev/v2"}).
     */
    @NotNull String value();

    /**
     * The {@link RateLimitConfig} applied to this route, defaulting to an unlimited configuration.
     *
     * @see RateLimitConfig
     */
    @NotNull RateLimitConfig rateLimit() default @RateLimitConfig(unlimited = true);

}