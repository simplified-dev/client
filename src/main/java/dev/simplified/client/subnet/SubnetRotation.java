package dev.simplified.client.subnet;

import dev.simplified.client.Proxy;
import dev.simplified.client.ratelimit.RateLimit;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;

/**
 * Immutable configuration describing how a {@link Proxy}
 * rotates IPv6 source addresses across rate-limit-relevant subnets.
 * <p>
 * The configuration captures three orthogonal dimensions:
 * <ol>
 *   <li>The <b>source prefix</b> - the IPv6 range the host is permitted to bind
 *       outbound sockets to (e.g., a routed /40 or a Hurricane Electric /48).</li>
 *   <li>The <b>bucket prefix length</b> - the size of the smaller subnet at which
 *       upstream rate-limit enforcement is observed (e.g., {@code 56} for an
 *       upstream that enforces per-/56 quotas).</li>
 *   <li>The <b>budget</b> - the {@link RateLimit} applied to each contained
 *       bucket. Independent of any per-route rate limit configured on the
 *       client contract.</li>
 * </ol>
 * <p>
 * When the source prefix is strictly larger (numerically smaller length) than
 * the bucket prefix length, the proxy fans out across all contained subnets
 * using the configured {@linkplain SubnetSelectionStrategy strategy}. When the
 * two lengths are equal, a single bucket is used and only addresses within it
 * are rotated. When the source prefix is strictly smaller, no useful bucket
 * partition exists and the proxy passes through.
 * <p>
 * Construct via {@link #builder()}.
 */
public record SubnetRotation(
    @NotNull IpPrefix sourcePrefix,
    int bucketPrefixLength,
    @NotNull RateLimit budget,
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
        if (bucketPrefixLength < 0 || bucketPrefixLength > IpPrefix.IPV6_BITS)
            throw new IllegalArgumentException(
                "bucketPrefixLength must be in [0, " + IpPrefix.IPV6_BITS + "]: " + bucketPrefixLength
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
     * Returns the soft-cap threshold in absolute requests for the configured
     * {@link #budget}.
     *
     * @return {@code floor(budget.limit * softCapFraction)}, or
     *         {@link Long#MAX_VALUE} for an unlimited budget
     */
    public long softCapThreshold() {
        if (this.budget.isUnlimited()) return Long.MAX_VALUE;
        return (long) Math.floor(this.budget.getLimit() * this.softCapFraction);
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

        private IpPrefix sourcePrefix;
        private int bucketPrefixLength;
        private boolean bucketPrefixLengthSet;
        private @NotNull RateLimit budget = RateLimit.UNLIMITED;
        private @NotNull SubnetSelectionStrategy strategy = SubnetSelectionStrategy.RANDOM_REJECT_SAMPLE;
        private double softCapFraction = 0.9;
        private int maxRejectSamples = 16;

        /**
         * Sets the source prefix from a parsed {@link IpPrefix}.
         *
         * @param sourcePrefix the source prefix
         * @return this builder
         */
        public @NotNull Builder sourcePrefix(@NotNull IpPrefix sourcePrefix) {
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
            return this.sourcePrefix(IpPrefix.parse(cidr));
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
         * Sets the per-bucket budget.
         *
         * @param budget the rate limit applied to each contained bucket
         * @return this builder
         */
        public @NotNull Builder budget(@NotNull RateLimit budget) {
            this.budget = budget;
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
                this.budget,
                this.strategy,
                this.softCapFraction,
                this.maxRejectSamples
            );
        }

    }

}
