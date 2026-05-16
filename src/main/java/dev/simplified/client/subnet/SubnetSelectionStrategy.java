package dev.simplified.client.subnet;

import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.subnet.pool.SubnetBucket;
import dev.simplified.client.subnet.pool.FanOutBucketPool;

/**
 * Strategy used by a {@link FanOutBucketPool} to pick a contained subnet
 * {@link SubnetBucket} when serving a request.
 * <p>
 * Strategies differ in how they balance request distribution across the
 * available subnet buckets and how they react to saturated buckets. All
 * strategies fall back to throwing
 * {@link RateLimitException} when no
 * unsaturated bucket is reachable.
 */
public enum SubnetSelectionStrategy {

    /**
     * Picks a uniformly random contained subnet on each call.
     * <p>
     * If the picked subnet's bucket is saturated, retries up to
     * {@link SubnetRotation#maxRejectSamples()} times with a fresh random pick.
     * After exhausting random samples, scans the active buckets and returns the
     * one with the lowest current count as a last-resort fallback.
     */
    RANDOM_REJECT_SAMPLE,

    /**
     * Picks the active bucket with the lowest current request count.
     * <p>
     * If no bucket is active yet, materializes a random one on demand. Scales
     * linearly with the number of active buckets - prefer
     * {@link #RANDOM_REJECT_SAMPLE} when the source prefix fans out to many
     * thousands of /56s.
     */
    LEAST_USED,

    /**
     * Cycles through contained subnets in lexicographic order.
     * <p>
     * Predictable and deterministic; loses the unpredictability benefit of
     * random rotation but useful for testing and load-distribution validation.
     */
    ROUND_ROBIN

}
