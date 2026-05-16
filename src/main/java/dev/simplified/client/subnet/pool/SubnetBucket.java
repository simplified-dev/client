package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.Proxy;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.request.Contract;
import dev.simplified.client.subnet.IPv6Prefix;
import dev.simplified.client.subnet.SubnetRotation;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Runtime state for a single rate-limit-relevant subnet within a {@link SubnetBucketPool}.
 * <p>
 * Each bucket owns one {@link IPv6Prefix} subnet and a sub-pool of {@link Client} instances bound
 * to random addresses inside that subnet. Buckets are materialized lazily by their containing
 * pool the first time the selection strategy chooses their subnet, so memory cost scales with
 * active subnets rather than the total number of contained subnets in the source prefix.
 * <p>
 * Rate-limit counters live in the shared {@link RateLimitManager}
 * held by every spawned {@link Client} and updated by the request and response interceptors at
 * request time. The bucket layer's saturation decision delegates to its availability predicate -
 * "no Client in this bucket can serve a request right now" - so soft-cap and hard-limit logic
 * stays entirely in rate-limit territory.
 * <p>
 * The bucket layer is internal to the {@link Proxy} mechanism - callers should not construct or
 * interact with buckets directly.
 *
 * @param <C> the contract interface type
 * @see SubnetBucketPool
 * @see SubnetRotation
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class SubnetBucket<C extends Contract> {

    /**
     * The subnet this bucket owns. All addresses bound by clients in this bucket fall inside this
     * prefix.
     */
    @Getter
    private final @NotNull IPv6Prefix subnet;

    /**
     * The precomputed rate-limit bucket key for this bucket's subnet under the pool's anchor route,
     * supplied by the {@link SubnetBucketPool} at construction. Read by {@link FanOutBucketPool} on
     * every LEAST_USED scan iteration to look up the bucket's request count in the shared rate
     * limit manager without re-composing a string per call.
     */
    @Getter
    private final @NotNull String anchorBucketKey;

    private final @NotNull ClientConfig<C> baseOptions;
    private final @NotNull UnaryOperator<ClientConfig.Builder<C>> mutator;
    private final @NotNull Predicate<Client<C>> availability;
    private final @NotNull ConcurrentList<Client<C>> clients = Concurrent.newList();

    /**
     * Returns whether this bucket has any spawned client capable of serving a fresh request.
     * <p>
     * A bucket with no spawned clients is never saturated - the pool will spawn one on the next
     * {@link #selectClient()} call. Once at least one client exists, the bucket is saturated when
     * every existing client fails the availability predicate.
     *
     * @return {@code true} if there is at least one client and none pass the availability predicate
     */
    public boolean isSaturated() {
        return !this.clients.isEmpty() && this.clients.stream().noneMatch(this.availability);
    }

    /**
     * Returns the number of clients currently spawned in this bucket. Diagnostic only.
     *
     * @return the client count
     */
    public int getClientCount() {
        return this.clients.size();
    }

    /**
     * Selects an available client from this bucket's sub-pool, spawning a new one bound to a random
     * address within {@link #getSubnet()} if needed.
     * <p>
     * Existing clients are filtered by the availability predicate before fallback construction.
     * Newly spawned clients have the per-client mutator applied first, then the random source
     * address binding overrides any address set by the mutator.
     * <p>
     * Request counting happens at the request-interceptor level via the shared
     * {@link RateLimitManager}, not here - so {@code selectClient}
     * does not advance any counter.
     *
     * @return an available client, never {@code null}
     */
    public @NotNull Client<C> selectClient() {
        Optional<Client<C>> existing = this.clients.stream()
            .filter(this.availability)
            .findFirst();

        if (existing.isPresent()) return existing.get();

        Client<C> fresh = this.createClient();
        this.clients.add(fresh);
        return fresh;
    }

    private @NotNull Client<C> createClient() {
        ClientConfig.Builder<C> builder = this.mutator.apply(this.baseOptions.mutate());
        builder.withSubnetPrefix(this.subnet);
        builder.withInet6Address(this.subnet.randomAddress());
        return Client.create(builder.build());
    }

}
