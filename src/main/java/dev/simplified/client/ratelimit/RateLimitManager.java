package dev.simplified.client.ratelimit;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * Central registry that tracks per-route rate-limit state across multiple
 * {@link RateLimitBucket} instances.
 * <p>
 * Each {@link dev.simplified.client.Client} owns a single {@code RateLimitManager}
 * whose buckets are keyed by route identifiers (typically the resolved domain or
 * domain+path string from {@link dev.simplified.client.route.RouteDiscovery}).
 * The manager coordinates proactive (client-side) rate-limit enforcement by
 * providing query and mutation methods used by the request and response
 * interceptor pipeline.
 * <p>
 * Bucket creation is lazy: buckets are instantiated on first access when a
 * {@link RateLimit} policy is supplied, and are never created by read-only
 * query methods.
 *
 * @see RateLimitBucket
 * @see RateLimit
 * @see dev.simplified.client.Client
 */
@NoArgsConstructor
public class RateLimitManager {

    /** Map of route identifiers to their corresponding rate-limit buckets. */
    private final @NotNull ConcurrentMap<String, RateLimitBucket> buckets = Concurrent.newMap();

    /**
     * Retrieves an existing bucket for the given identifier, or creates a new
     * one initialized with the specified {@link RateLimit} policy if none exists.
     *
     * @param bucketId the route identifier for the bucket
     * @param rateLimit the rate-limit policy to use if a new bucket must be created
     * @return the existing or newly created bucket, never {@code null}
     */
    private @NotNull RateLimitBucket getOrCreateBucket(@NotNull String bucketId, @NotNull RateLimit rateLimit) {
        return this.buckets.computeIfAbsent(bucketId, __ -> new RateLimitBucket(rateLimit));
    }

    /**
     * Checks whether the bucket identified by {@code bucketId} has exhausted
     * its quota for the current window.
     * <p>
     * This overload does <em>not</em> create a missing bucket; if no bucket
     * exists for the given identifier, {@code false} is returned.
     *
     * @param bucketId the route identifier to check
     * @return {@code true} if the bucket exists and is currently rate-limited;
     *         {@code false} otherwise
     */
    public boolean isRateLimited(@NotNull String bucketId) {
        RateLimitBucket bucket = this.buckets.get(bucketId);
        return bucket != null && bucket.isRateLimited();
    }

    /**
     * Checks whether the bucket identified by {@code bucketId} has exhausted
     * its quota for the current window.
     * <p>
     * Unlike {@link #isRateLimited(String)}, this overload creates the bucket
     * with the supplied {@link RateLimit} policy if it does not already exist,
     * making it suitable for use during known request flows where the policy is
     * always available.
     *
     * @param bucketId the route identifier to check
     * @param rateLimit the rate-limit policy to use if the bucket must be created
     * @return {@code true} if the bucket is currently rate-limited; {@code false}
     *         otherwise
     */
    public boolean isRateLimited(@NotNull String bucketId, @NotNull RateLimit rateLimit) {
        return this.getOrCreateBucket(bucketId, rateLimit).isRateLimited();
    }

    /**
     * Variant of {@link #isRateLimited(String, RateLimit)} that accepts a pre-sampled
     * epoch-millisecond timestamp, allowing callers that already hold a clock reading to
     * avoid an extra {@link System#currentTimeMillis()} sample.
     *
     * @param bucketId the route identifier to check
     * @param rateLimit the rate-limit policy to use if the bucket must be created
     * @param now the pre-sampled epoch-millisecond timestamp to evaluate the window against
     * @return {@code true} if the bucket is currently rate-limited; {@code false} otherwise
     */
    public boolean isRateLimited(@NotNull String bucketId, @NotNull RateLimit rateLimit, long now) {
        return this.getOrCreateBucket(bucketId, rateLimit).isRateLimited(now);
    }

