package dev.simplified.client.ratelimit;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Immutable rate-limit policy describing the maximum number of requests allowed
 * within a time window.
 * <p>
 * This class models the <em>policy</em> (quota and window duration) rather than
 * the live request count, which is tracked externally by {@link RateLimitBucket}.
 * Its fields are aligned with the IETF {@code RateLimit} header fields draft:
 * <ul>
 *   <li>{@code RateLimit-Limit} maps to {@link #limit}</li>
 *   <li>{@code RateLimit-Reset} maps to {@link #resetSeconds}</li>
 * </ul>
 * <p>
 * A policy parsed from response headers also carries {@link #resetEpochMillis}, the instant the
 * server's quota resets at, so the bucket enforcing it ends its window when the server does
 * rather than a fixed duration after the window opened. A client-configured policy carries none,
 * and its window runs for {@link #windowDurationMillis} from the moment the bucket opens it.
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
    public static final @NotNull RateLimit UNLIMITED = new RateLimit(Long.MAX_VALUE, Long.MAX_VALUE / 1000L, true, 0L);

    /**
     * Smallest reset header value read as an epoch second rather than as delta seconds.
     * <p>
     * One billion seconds is 2001-09-09T01:46:40Z as an instant and over thirty-one years as a
     * delta, a window no server advertises. A fixed bound rather than a comparison with the current
     * second keeps an epoch reset that has already passed - a response that crossed the reset in
     * flight, or a server clock behind the local one - from reading as a decades-long delta.
     */
    private static final long EPOCH_SECONDS_THRESHOLD = 1_000_000_000L;

    /**
     * Smallest reset header value read as an epoch millisecond rather than as an epoch second.
     * <p>
     * One trillion milliseconds is the instant {@link #EPOCH_SECONDS_THRESHOLD} seconds names,
     * 2001-09-09T01:46:40Z, so every epoch-millisecond reset since then reaches it, while one
     * trillion seconds is the year 33658, which no epoch-second reset reaches. Like the smaller
     * bound, it is fixed so that a reset already past reads in the same unit as one to come.
     */
    private static final long EPOCH_MILLIS_THRESHOLD = 1_000_000_000_000L;

    /**
     * Maximum number of requests permitted within a single window, mirroring {@code RateLimit-Limit}.
     */
    private final long limit;

    /**
     * Window duration in seconds, mirroring {@code RateLimit-Reset}; for a policy parsed from headers,
     * the seconds that remained until the server's quota reset when the headers were read.
     */
    private final long resetSeconds;

    /**
     * Normalized window duration in milliseconds, pre-computed for efficient elapsed-time comparisons.
     */
    private final long windowDurationMillis;

    /**
     * Epoch-millisecond instant at which the server-advertised quota resets, or {@code 0} for a
     * client-configured policy whose window has no fixed end.
     */
    private final long resetEpochMillis;

    /**
     * Whether this instance represents an effectively unlimited policy on the client side.
     */
    private final boolean unlimited;

    /**
     * Primary constructor used by all factory methods.
     *
     * @param limit maximum requests allowed in the window
     * @param resetSeconds window length or server-advertised reset interval in seconds
     * @param unlimited {@code true} to mark this instance as having no effective limit
     * @param resetEpochMillis the epoch-millisecond instant the server's quota resets at, or {@code 0} for none
     */
    private RateLimit(long limit, long resetSeconds, boolean unlimited, long resetEpochMillis) {
        this.limit = limit;
        this.resetSeconds = resetSeconds;
        this.unlimited = unlimited;
        this.resetEpochMillis = resetEpochMillis;

        // For "unlimited", we still give a very large window for consistency
        long effectiveReset = unlimited ? Long.MAX_VALUE / 1000L : Math.clamp(resetSeconds, 1L, Long.MAX_VALUE / 1000L);
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
            false,
            0L
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
     * The reset value may be delta seconds, as the RFC draft and APIs such as Hypixel's send it,
     * an epoch second, as GitHub sends {@code X-RateLimit-Reset}, or an epoch millisecond; see
     * {@link #fromHeaders(long, long, long)} for how the three are told apart.
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
        return fromHeaders(headers, System.currentTimeMillis());
    }

    /**
     * Parses rate-limit metadata from HTTP response headers against a pre-sampled clock reading.
     * <p>
     * Behaves as {@link #fromHeaders(Map)}, with {@code now} standing in for the current time when a
     * delta reset is anchored to an instant and when an epoch reset is converted to seconds.
     *
     * @param headers the HTTP response headers to inspect
     * @param now the epoch-millisecond timestamp the headers were received at
     * @return an {@link Optional} containing the parsed {@link RateLimit}, or
     *         empty if insufficient header information is present
     */
    public static @NotNull Optional<RateLimit> fromHeaders(@NotNull Map<String, Collection<String>> headers, long now) {
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

        return Optional.of(fromHeaders(limit.get(), reset.get(), now));
    }

    /**
     * Creates a {@link RateLimit} directly from server-provided limit and reset
     * values.
     * <p>
     * This is a convenience method for constructing a rate limit when the
     * header values have already been extracted.  The {@code remaining} count,
     * if relevant, is tracked externally by {@link RateLimitBucket}.  The reset
     * value is read as {@link #fromHeaders(long, long, long)} reads it, against
     * the current time.
     *
     * @param limit the maximum number of requests allowed in the window
     * @param resetSeconds the number of seconds until the quota resets, or the
     *                     epoch second or epoch millisecond at which it resets
     * @return a new {@link RateLimit} reflecting the server-advertised policy
     */
    public static @NotNull RateLimit fromHeaders(long limit, long resetSeconds) {
        return fromHeaders(limit, resetSeconds, System.currentTimeMillis());
    }

    /**
     * Creates a {@link RateLimit} from server-provided limit and reset values against a
     * pre-sampled clock reading.
     * <p>
     * The reset's unit is told apart by its magnitude:
     * <ul>
     *   <li><b>below one billion</b> - delta seconds, the form of the RFC draft's
     *       {@code RateLimit-Reset}; the policy resets that many seconds after {@code now}</li>
     *   <li><b>one billion up to one trillion</b> - an epoch second, the form GitHub sends in
     *       {@code X-RateLimit-Reset}</li>
     *   <li><b>one trillion and above</b> - an epoch millisecond</li>
     * </ul>
     * One billion seconds and one trillion milliseconds are the same instant,
     * 2001-09-09T01:46:40Z, so each epoch form covers every reset from then until the year 33658,
     * and no delta reaches the thirty-one years one billion seconds spans. An epoch reset resets
     * at the instant it names, and the policy's {@link #getResetSeconds() resetSeconds} is the
     * seconds from {@code now} until then, rounded up and never negative. Every form carries the
     * absolute {@link #getResetEpochMillis() reset instant}.
     *
     * @param limit the maximum number of requests allowed in the window
     * @param reset the number of seconds until the quota resets, or the epoch second or epoch
     *              millisecond at which it resets
     * @param now the epoch-millisecond timestamp the values were received at
     * @return a new {@link RateLimit} reflecting the server-advertised policy
     */
    public static @NotNull RateLimit fromHeaders(long limit, long reset, long now) {
        if (reset < EPOCH_SECONDS_THRESHOLD) {
            long secondsUntil = Math.max(0L, reset);
            return new RateLimit(limit, secondsUntil, false, now + secondsUntil * 1000L);
        }

        long resetEpochMillis = reset < EPOCH_MILLIS_THRESHOLD ? reset * 1000L : reset;
        long secondsUntil = Math.max(0L, Math.ceilDiv(resetEpochMillis - now, 1000L));
        return new RateLimit(limit, secondsUntil, false, resetEpochMillis);
    }

    /**
     * Parses the server's remaining request count for the current window from HTTP response
     * headers, reading {@code RateLimit-Remaining} before {@code X-RateLimit-Remaining}.
     *
     * @param headers the HTTP response headers to inspect
     * @return the remaining request count, or empty if neither header is present or parseable
     */
    public static @NotNull OptionalLong remainingFromHeaders(@NotNull Map<String, Collection<String>> headers) {
        return getFirstLong(
            headers,
            "RateLimit-Remaining", "ratelimit-remaining",
            "X-RateLimit-Remaining", "x-ratelimit-remaining"
        )
            .map(OptionalLong::of)
            .orElseGet(OptionalLong::empty);
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
        return new RateLimit(Long.MAX_VALUE, Long.MAX_VALUE / 1000L, true, 0L);
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

        /**
         * The maximum number of requests allowed in the window (default: 600).
         */
        private long limit = 600;

        /**
         * The numeric duration of the window (default: 10).
         */
        private long windowDuration = 10;

        /**
         * The temporal unit of the window duration (default: {@link ChronoUnit#MINUTES}).
         */
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
