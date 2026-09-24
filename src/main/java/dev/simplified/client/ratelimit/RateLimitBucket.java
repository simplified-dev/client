package dev.simplified.client.ratelimit;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe fixed-window counter that tracks the number of requests made
 * against a single rate-limit bucket.
 * <p>
 * Each bucket is identified by a route string (typically the resolved domain or
 * domain+path) and is associated with a {@link RateLimit} policy that defines the
 * quota and window duration.  Request counts and window boundaries are maintained
 * using atomic primitives, making the bucket safe for concurrent access without
 * external synchronization.
 * <p>
 * The current window ends at {@link #windowEnd}. Under a server-advertised policy that is the
 * instant the server says its quota resets at, {@link RateLimit#getResetEpochMillis()}; under a
 * client-configured policy it is {@link RateLimit#getWindowDurationMillis()} after the window
 * opened.
 * <p>
 * Window rotation is performed lazily: when a request arrives at or after the window's end, a
 * new window opens at that moment and the counter is reset via a compare-and-set operation.
 * This approach avoids the need for a background timer while remaining accurate under
 * contention. A window opened this way lasts the policy's window duration until the next
 * server-advertised policy anchors it to the server's reset instant.
 * <p>
 * Server responses are applied through {@link #updateFromServer}, which orders them by the
 * request they answered and serializes them on a lock of the bucket's own; counting and
 * window rotation stay lock-free.
 * <p>
 * Instances are managed by {@link RateLimitManager} and are not intended for
 * direct external use.
 *
 * @see RateLimit
 * @see RateLimitManager
 */
@Getter
public class RateLimitBucket {

    /**
     * The epoch-millisecond timestamp marking the start of the current window.
     */
    private final @NotNull AtomicLong windowStart;

    /**
     * The epoch-millisecond timestamp at which the current window ends and its count clears.
     */
    private final @NotNull AtomicLong windowEnd;

    /**
     * The number of requests recorded in the current window.
     */
    private final @NotNull AtomicLong requestCount;

    /**
     * The rate-limit policy governing this bucket, updatable from server headers.
     */
    private final @NotNull AtomicReference<RateLimit> rateLimit;

    /**
     * Serializes {@link #updateFromServer}, guarding {@link #serverSequence}.
     */
    @Getter(AccessLevel.NONE)
    private final @NotNull ReentrantLock serverLock = new ReentrantLock();

    /**
     * The number of the request whose response last updated this bucket through
     * {@link #updateFromServer}, or {@code 0} before any has.
     */
    @Getter(AccessLevel.NONE)
    private long serverSequence;

    /**
     * Constructs a new bucket initialized to the current system time with a
     * request count of zero.
     *
     * @param initialRateLimit the rate-limit policy to enforce for this bucket
     */
    public RateLimitBucket(@NotNull RateLimit initialRateLimit) {
        this(initialRateLimit, System.currentTimeMillis());
    }

    /**
     * Constructs a new bucket whose first window opens at a pre-sampled timestamp, with a
     * request count of zero.
     *
     * @param initialRateLimit the rate-limit policy to enforce for this bucket
     * @param now the epoch-millisecond timestamp the first window opens at
     */
    public RateLimitBucket(@NotNull RateLimit initialRateLimit, long now) {
        this.windowStart = new AtomicLong(now);
        this.windowEnd = new AtomicLong(nextWindowEnd(initialRateLimit, now));
        this.requestCount = new AtomicLong(0);
        this.rateLimit = new AtomicReference<>(initialRateLimit);
    }

    /**
     * Determines whether this bucket has exhausted its quota for the current window.
     * <p>
     * If the current window has elapsed, the counter is atomically reset and the method returns {@code false}.
     * Buckets backed by an {@linkplain RateLimit#isUnlimited() unlimited} policy always return {@code false}.
     *
     * @return {@code true} if the request count has reached the configured
     *         limit and the window has not yet expired; {@code false} otherwise
     */
    public boolean isRateLimited() {
        return this.isRateLimited(System.currentTimeMillis());
    }

    /**
     * Determines whether this bucket has exhausted its quota for the current window.
     * <p>
     * Accepts a pre-sampled epoch-millisecond timestamp, allowing callers that already hold a clock
     * reading to avoid an extra {@link System#currentTimeMillis()} sample.
     *
     * @param now the pre-sampled epoch-millisecond timestamp to evaluate the window against
     * @return {@code true} if the request count has reached the configured
     *         limit and the window has not yet expired; {@code false} otherwise
     */
    public boolean isRateLimited(long now) {
        RateLimit limit = this.rateLimit.get();

        if (limit.isUnlimited())
            return false;

        if (this.rotateIfElapsed(limit, now))
            return false;

        return this.requestCount.get() >= limit.getLimit();
    }

    /**
     * Records a single request against this bucket.
     * <p>
     * If the current window has elapsed, the counter is atomically reset to {@code 1} (counting the current request
     * as the first in the new window). Requests against an {@linkplain RateLimit#isUnlimited() unlimited} policy are silently ignored.
     */
    public void trackRequest() {
        this.trackRequest(System.currentTimeMillis());
    }

    /**
     * Records a single request against this bucket.
     * <p>
     * Accepts a pre-sampled epoch-millisecond timestamp, allowing callers that already hold a clock
     * reading to avoid an extra {@link System#currentTimeMillis()} sample.
     *
     * @param now the pre-sampled epoch-millisecond timestamp to record this request against
     */
    public void trackRequest(long now) {
        RateLimit limit = this.rateLimit.get();

        if (limit.isUnlimited())
            return;

        this.rotateIfElapsed(limit, now);
        this.requestCount.incrementAndGet();
    }

    /**
     * Replaces the current rate-limit policy for this bucket.
     * <p>
     * Typically invoked when updated rate-limit information is received from
     * the server via response headers, allowing the bucket to adapt to
     * server-side quota changes at runtime.  See {@link #updateRateLimit(RateLimit, long)}
     * for how the window follows the new policy.
     *
     * @param newLimit the updated rate-limit policy to apply
     */
    public void updateRateLimit(@NotNull RateLimit newLimit) {
        this.updateRateLimit(newLimit, System.currentTimeMillis());
    }

    /**
     * Replaces the current rate-limit policy for this bucket against a pre-sampled clock reading.
     * <p>
     * A window that has already ended by {@code now} clears its count first. The current window
     * then ends at the new policy's {@linkplain RateLimit#getResetEpochMillis() reset instant}
     * when it is server-advertised, even one already past, which clears the count on the next
     * check; under a client-configured policy it ends the new policy's window duration after the
     * current window opened.
     *
     * @param newLimit the updated rate-limit policy to apply
     * @param now the pre-sampled epoch-millisecond timestamp the policy was received at
     */
    public void updateRateLimit(@NotNull RateLimit newLimit, long now) {
        this.rateLimit.set(newLimit);
        this.rotateIfElapsed(newLimit, now);
        long resetEpochMillis = newLimit.getResetEpochMillis();
        this.windowEnd.set(resetEpochMillis > 0 ? resetEpochMillis : nextWindowEnd(newLimit, this.windowStart.get()));
    }

    /**
     * Applies the policy and remaining count a server response reported, unless the response to
     * a later request has already been applied.
     * <p>
     * Requests are numbered in the order they are sent, by
     * {@link RateLimitManager#nextSequence()}. A response that lands after the response to a
     * later request reports the server's quota as it stood before that request: its remaining
     * count would roll the count back below what the server has since reported spent, and its
     * reset could move the window's end back to one already superseded. It is ignored whole.
     * Otherwise the policy replaces this bucket's as {@link #updateRateLimit(RateLimit, long)}
     * does and {@code remaining}, when present, {@linkplain #syncRemaining(long) syncs} the count.
     * <p>
     * The check and the update run under one lock, so responses racing to apply cannot
     * interleave: whatever order they run in, the bucket ends holding the figures of the latest
     * request among them.
     *
     * @param newLimit the policy the response advertised
     * @param remaining the remaining count the response reported, or empty if it reported none
     * @param now the epoch-millisecond timestamp the response was received at
     * @param sequence the number {@link RateLimitManager#nextSequence()} gave the request the
     *                 response answered
     * @return {@code true} if the response was applied, {@code false} if the response to a later
     *         request already had been
     */
    public boolean updateFromServer(@NotNull RateLimit newLimit, @NotNull OptionalLong remaining, long now, long sequence) {
        this.serverLock.lock();

        try {
            if (sequence <= this.serverSequence)
                return false;

            this.serverSequence = sequence;
            this.updateRateLimit(newLimit, now);
            remaining.ifPresent(this::syncRemaining);
            return true;
        } finally {
            this.serverLock.unlock();
        }
    }

    /**
     * Sets the request count to the number of requests the server reports as spent in its
     * current window.
     * <p>
     * The count becomes the policy's limit less {@code remaining}, with {@code remaining} clamped
     * to between zero and the limit. Requests the server never counted - replies served from the
     * local response cache among them - stop counting against the bucket, and requests spent
     * against the same quota from elsewhere start to. A request still in flight when the server
     * reported the figure is not in it, so under concurrent load the count can trail the true
     * usage until that request's own response is synced. {@link #updateFromServer} keeps a
     * response that lands after a later request's from syncing its older figure. Buckets backed
     * by an {@linkplain RateLimit#isUnlimited() unlimited} policy ignore it.
     *
     * @param remaining the number of requests the server reports remaining in its current window
     */
    public void syncRemaining(long remaining) {
        RateLimit limit = this.rateLimit.get();

        if (limit.isUnlimited())
            return;

        long max = Math.max(0L, limit.getLimit());
        this.requestCount.set(max - Math.clamp(remaining, 0L, max));
    }

    /**
     * Resets this bucket by opening a new window at the current time and
     * clearing the request count to zero.
     * <p>
     * Under a server-advertised policy whose reset instant is still ahead, the new
     * window ends at that instant.
     */
    public void reset() {
        long now = System.currentTimeMillis();
        this.windowStart.set(now);
        this.windowEnd.set(nextWindowEnd(this.rateLimit.get(), now));
        this.requestCount.set(0);
    }

    /**
     * Returns the number of requests recorded in the current window.
     *
     * @return the current request count
     */
    public long getCount() {
        return this.getCount(System.currentTimeMillis());
    }

    /**
     * Returns the number of requests recorded in the window current at a pre-sampled timestamp.
     * <p>
     * A window that has ended by {@code now} reports zero without being rotated.
     *
     * @param now the pre-sampled epoch-millisecond timestamp to evaluate the window against
     * @return the request count of the window current at {@code now}
     */
    public long getCount(long now) {
        return now >= this.windowEnd.get() ? 0L : this.requestCount.get();
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
        return this.getRemaining(System.currentTimeMillis());
    }

    /**
     * Calculates the number of requests remaining in the window current at a pre-sampled
     * timestamp.
     * <p>
     * A window that has ended by {@code now} reports the policy's full limit without being
     * rotated. Returns {@link Long#MAX_VALUE} for buckets backed by an
     * {@linkplain RateLimit#isUnlimited() unlimited} policy; the returned value is otherwise
     * clamped to a minimum of {@code 0}.
     *
     * @param now the pre-sampled epoch-millisecond timestamp to evaluate the window against
     * @return the number of remaining requests, or {@link Long#MAX_VALUE} if unlimited
     */
    public long getRemaining(long now) {
        RateLimit limit = this.rateLimit.get();

        if (limit.isUnlimited())
            return Long.MAX_VALUE;

        long remaining = limit.getLimit() - this.getCount(now);
        return Math.max(0, remaining);
    }

    /**
     * Opens a new window at {@code now} when the current one has ended, clearing the count.
     * <p>
     * Only the caller whose compare-and-set moves {@link #windowEnd} opens the window; a
     * concurrent caller that loses the race sees the window the winner opened.
     *
     * @param limit the policy the new window is sized by
     * @param now the epoch-millisecond timestamp to evaluate the window against
     * @return {@code true} if this call opened a new window
     */
    private boolean rotateIfElapsed(@NotNull RateLimit limit, long now) {
        long end = this.windowEnd.get();

        if (now < end)
            return false;

        if (!this.windowEnd.compareAndSet(end, nextWindowEnd(limit, now)))
            return false;

        this.windowStart.set(now);
        this.requestCount.set(0);
        return true;
    }

    /**
     * Computes the end of a window opening at {@code start} under the given policy.
     * <p>
     * A server-advertised reset instant still ahead of {@code start} ends the window; otherwise
     * the window lasts the policy's {@linkplain RateLimit#getWindowDurationMillis() duration},
     * saturating at {@link Long#MAX_VALUE}.
     *
     * @param limit the policy sizing the window
     * @param start the epoch-millisecond timestamp the window opens at
     * @return the epoch-millisecond timestamp the window ends at
     */
    private static long nextWindowEnd(@NotNull RateLimit limit, long start) {
        long resetEpochMillis = limit.getResetEpochMillis();

        if (resetEpochMillis > start)
            return resetEpochMillis;

        long end = start + limit.getWindowDurationMillis();
        return end < start ? Long.MAX_VALUE : end;
    }

}