    /**
     * Records a single request against the bucket identified by {@code bucketId}.
     * <p>
     * Creates the bucket with the supplied {@link RateLimit} policy if it does
     * not already exist.
     *
     * @param bucketId the route identifier to track
     * @param rateLimit the rate-limit policy to use if the bucket must be created
     */
    public void trackRequest(@NotNull String bucketId, @NotNull RateLimit rateLimit) {
        this.getOrCreateBucket(bucketId, rateLimit).trackRequest();
    }

    /**
     * Variant of {@link #trackRequest(String, RateLimit)} that accepts a pre-sampled
     * epoch-millisecond timestamp, allowing callers that already hold a clock reading to
     * avoid an extra {@link System#currentTimeMillis()} sample.
     *
     * @param bucketId the route identifier to track
     * @param rateLimit the rate-limit policy to use if the bucket must be created
     * @param now the pre-sampled epoch-millisecond timestamp to record this request against
     */
    public void trackRequest(@NotNull String bucketId, @NotNull RateLimit rateLimit, long now) {
        this.getOrCreateBucket(bucketId, rateLimit).trackRequest(now);
    }

    /**
     * Replaces the rate-limit policy for the bucket identified by
     * {@code bucketId}.
     * <p>
     * If the bucket already exists, its policy is updated in place.  If no
     * bucket exists, a new one is created with the given policy and an initial
     * request count of zero.  This is typically called when updated rate-limit
     * information is received from the server via response headers.
     *
     * @param bucketId the route identifier whose policy should be updated
     * @param newLimit the updated rate-limit policy
     */
    public void updateRateLimit(@NotNull String bucketId, @NotNull RateLimit newLimit) {
        RateLimitBucket bucket = this.buckets.get(bucketId);

        if (bucket != null)
            bucket.updateRateLimit(newLimit);
        else {
            // If server tells us about a new/unknown route, create it
            this.buckets.put(bucketId, new RateLimitBucket(newLimit));
        }
    }

    /**
     * Returns the number of requests recorded in the current window for the
     * specified bucket.
     * <p>
     * Does <em>not</em> create a missing bucket; returns {@code 0} if no
     * bucket exists for the given identifier.
     *
     * @param bucketId the route identifier to query
     * @return the current window request count, or {@code 0} if the bucket does not exist
     */
    public long getRequestCount(@NotNull String bucketId) {
        RateLimitBucket bucket = this.buckets.get(bucketId);
        return bucket != null ? bucket.getCount() : 0;
    }

    /**
     * Returns the number of requests remaining before the specified bucket's
     * quota is exhausted in the current window.
     * <p>
     * Does <em>not</em> create a missing bucket; returns
     * {@link RateLimit#UNLIMITED}'s limit if no bucket exists for the given
     * identifier.
     *
     * @param bucketId the route identifier to query
     * @return the number of remaining requests, or the unlimited sentinel value
     *         if the bucket does not exist
     */
    public long getRemaining(@NotNull String bucketId) {
        RateLimitBucket bucket = this.buckets.get(bucketId);
        return bucket != null ? bucket.getRemaining() : RateLimit.UNLIMITED.getLimit();
    }

    /**
     * Removes all buckets from this manager, discarding all tracked state.
     */
    public void clear() {
        this.buckets.clear();
    }

    /**
     * Checks whether a bucket with the given identifier exists in this manager.
     * <p>
     * Does <em>not</em> create a missing bucket.
     *
     * @param bucketId the route identifier to look up
     * @return {@code true} if a bucket exists for the given identifier;
     *         {@code false} otherwise
     */
    public boolean hasBucket(@NotNull String bucketId) {
        return this.buckets.containsKey(bucketId);
    }

    /**
     * Resets the window start and request count for the bucket identified by
     * {@code bucketId}.
     * <p>
     * Does <em>not</em> create a missing bucket; if no bucket exists for the
     * given identifier, this method is a no-op.
     *
     * @param bucketId the route identifier whose bucket should be reset
     */
    public void reset(@NotNull String bucketId) {
        RateLimitBucket bucket = this.buckets.get(bucketId);

        if (bucket != null)
            bucket.reset();
    }

}
