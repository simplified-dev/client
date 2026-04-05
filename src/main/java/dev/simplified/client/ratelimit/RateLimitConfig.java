package dev.simplified.client.ratelimit;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.temporal.ChronoUnit;

/**
 * Annotation-based rate-limit configuration applied to route annotations
 * such as {@link dev.simplified.client.route.Route} to declare the quota and
 * window for a given API endpoint.
 * <p>
 * At startup, {@link RateLimit#fromAnnotation(RateLimitConfig)} converts this
 * annotation into a {@link RateLimit} policy instance that the
 * {@link RateLimitManager} uses to enforce request quotas.
 * <p>
 * If {@link #unlimited()} is set to {@code true}, all other attribute values
 * are ignored and the resulting {@link RateLimit} will be
 * {@link RateLimit#UNLIMITED}.
 *
 * @see RateLimit#fromAnnotation(RateLimitConfig)
 * @see RateLimitManager
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimitConfig {

    /**
     * Maximum number of requests allowed within the time window, defaulting to
     * {@link Long#MAX_VALUE} (effectively unlimited).
     */
    long limit() default Long.MAX_VALUE;

    /** Numeric duration of the rate-limit window, measured in {@link #unit()}. */
    long window() default 600;

    /** Temporal unit for the {@link #window()} duration, defaulting to {@link ChronoUnit#SECONDS}. */
    @NotNull ChronoUnit unit() default ChronoUnit.SECONDS;

    /**
     * Whether the annotated route has no effective rate limit; when {@code true},
     * {@link #limit()}, {@link #window()}, and {@link #unit()} are ignored.
     */
    boolean unlimited() default false;

}
