package dev.simplified.client.subnet.pool;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.Proxy;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.request.Contract;
import dev.simplified.client.subnet.IPv6Prefix;
import dev.simplified.client.subnet.SubnetRotation;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Runtime state for a single rate-limit-relevant subnet within a {@link SubnetBucketPool}.
 * <p>
 * Each bucket owns one {@link IPv6Prefix} subnet and holds at most one {@link Client}, bound to a
 * random address inside that subnet and built on the bucket's first {@link #selectClient()}. A
 * second client would count against the same route-and-subnet keys of the shared
 * {@link RateLimitManager}, so it could never pass an availability predicate the first one fails;
 * the bucket refuses a request its client cannot serve instead of building another. Buckets are
 * materialized lazily by their containing pool the first time the selection strategy chooses
 * their subnet, so memory cost scales with active subnets rather than the total number of
 * contained subnets in the source prefix.
 * <p>
 * Rate-limit counters live in the shared {@link RateLimitManager} held by every client of the pool
 * and updated by the request and response interceptors at request time. The bucket layer's
 * saturation decision delegates to its availability predicate, so soft-cap and hard-limit logic
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
     * The subnet this bucket owns. The address the bucket's client binds falls inside this prefix.
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

    /**
     * The policy the pool's anchor route declares, which a refusal carries when the refused client
     * reports no exhausted bucket and holds no bucket for its type-level route.
     */
    private final @NotNull RateLimit anchorRateLimit;

    private final @NotNull ClientConfig<C> baseOptions;
    private final @NotNull UnaryOperator<ClientConfig.Builder<C>> mutator;
    private final @NotNull Predicate<Client<C>> availability;

    /**
     * The monitor the bucket's one client is built under.
     */
    private final @NotNull Object lock = new Object();

    /**
     * The bucket's one client, empty until the first {@link #selectClient()} builds it.
     */
    private volatile @NotNull Optional<Client<C>> client = Optional.empty();

    /**
     * Returns whether this bucket's client is unable to serve a fresh request.
     * <p>
     * A bucket that has not built its client is never saturated - the next {@link #selectClient()}
     * builds it. Once built, the bucket is saturated while its client fails the availability
     * predicate.
     *
     * @return {@code true} if the bucket holds a client and it fails the availability predicate
     */
    public boolean isSaturated() {
        return this.client
            .map(held -> !this.availability.test(held))
            .orElse(false);
    }

    /**
     * Returns the number of clients this bucket holds - {@code 0} until the first
     * {@link #selectClient()} builds its client, {@code 1} after. Diagnostic only.
     *
     * @return the client count
     */
    public int getClientCount() {
        return this.client.isPresent() ? 1 : 0;
    }

    /**
     * Selects this bucket's client, building it on the first call.
     * <p>
     * The client is built once, under a lock, so concurrent first callers all receive the same
     * instance. It has the per-client mutator applied first, then a random source address within
     * {@link #subnet} overrides any address the mutator set. Every call, the first included, tests
     * the client against the availability predicate and refuses a client that fails it rather than
     * building another.
     * <p>
     * Request counting happens at the request-interceptor level via the shared
     * {@link RateLimitManager}, not here - so {@code selectClient} does not advance any counter.
     *
     * @return the bucket's client, never {@code null}
     * @throws RateLimitException if the client fails the availability predicate, naming this
     *     bucket's subnet and carrying the policy of the exhausted rate-limit bucket
     *     {@link Client#findRateLimitedPolicy()} finds on the client; when it finds none, the
     *     policy {@link Client#getRateLimit()} reads, or the anchor route's declared policy while
     *     the client holds no bucket for its type-level route
     */
    public @NotNull Client<C> selectClient() throws RateLimitException {
        Client<C> held = this.getOrBuildClient();

        if (!this.availability.test(held)) {
            RateLimit spent = held.findRateLimitedPolicy()
                .or(held::getRateLimit)
                .orElse(this.anchorRateLimit);

            throw new RateLimitException(this.subnet.toString(), spent);
        }

        return held;
    }

    /**
     * Finds the policy of the exhausted rate-limit bucket this bucket's client reports, as
     * {@link Client#findRateLimitedPolicy()} finds it.
     *
     * @return the exhausted bucket's policy, or empty when the bucket holds no client or its
     *     client reports no exhausted bucket
     */
    @NotNull Optional<RateLimit> findExhaustedRateLimit() {
        return this.client.flatMap(Client::findRateLimitedPolicy);
    }

    /**
     * Finds the policy the type-level route's rate-limit bucket of this bucket's client enforces,
     * as {@link Client#getRateLimit()} reads it.
     *
     * @return the policy, or empty when the bucket holds no client or its client holds no bucket
     *     for its type-level route
     */
    @NotNull Optional<RateLimit> findAnchorRateLimit() {
        return this.client.flatMap(Client::getRateLimit);
    }

    /**
     * Returns this bucket's client, building it under {@link #lock} when the bucket holds none.
     *
     * @return the bucket's one client
     */
    private @NotNull Client<C> getOrBuildClient() {
        Optional<Client<C>> held = this.client;

        if (held.isPresent())
            return held.get();

        synchronized (this.lock) {
            held = this.client;

            if (held.isEmpty()) {
                held = Optional.of(this.createClient());
                this.client = held;
            }

            return held.get();
        }
    }

    private @NotNull Client<C> createClient() {
        ClientConfig.Builder<C> builder = this.mutator.apply(this.baseOptions.mutate());
        builder.withSubnetPrefix(this.subnet);
        builder.withInet6Address(this.subnet.randomAddress());
        return Client.create(builder.build());
    }

}
