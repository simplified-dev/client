package dev.simplified.client.ratelimit;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable rate-limit policy describing the maximum number of requests allowed
 * within a sliding time window.
 * <p>
 * This class models the <em>policy</em> (quota and window duration) rather than
 * the live request count, which is tracked externally by {@link RateLimitBucket}.
 * Its fields are aligned with the IETF {@code RateLimit} header fields draft:
 * <ul>
 *   <li>{@code RateLimit-Limit} maps to {@link #limit}</li>
 *   <li>{@code RateLimit-Reset} maps to {@link #resetSeconds}</li>
 * </ul>
 * <p>
 * Instances can be obtained in several ways:
 * <ul>
 *   <li>{@link #builder()} - fluent programmatic construction</li>
 *   <li>{@link #fromAnnotation(RateLimitConfig)} - from a {@link RateLimitConfig} annotation</li>
 *   <li>{@link #fromHeaders(Map)} - parsed from HTTP response headers</li>
 *   <li>{@link #unlimited()} or {@link #UNLIMITED} - a sentinel representing no effective limit</li>
 * </ul>
 *
 * @see RateLimitConfig
 * @see RateLimitBucket
 * @see RateLimitManager
 */
@Getter
public final class RateLimit {

    /**
     * Shared sentinel instance representing an unlimited rate-limit policy.
     * <p>
     * Equivalent to calling {@link #unlimited()} but avoids allocating a new
     * object on each access.
     */
    public static final @NotNull RateLimit UNLIMITED = new RateLimit(Long.MAX_VALUE, Long.MAX_VALUE / 1000L, true);

    /** Maximum number of requests permitted within a single window, mirroring {@code RateLimit-Limit}. */
    private final long limit;

    /** Window duration in seconds, mirroring {@code RateLimit-Reset} (delta seconds until quota resets). */
    private final long resetSeconds;

    /** Normalized window duration in milliseconds, pre-computed for efficient elapsed-time comparisons. */
    private final long windowDurationMillis;

    /** Whether this instance represents an effectively unlimited policy on the client side. */
    private final boolean unlimited;

    /**
     * Primary constructor used by all factory methods.
     *
     * @param limit maximum requests allowed in the window
     * @param resetSeconds window length or server-advertised reset interval in seconds
     * @param unlimited {@code true} to mark this instance as having no effective limit
     */
    private RateLimit(long limit, long resetSeconds, boolean unlimited) {
        this.limit = limit;
        this.resetSeconds = resetSeconds;
        this.unlimited = unlimited;

        // For "unlimited", we still give a very large window for consistency
        long effectiveReset = unlimited ? Long.MAX_VALUE / 1000L : Math.max(resetSeconds, 1L);
        this.windowDurationMillis = effectiveReset * 1000L;
    }

    /**
     * Creates a new {@link Builder} for constructing a {@link RateLimit} instance
     * with fluent configuration.
     *
     * @return a new builder pre-configured with sensible defaults (600 requests per 10 minutes)
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Constructs a client-configured rate limit from an explicit quota and
     * window specification.
     * <p>
     * The window duration is converted to seconds via
     * {@link ChronoUnit#getDuration()}.
     *
     * @param limit maximum requests allowed in the window
     * @param window the number of {@code unit}s that define the window duration
     * @param unit the temporal unit of the window (e.g. {@link ChronoUnit#SECONDS})
     */
    public RateLimit(long limit, long window, @NotNull ChronoUnit unit) {
        this(
            limit,
            unit.getDuration().multipliedBy(window).getSeconds(),
            false
        );
    }

    /**
     * Creates a {@link RateLimit} from a {@link RateLimitConfig} annotation.
     * <p>
     * If the annotation's {@link RateLimitConfig#unlimited()} flag is set, the
     * {@link #UNLIMITED} sentinel is returned; otherwise a new instance is
     * constructed from the annotation's {@code limit}, {@code window}, and
     * {@code unit} attributes.
     *
     * @param config the rate-limit configuration annotation to convert
     * @return the corresponding {@link RateLimit} instance
     */
    public static @NotNull RateLimit fromAnnotation(@NotNull RateLimitConfig config) {
        if (config.unlimited())
            return RateLimit.UNLIMITED;

        return new RateLimit(config.limit(), config.window(), config.unit());
    }

    /**
     * Parses rate-limit metadata from HTTP response headers, supporting
     * multiple common header formats.
     * <p>
     * The following header families are checked in order of precedence:
     * <ol>
     *   <li><b>RFC draft</b>: {@code RateLimit-Limit} and {@code RateLimit-Reset}</li>
     *   <li><b>Common {@code X-} prefixed</b>: {@code X-RateLimit-Limit} and
     *       {@code X-RateLimit-Reset}</li>
     * </ol>
     * <p>
     * If neither format provides both a limit and a reset value, an empty
     * {@link Optional} is returned, indicating that rate-limit information is
     * not available in the response.
     *
     * @param headers the HTTP response headers to inspect
     * @return an {@link Optional} containing the parsed {@link RateLimit}, or
     *         empty if insufficient header information is present
     */
    public static @NotNull Optional<RateLimit> fromHeaders(@NotNull Map<String, Collection<String>> headers) {
        // Try standard headers first (RFC draft)
        Optional<Long> limit = getFirstLong(headers, "RateLimit-Limit", "ratelimit-limit");
        Optional<Long> reset = getFirstLong(headers, "RateLimit-Reset", "ratelimit-reset");

        // Fall back to X- prefixed headers (common)
        if (limit.isEmpty()) {
            limit = getFirstLong(headers, "X-RateLimit-Limit", "x-ratelimit-limit");
            reset = getFirstLong(headers, "X-RateLimit-Reset", "x-ratelimit-reset");
        }

        // If we don't have enough info to build a RateLimit, treat as "no info"
        if (limit.isEmpty() || reset.isEmpty())
            return Optional.empty();

        return Optional.of(fromHeaders(limit.get(), reset.get()));
    }

    /**
     * Creates a {@link RateLimit} directly from server-provided limit and reset
     * values.
     * <p>
     * This is a convenience method for constructing a rate limit when the
     * header values have already been extracted.  The {@code remaining} count,
     * if relevant, is tracked externally by {@link RateLimitBucket}.
     *
     * @param limit the maximum number of requests allowed in the window
     * @param resetSeconds the number of seconds until the quota resets
     * @return a new {@link RateLimit} reflecting the server-advertised policy
     */
    public static @NotNull RateLimit fromHeaders(long limit, long resetSeconds) {
        return new RateLimit(limit, resetSeconds, false);
    }

    /**
     * Creates a new unlimited rate-limit instance.
     * <p>
     * Useful for testing or for endpoints that have no effective rate limit.
     * Callers that need a shared constant should prefer {@link #UNLIMITED}.
     *
     * @return a fresh unlimited {@link RateLimit} instance
     */
    public static @NotNull RateLimit unlimited() {
        return new RateLimit(Long.MAX_VALUE, Long.MAX_VALUE / 1000L, true);
    }

    /**
     * Retrieves the first non-empty string value for any of the specified header
     * keys from the given header map.
     *
     * @param headers the HTTP headers to search
     * @param keys one or more header names to look up (checked in order)
     * @return an {@link Optional} containing the first found value, or empty if
     *         none of the keys are present
     */
    private static @NotNull Optional<String> getFirst(@NotNull Map<String, Collection<String>> headers, @NotNull String... keys) {
        for (String key : keys) {
            Collection<String> values = headers.get(key);

            if (values != null && !values.isEmpty())
                return Optional.of(values.iterator().next());
        }

        return Optional.empty();
    }

    /**
     * Retrieves the first header value for the specified keys and parses it as
     * a {@code long}.
     * <p>
     * Returns an empty {@link Optional} if no matching header is found or if
     * the value cannot be parsed as a long integer.
     *
     * @param headers the HTTP headers to search
     * @param keys one or more header names to look up (checked in order)
     * @return an {@link Optional} containing the parsed long value, or empty on
     *         absence or parse failure
     */
    private static @NotNull Optional<Long> getFirstLong(@NotNull Map<String, Collection<String>> headers, @NotNull String... keys) {
        return getFirst(headers, keys).flatMap(value -> {
            try {
                return Optional.of(Long.parseLong(value.trim()));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    /**
     * Fluent builder for constructing {@link RateLimit} instances with custom
     * quota and window settings.
     * <p>
     * Defaults to 600 requests per 10 minutes if no values are explicitly set.
     *
     * @see RateLimit#builder()
     */
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static class Builder {

        /** The maximum number of requests allowed in the window (default: 600). */
        private long limit = 600;

        /** The numeric duration of the window (default: 10). */
        private long windowDuration = 10;

        /** The temporal unit of the window duration (default: {@link ChronoUnit#MINUTES}). */
        private ChronoUnit windowUnit = ChronoUnit.MINUTES;

        /**
         * Sets the maximum number of requests allowed in the window.
         *
         * @param limit the request quota
         * @return this builder for chaining
         */
        public @NotNull Builder limit(long limit) {
            this.limit = limit;
            return this;
        }

        /**
         * Sets the window duration and its temporal unit.
         *
         * @param duration the numeric length of the window
         * @param unit the temporal unit (e.g. {@link ChronoUnit#SECONDS}, {@link ChronoUnit#MINUTES})
         * @return this builder for chaining
         */
        public @NotNull Builder window(long duration, @NotNull ChronoUnit unit) {
            this.windowDuration = duration;
            this.windowUnit = unit;
            return this;
        }

        /**
         * Builds and returns a new {@link RateLimit} from the configured values.
         *
         * @return a new {@link RateLimit} instance
         */
        public @NotNull RateLimit build() {
            return new RateLimit(
                this.limit,
                this.windowDuration,
                this.windowUnit
            );
        }

    }

}
