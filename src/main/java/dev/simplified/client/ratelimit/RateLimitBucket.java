package dev.simplified.client.ratelimit;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe sliding-window counter that tracks the number of requests made
 * against a single rate-limit bucket.
 * <p>
 * Each bucket is identified by a route string (typically the resolved domain or
 * domain+path) and is associated with a {@link RateLimit} policy that defines the
 * quota and window duration.  Request counts and window boundaries are maintained
 * using atomic primitives, making the bucket safe for concurrent access without
 * external synchronization.
 * <p>
 * Window rotation is performed lazily: when a request arrives after the current
 * window has elapsed, the window start is advanced and the counter is reset via
 * a compare-and-set operation.  This approach avoids the need for a background
 * timer while remaining accurate under contention.
 * <p>
 * Instances are managed by {@link RateLimitManager} and are not intended for
 * direct external use.
 *
 * @see RateLimit
 * @see RateLimitManager
 */
@Getter
class RateLimitBucket {

    /** The epoch-millisecond timestamp marking the start of the current window. */
    private final @NotNull AtomicLong windowStart;

    /** The number of requests recorded in the current window. */
    private final @NotNull AtomicLong requestCount;

    /** The rate-limit policy governing this bucket, updatable from server headers. */
    private final @NotNull AtomicReference<RateLimit> rateLimit;

    /**
     * Constructs a new bucket initialized to the current system time with a
     * request count of zero.
     *
     * @param initialRateLimit the rate-limit policy to enforce for this bucket
     */
    RateLimitBucket(@NotNull RateLimit initialRateLimit) {
        this.windowStart = new AtomicLong(System.currentTimeMillis());
        this.requestCount = new AtomicLong(0);
        this.rateLimit = new AtomicReference<>(initialRateLimit);
    }

    /**
     * Determines whether this bucket has exhausted its quota for the current
     * window.
     * <p>
     * If the current window has elapsed, the counter is atomically reset and
     * the method returns {@code false}.  Buckets backed by an
     * {@linkplain RateLimit#isUnlimited() unlimited} policy always return
     * {@code false}.
     *
     * @return {@code true} if the request count has reached the configured
     *         limit and the window has not yet expired; {@code false} otherwise
     */
    public boolean isRateLimited() {
        RateLimit limit = rateLimit.get();
        if (limit.isUnlimited()) {
            return false;
        }

        long now = System.currentTimeMillis();
        long start = windowStart.get();
        long windowMillis = limit.getWindowDurationMillis();

        if (now - start >= windowMillis) {
            if (windowStart.compareAndSet(start, now)) {
                requestCount.set(0);
                return false;
            }
        }

        long current = requestCount.get();
        long hardLimit = limit.getLimit();

        return current >= hardLimit;
    }

    /**
     * Records a single request against this bucket.
     * <p>
     * If the current window has elapsed, the counter is atomically reset to
     * {@code 1} (counting the current request as the first in the new window).
     * Requests against an {@linkplain RateLimit#isUnlimited() unlimited} policy
     * are silently ignored.
     */
    public void trackRequest() {
        RateLimit limit = this.rateLimit.get();

        if (limit.isUnlimited())
            return;

        long now = System.currentTimeMillis();
        long start = this.windowStart.get();
        long windowMillis = limit.getWindowDurationMillis();

        if (now - start >= windowMillis) {
            if (this.windowStart.compareAndSet(start, now)) {
                this.requestCount.set(1);
                return;
            }
        }

        this.requestCount.incrementAndGet();
    }

    /**
     * Replaces the current rate-limit policy for this bucket.
     * <p>
     * Typically invoked when updated rate-limit information is received from
     * the server via response headers, allowing the bucket to adapt to
     * server-side quota changes at runtime.
     *
     * @param newLimit the updated rate-limit policy to apply
     */
    public void updateRateLimit(@NotNull RateLimit newLimit) {
        this.rateLimit.set(newLimit);
    }

    /**
     * Resets this bucket by advancing the window start to the current time
     * and clearing the request count to zero.
     */
    public void reset() {
        this.windowStart.set(System.currentTimeMillis());
        this.requestCount.set(0);
    }

    /**
     * Returns the number of requests recorded in the current window.
     *
     * @return the current request count
     */
    public long getCount() {
        return this.requestCount.get();
    }

    /**
     * Calculates the number of requests remaining before the bucket's quota
     * is exhausted in the current window.
     * <p>
     * Returns {@link Long#MAX_VALUE} for buckets backed by an
     * {@linkplain RateLimit#isUnlimited() unlimited} policy.  The returned
     * value is clamped to a minimum of {@code 0}.
     *
     * @return the number of remaining requests, or {@link Long#MAX_VALUE} if unlimited
     */
    public long getRemaining() {
        RateLimit limit = this.rateLimit.get();

        if (limit.isUnlimited())
            return Long.MAX_VALUE;

        long remaining = limit.getLimit() - this.requestCount.get();
        return Math.max(0, remaining);
    }

}
