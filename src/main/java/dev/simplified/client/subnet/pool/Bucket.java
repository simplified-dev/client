package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.Proxy;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.ratelimit.RateLimitBucket;
import dev.simplified.client.request.Contract;
import dev.simplified.client.subnet.IpPrefix;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lombok.AccessLevel;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Runtime state for a single rate-limit-relevant subnet within a
 * {@link SubnetBucketPool}.
 * <p>
 * Each bucket owns one {@link IpPrefix} subnet, a private {@link RateLimitBucket}
 * tracking the per-subnet request budget, and a sub-pool of {@link Client}
 * instances each bound to a random address within the subnet. Buckets are
 * materialized lazily by their containing pool the first time the selection
 * strategy chooses their subnet, so memory cost scales with active subnets
 * rather than the total number of contained subnets in the source prefix.
 * <p>
 * The bucket layer is internal to the {@link Proxy}
 * mechanism - callers should not construct or interact with buckets directly.
 *
 * @param <C> the contract interface type
 * @see SubnetBucketPool
 * @see SubnetRotation
 */
public final class Bucket<C extends Contract> {

    /**
     * The subnet this bucket owns. All addresses bound by clients in this
     * bucket fall inside this prefix.
     */
    @Getter
    private final @NotNull IpPrefix subnet;

    /**
     * The per-subnet request budget tracker.
     */
    @Getter
    private final @NotNull RateLimitBucket budget;

    @Getter(AccessLevel.NONE)
    private final long softCapThreshold;

    @Getter(AccessLevel.NONE)
    private final @NotNull ClientConfig<C> baseOptions;

    @Getter(AccessLevel.NONE)
    private final @NotNull UnaryOperator<ClientConfig.Builder<C>> mutator;

    @Getter(AccessLevel.NONE)
    private final @NotNull Predicate<Client<C>> availability;

    @Getter(AccessLevel.NONE)
    private final @NotNull ConcurrentList<Client<C>> clients = Concurrent.newList();

    Bucket(
        @NotNull IpPrefix subnet,
        @NotNull RateLimit budgetPolicy,
        long softCapThreshold,
        @NotNull ClientConfig<C> baseOptions,
        @NotNull UnaryOperator<ClientConfig.Builder<C>> mutator,
        @NotNull Predicate<Client<C>> availability
    ) {
        this.subnet = subnet;
        this.budget = new RateLimitBucket(budgetPolicy);
        this.softCapThreshold = softCapThreshold;
        this.baseOptions = baseOptions;
        this.mutator = mutator;
        this.availability = availability;
    }

    /**
     * Returns whether this bucket has reached its configured soft cap.
     * <p>
     * Buckets backed by an {@linkplain RateLimit#isUnlimited() unlimited} policy
     * are never saturated.
     *
     * @return {@code true} if the current request count is at or above the soft
     *         cap threshold
     */
    public boolean isSaturated() {
        return this.budget.getCount() >= this.softCapThreshold;
    }

    /**
     * Returns the current request count tracked against this bucket's budget.
     *
     * @return the count within the current window
     */
    public long getCount() {
        return this.budget.getCount();
    }

    /**
     * Returns the number of remaining requests before this bucket's hard limit.
     *
     * @return remaining requests, or {@link Long#MAX_VALUE} if unlimited
     */
    public long getRemaining() {
        return this.budget.getRemaining();
    }

    /**
     * Selects an available client from this bucket's sub-pool, spawning a new
     * one bound to a random address within {@link #getSubnet()} if needed, and
     * tracks the selection against this bucket's budget.
     * <p>
     * Existing clients are filtered by the availability predicate before
     * fallback construction. Newly spawned clients have the per-client mutator
     * applied first, then the random source address binding overrides any
     * address set by the mutator.
     *
     * @return an available client, never {@code null}
     */
    public @NotNull Client<C> selectClient() {
        Optional<Client<C>> existing = this.clients.stream()
            .filter(this.availability)
            .findFirst();

        Client<C> chosen;
        if (existing.isPresent()) {
            chosen = existing.get();
        } else {
            chosen = this.createClient();
            this.clients.add(chosen);
        }

        this.budget.trackRequest();
        return chosen;
    }

    private @NotNull Client<C> createClient() {
        ClientConfig.Builder<C> builder = this.mutator.apply(this.baseOptions.mutate());
        builder.withInet6Address(this.subnet.randomAddress());
        return Client.create(builder.build());
    }

}
