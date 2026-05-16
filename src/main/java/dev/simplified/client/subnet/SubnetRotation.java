package dev.simplified.client.subnet;

import dev.simplified.client.Proxy;
import dev.simplified.client.ratelimit.RateLimitConfig;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.route.DynamicRouteProvider;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;

/**
 * Immutable configuration describing how a {@link Proxy} rotates IPv6 source
 * addresses across rate-limit-relevant subnets.
 * <p>
 * Captures rotation specifics only - source prefix, the dimension at which
 * upstream distinguishes my sources, selection strategy, and per-Proxy knobs.
 * The rate-limit numbers (limit, window) live entirely with the route on
 * {@link RateLimitConfig @RateLimitConfig} or
 * {@link DynamicRouteProvider}, and the live
 * counter state stays in {@link RateLimitManager}.
 * <p>
 * The {@code bucketPrefixLength} is what makes rotation produce relief: it
 * answers "at what /N does upstream group my source addresses into one
 * bucket?". The proxy uses it to compose request-time bucketKeys of the form
 * {@code routeBucketId + "@" + IPv6Prefix.of(boundIp, bucketPrefixLength)}, which
 * the rate-limit layer then keys its counters by.
 * <p>
 * Construct via {@link #builder()}.
 */
public record SubnetRotation(
    @NotNull IPv6Prefix sourcePrefix,
    int bucketPrefixLength,
    @NotNull SubnetSelectionStrategy strategy,
    double softCapFraction,
    int maxRejectSamples
) {

    /**
     * Compact constructor validating all components.
     * <p>
     * The bucket prefix length is independent of the source prefix length; the
     * pool dispatch (fan-out / single / pass-through) is selected from the
     * relationship between them at construction time.
     *
     * @throws IllegalArgumentException if {@code bucketPrefixLength} is outside
     *         {@code [0, 128]}, {@code softCapFraction} is outside
     *         {@code (0, 1]}, or {@code maxRejectSamples} is less than 1
     */
    public SubnetRotation {
        if (bucketPrefixLength < 0 || bucketPrefixLength > IPv6Prefix.IPV6_BITS)
            throw new IllegalArgumentException(
                "bucketPrefixLength must be in [0, " + IPv6Prefix.IPV6_BITS + "]: " + bucketPrefixLength
            );
        if (!(softCapFraction > 0.0 && softCapFraction <= 1.0))
            throw new IllegalArgumentException("softCapFraction must be in (0, 1]: " + softCapFraction);
        if (maxRejectSamples < 1)
            throw new IllegalArgumentException("maxRejectSamples must be >= 1: " + maxRejectSamples);
    }

    /**
     * Returns the number of buckets this rotation can fan out across.
     *
     * @return {@code 2^(bucketPrefixLength - sourcePrefix.length())}
     */
    public @NotNull BigInteger bucketCount() {
        return this.sourcePrefix.containedSubnetCount(this.bucketPrefixLength);
    }

    /**
     * Returns a new builder pre-seeded with sensible defaults.
     *
     * @return a fresh builder
     */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link SubnetRotation} instances.
     */
    @NoArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class Builder {

        private IPv6Prefix sourcePrefix;
        private int bucketPrefixLength;
        private boolean bucketPrefixLengthSet;
        private @NotNull SubnetSelectionStrategy strategy = SubnetSelectionStrategy.RANDOM_REJECT_SAMPLE;
        private double softCapFraction = 0.9;
        private int maxRejectSamples = 16;

        /**
         * Sets the source prefix from a parsed {@link IPv6Prefix}.
         *
         * @param sourcePrefix the source prefix
         * @return this builder
         */
        public @NotNull Builder sourcePrefix(@NotNull IPv6Prefix sourcePrefix) {
            this.sourcePrefix = sourcePrefix;
            return this;
        }

        /**
         * Sets the source prefix by parsing the given CIDR string.
         *
         * @param cidr the source prefix in CIDR notation (e.g., {@code "2602:fa02:0a00::/40"})
         * @return this builder
         */
        public @NotNull Builder sourcePrefix(@NotNull String cidr) {
            return this.sourcePrefix(IPv6Prefix.parse(cidr));
        }

        /**
         * Sets the bucket prefix length.
         *
         * @param bucketPrefixLength the rate-limit-relevant subnet size in bits
         * @return this builder
         */
        public @NotNull Builder bucketPrefixLength(int bucketPrefixLength) {
            this.bucketPrefixLength = bucketPrefixLength;
            this.bucketPrefixLengthSet = true;
            return this;
        }

        /**
         * Sets the bucket-selection strategy used when fanning out across
         * multiple buckets.
         *
         * @param strategy the strategy
         * @return this builder
         */
        public @NotNull Builder strategy(@NotNull SubnetSelectionStrategy strategy) {
            this.strategy = strategy;
            return this;
        }

        /**
         * Sets the soft-cap fraction at which a bucket is considered saturated
         * for selection purposes.
         *
         * @param softCapFraction the fraction in {@code (0, 1]}
         * @return this builder
         */
        public @NotNull Builder softCapFraction(double softCapFraction) {
            this.softCapFraction = softCapFraction;
            return this;
        }

        /**
         * Sets the maximum number of random samples
         * {@link SubnetSelectionStrategy#RANDOM_REJECT_SAMPLE} attempts before
         * falling back to a least-used scan.
         *
         * @param maxRejectSamples the maximum attempt count, must be at least 1
         * @return this builder
         */
        public @NotNull Builder maxRejectSamples(int maxRejectSamples) {
            this.maxRejectSamples = maxRejectSamples;
            return this;
        }

        /**
         * Builds the immutable {@link SubnetRotation}.
         *
         * @return a new rotation configuration
         * @throws IllegalStateException if {@code sourcePrefix} or
         *         {@code bucketPrefixLength} was not set
         */
        public @NotNull SubnetRotation build() {
            if (this.sourcePrefix == null)
                throw new IllegalStateException("sourcePrefix must be set");
            if (!this.bucketPrefixLengthSet)
                throw new IllegalStateException("bucketPrefixLength must be set");
            return new SubnetRotation(
                this.sourcePrefix,
                this.bucketPrefixLength,
                this.strategy,
                this.softCapFraction,
                this.maxRejectSamples
            );
        }

    }

}
