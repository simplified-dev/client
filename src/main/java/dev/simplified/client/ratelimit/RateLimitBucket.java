package dev.simplified.client.ratelimit;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe fixed-window counter that tracks the number of requests made
 * against a single rate-limit bucket.
 * <p>
 * Each bucket is identified by a route string (typically the resolved domain or
 * domain+path) and is associated with a {@link RateLimit} policy that defines the
 * quota and window duration.  The current {@link Window} - when it opened, when it ends, and the
 * requests counted in it - is one immutable value held in a single atomic reference, and every
 * change to it replaces the whole value by compare-and-set, making the bucket safe for concurrent
 * access without external synchronization.
 * <p>
 * The current window ends at {@link Window#end()}. Under a server-advertised policy that is the
 * instant the server says its quota resets at, {@link RateLimit#resetEpochMillis}; under a
 * client-configured policy it is {@link RateLimit#windowDurationMillis} after the window
 * opened.
 * <p>
 * Window rotation is performed lazily: when a request arrives at or after the window's end, a
 * new window opens at that moment with a count of zero. This approach avoids the need for a
 * background timer. A request is counted by the same compare-and-set that rotates the window it
 * is counted in, so a request racing a rotation lands in the window that ended or in the one that
 * opened, and the rotation never erases it: however many requests race across a boundary, the
 * window opened there admits no more than the limit. A window opened this way lasts the policy's
 * window duration until the next server-advertised policy anchors it to the server's reset
 * instant.
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
     * The current window, replaced whole on every change.
     */
    @Getter(AccessLevel.NONE)
    private final @NotNull AtomicReference<Window> window;

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
        this.window = new AtomicReference<>(Window.open(initialRateLimit, now));
        this.rateLimit = new AtomicReference<>(initialRateLimit);
    }

    /**
     * Returns the window this bucket holds, as one consistent snapshot of its start, end and
     * count.
     * <p>
     * A window that has ended is returned as it stands, without being rotated.
     *
     * @return the window this bucket holds
     */
    public @NotNull Window getWindow() {
        return this.window.get();
    }

    /**
     * Determines whether this bucket has exhausted its quota for the current window.
     * <p>
     * If the current window has elapsed, a new one opens with a count of zero before the check, so
     * a bucket under any positive limit returns {@code false}. Buckets backed by an
     * {@linkplain RateLimit#unlimited unlimited} policy always return {@code false}.
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

        return this.rotateIfElapsed(limit, now).count() >= limit.getLimit();
    }

    /**
     * Records a single request against this bucket.
     * <p>
     * If the current window has elapsed, a new window opens with this request as the first it
     * counts, in the same atomic step that counts it. Requests against an
     * {@linkplain RateLimit#unlimited unlimited} policy are silently ignored.
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

        this.window.updateAndGet(current -> current.rotatedAt(limit, now).counted());
    }

    /**
     * Admits a single request and records it against this bucket in one atomic step, unless the
     * bucket has exhausted its quota for the current window.
     * <p>
     * The window is replaced by a compare-and-set that succeeds only while no other change has
     * landed since it was read, and only a window whose count is below the limit is counted into,
     * so requests arriving together are admitted no further than the limit allows, where an
     * {@link #isRateLimited(long)} check followed by {@link #trackRequest(long)} can admit every
     * request that checks before any of them is counted. A window that has elapsed is rotated by
     * the same compare-and-set that counts the request, so a request racing the rotation is
     * counted in the window it was admitted against and is never erased by it. Requests against an
     * {@linkplain RateLimit#unlimited unlimited} policy are admitted without being counted.
     *
     * @param now the pre-sampled epoch-millisecond timestamp to evaluate the window against and
     *            record the request at
     * @return {@code true} if the request was admitted and counted; {@code false} if the quota is
     *         exhausted, in which case nothing is counted
     */
    public boolean tryAcquire(long now) {
        RateLimit limit = this.rateLimit.get();

        if (limit.isUnlimited())
            return true;

        while (true) {
            Window observed = this.window.get();
            Window current = observed.rotatedAt(limit, now);

            if (current.count() >= limit.getLimit())
                return false;

            if (this.window.compareAndSet(observed, current.counted()))
                return true;
        }
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
     * then ends at the new policy's {@linkplain RateLimit#resetEpochMillis reset instant}
     * when it is server-advertised, even one already past, which clears the count on the next
     * check; under a client-configured policy it ends the new policy's window duration after the
     * current window opened.
     *
     * @param newLimit the updated rate-limit policy to apply
     * @param now the pre-sampled epoch-millisecond timestamp the policy was received at
     */
    public void updateRateLimit(@NotNull RateLimit newLimit, long now) {
        this.rateLimit.set(newLimit);
        long resetEpochMillis = newLimit.getResetEpochMillis();

        this.window.updateAndGet(current -> {
            Window rotated = current.rotatedAt(newLimit, now);
            return rotated.endingAt(resetEpochMillis > 0 ? resetEpochMillis : nextWindowEnd(newLimit, rotated.start()));
        });
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
     * by an {@linkplain RateLimit#unlimited unlimited} policy ignore it.
     *
     * @param remaining the number of requests the server reports remaining in its current window
     */
    public void syncRemaining(long remaining) {
        RateLimit limit = this.rateLimit.get();

        if (limit.isUnlimited())
            return;

        long max = Math.max(0L, limit.getLimit());
        long count = max - Math.clamp(remaining, 0L, max);
        this.window.updateAndGet(current -> current.withCount(count));
    }

    /**
     * Resets this bucket by opening a new window at the current time and
     * clearing the request count to zero.
     * <p>
     * Under a server-advertised policy whose reset instant is still ahead, the new
     * window ends at that instant.
     */
    public void reset() {
        this.window.set(Window.open(this.rateLimit.get(), System.currentTimeMillis()));
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
        Window current = this.window.get();
        return now >= current.end() ? 0L : current.count();
    }

    /**
     * Calculates the number of requests remaining before the bucket's quota
     * is exhausted in the current window.
     * <p>
     * Returns {@link Long#MAX_VALUE} for buckets backed by an
     * {@linkplain RateLimit#unlimited unlimited} policy.  The returned
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
     * {@linkplain RateLimit#unlimited unlimited} policy; the returned value is otherwise
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
     * Opens a new window at {@code now} when the current one has ended, with a count of zero.
     * <p>
     * The new window replaces the one that ended by compare-and-set, so a caller that loses the
     * race to a concurrent change reads the window that change left and checks it again.
     *
     * @param limit the policy the new window is sized by
     * @param now the epoch-millisecond timestamp to evaluate the window against
     * @return the window current at {@code now}, which is the one this call opened when the
     *         window it read had ended
     */
    private @NotNull Window rotateIfElapsed(@NotNull RateLimit limit, long now) {
        while (true) {
            Window observed = this.window.get();
            Window current = observed.rotatedAt(limit, now);

            if (current == observed || this.window.compareAndSet(observed, current))
                return current;
        }
    }

    /**
     * Computes the end of a window opening at {@code start} under the given policy.
     * <p>
     * A server-advertised reset instant still ahead of {@code start} ends the window; otherwise
     * the window lasts the policy's {@linkplain RateLimit#windowDurationMillis duration},
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

    /**
     * One fixed window of a bucket - when it opened, when it ends, and the requests counted in it.
     * <p>
     * A window is immutable. The bucket replaces the one it holds whole, so the three components
     * are always read and changed together.
     *
     * @param start the epoch-millisecond timestamp the window opened at
     * @param end the epoch-millisecond timestamp at which the window ends and its count clears
     * @param count the number of requests counted in the window
     */
    public record Window(long start, long end, long count) {

        /**
         * Opens a window at a timestamp under a policy, with nothing counted.
         *
         * @param limit the policy sizing the window
         * @param start the epoch-millisecond timestamp the window opens at
         * @return the opened window
         */
        private static @NotNull Window open(@NotNull RateLimit limit, long start) {
            return new Window(start, nextWindowEnd(limit, start), 0L);
        }

        /**
         * Resolves the window current at a timestamp: this one while it has not ended, otherwise
         * one opened at that timestamp under a policy.
         *
         * @param limit the policy sizing a window opened in place of this one
         * @param now the epoch-millisecond timestamp to evaluate this window against
         * @return this window, or the one opened at {@code now}
         */
        private @NotNull Window rotatedAt(@NotNull RateLimit limit, long now) {
            return now < this.end ? this : open(limit, now);
        }

        /**
         * Counts one more request in this window.
         *
         * @return this window with its count raised by one
         */
        private @NotNull Window counted() {
            return new Window(this.start, this.end, this.count + 1);
        }

        /**
         * Replaces the count of this window.
         *
         * @param count the number of requests the window counts
         * @return this window with the given count
         */
        private @NotNull Window withCount(long count) {
            return new Window(this.start, this.end, count);
        }

        /**
         * Moves the end of this window.
         *
         * @param end the epoch-millisecond timestamp the window ends at
         * @return this window ending at the given timestamp
         */
        private @NotNull Window endingAt(long end) {
            return new Window(this.start, end, this.count);
        }

    }

}
