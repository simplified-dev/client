package dev.simplified.client.subnet.pool;

import dev.simplified.annotations.Getter;
import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.request.Contract;
import dev.simplified.client.route.RouteDiscovery;
import dev.simplified.client.subnet.IPv6Prefix;
import dev.simplified.client.subnet.SubnetRotation;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * {@link SubnetBucketPool} for the case where the source prefix is larger than
 * (numerically shorter than) the bucket prefix length.
 * <p>
 * Materializes {@link SubnetBucket} instances lazily into a sparse
 * {@link ConcurrentMap} keyed by the bucket's subnet, so memory cost scales
 * with the number of <em>actually used</em> subnets rather than the total
 * theoretical fan-out. Selection is dispatched to one of three algorithms based
 * on {@link SubnetRotation#strategy()}.
 * <p>
 * When every bucket is saturated, {@link #selectClient()} throws a refusal naming
 * the source prefix and carrying the policy of an exhausted rate-limit bucket a
 * saturated bucket's client reports, as {@link Client#findRateLimitedPolicy()} finds
 * it. When none reports one, the refusal carries the policy a saturated bucket's
 * client holds for its type-level route, as {@link Client#getRateLimit()} reads it,
 * or the anchor route's declared policy when none holds one. A bucket chosen while
 * its client could serve, and spent before the call reaches it, refuses under its
 * own subnet as {@link SubnetBucket#selectClient()} does, and a retry may be served
 * by another bucket.
 *
 * @param <C> the contract interface type
 */
public final class FanOutBucketPool<C extends Contract> implements SubnetBucketPool<C> {

    @Getter
    private final @NotNull SubnetRotation rotation;
    private final @NotNull RateLimitManager sharedManager;
    private final @NotNull RouteDiscovery.Metadata anchorRoute;
    private final @NotNull ClientConfig<C> baseOptions;
    private final @NotNull UnaryOperator<ClientConfig.Builder<C>> mutator;
    private final @NotNull Predicate<Client<C>> availability;
    private final @NotNull ConcurrentMap<IPv6Prefix, SubnetBucket<C>> active = Concurrent.newMap();
    private final @NotNull AtomicReference<BigInteger> roundRobinCursor = new AtomicReference<>(BigInteger.ZERO);

    FanOutBucketPool(
        @NotNull SubnetRotation rotation,
        @NotNull RateLimitManager sharedManager,
        @NotNull RouteDiscovery.Metadata anchorRoute,
        @NotNull ClientConfig<C> baseOptions,
        @NotNull UnaryOperator<ClientConfig.Builder<C>> mutator,
        @NotNull Predicate<Client<C>> availability
    ) {
        this.rotation = rotation;
        this.sharedManager = sharedManager;
        this.anchorRoute = anchorRoute;
        this.baseOptions = baseOptions;
        this.mutator = mutator;
        this.availability = availability;
    }

    @Override
    public @NotNull Stream<SubnetBucket<C>> activeBuckets() {
        return this.active.values().stream();
    }

    @Override
    public @NotNull Client<C> selectClient() throws RateLimitException {
        SubnetBucket<C> bucket = switch (this.rotation.strategy()) {
            case RANDOM_REJECT_SAMPLE -> this.selectRandomRejectSample();
            case LEAST_USED -> this.selectLeastUsed();
            case ROUND_ROBIN -> this.selectRoundRobin();
        };
        return bucket.selectClient();
    }

    private @NotNull SubnetBucket<C> selectRandomRejectSample() {
        int max = this.rotation.maxRejectSamples();
        for (int i = 0; i < max; i++) {
            IPv6Prefix subnet = this.rotation.sourcePrefix().randomContainedSubnet(this.rotation.bucketPrefixLength());
            SubnetBucket<C> bucket = this.active.computeIfAbsent(subnet, this::newBucket);
            if (!bucket.isSaturated()) return bucket;
        }
        return this.leastUsedActiveOrThrow();
    }

    private @NotNull SubnetBucket<C> selectLeastUsed() {
        BigInteger totalBuckets = this.rotation.bucketCount();
        long activeMin = this.active.values().stream()
            .filter(b -> !b.isSaturated())
            .mapToLong(this::bucketCount)
            .min()
            .orElse(Long.MAX_VALUE);

        // A freshly materialized bucket has count 0. While unvisited subnets remain, prefer
        // materializing a fresh one over reusing an active bucket with count > 0. Random picks
        // can collide with already-materialized subnets, so retry up to maxRejectSamples times;
        // putIfAbsent's null return distinguishes a fresh insert from an existing collision.
        int attempts = this.rotation.maxRejectSamples();
        while (BigInteger.valueOf(this.active.size()).compareTo(totalBuckets) < 0
            && activeMin > 0
            && attempts-- > 0) {
            IPv6Prefix subnet = this.rotation.sourcePrefix().randomContainedSubnet(this.rotation.bucketPrefixLength());
            SubnetBucket<C> fresh = this.newBucket(subnet);
            SubnetBucket<C> prior = this.active.putIfAbsent(subnet, fresh);
            if (prior == null) return fresh;
        }

        return this.active.values().stream()
            .filter(b -> !b.isSaturated())
            .min(Comparator.comparingLong(this::bucketCount))
            .orElseThrow(this::saturationException);
    }

    private @NotNull SubnetBucket<C> selectRoundRobin() {
        BigInteger total = this.rotation.bucketCount();
        for (BigInteger i = BigInteger.ZERO; i.compareTo(total) < 0; i = i.add(BigInteger.ONE)) {
            BigInteger cursor = this.roundRobinCursor.getAndUpdate(c -> c.add(BigInteger.ONE).mod(total));
            IPv6Prefix subnet = this.rotation.sourcePrefix().subnetAt(this.rotation.bucketPrefixLength(), cursor);
            SubnetBucket<C> bucket = this.active.computeIfAbsent(subnet, this::newBucket);
            if (!bucket.isSaturated()) return bucket;
        }
        throw this.saturationException();
    }

    private @NotNull SubnetBucket<C> leastUsedActiveOrThrow() {
        return this.active.values().stream()
            .filter(b -> !b.isSaturated())
            .min(Comparator.comparingLong(this::bucketCount))
            .orElseThrow(this::saturationException);
    }

    /**
     * Returns the request count tracked against this bucket's subnet for the pool's anchor route,
     * sampled from the shared {@link RateLimitManager}. Resolves the bucket's
     * {@linkplain SubnetBucket#getAnchorBucketKey() precomputed anchor bucket key} through
     * {@link RateLimitManager#getBucketKey(String)}, so a route whose server names its quota is
     * read from that quota's bucket - no per-call string composition. Used by
     * {@link #selectLeastUsed()} to compare bucket load.
     */
    private long bucketCount(@NotNull SubnetBucket<C> bucket) {
        return this.sharedManager.getRequestCount(this.sharedManager.getBucketKey(bucket.getAnchorBucketKey()));
    }

    /**
     * Builds the refusal thrown when every bucket is saturated, naming the source prefix.
     * <p>
     * The refusal carries the policy of an exhausted rate-limit bucket a saturated bucket's client
     * reports; when none reports one, the policy a saturated bucket's client holds for its
     * type-level route, or the anchor route's declared policy when none holds one.
     *
     * @return the refusal
     */
    private @NotNull RateLimitException saturationException() {
        List<SubnetBucket<C>> saturated = this.active.values().stream()
            .filter(SubnetBucket::isSaturated)
            .toList();

        RateLimit spent = saturated.stream()
            .map(SubnetBucket::findExhaustedRateLimit)
            .flatMap(Optional::stream)
            .findFirst()
            .or(() -> saturated.stream()
                .map(SubnetBucket::findAnchorRateLimit)
                .flatMap(Optional::stream)
                .findFirst()
            )
            .orElse(this.anchorRoute.getRateLimit());

        return new RateLimitException(this.rotation.sourcePrefix().toString(), spent);
    }

    private @NotNull SubnetBucket<C> newBucket(@NotNull IPv6Prefix subnet) {
        return new SubnetBucket<>(
            subnet,
            this.anchorRoute.getRoute() + "@" + subnet,
            this.anchorRoute.getRateLimit(),
            this.baseOptions,
            this.mutator,
            this.availability
        );
    }

}
