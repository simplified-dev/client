package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.request.Contract;
import dev.simplified.client.subnet.IpPrefix;
import dev.simplified.client.subnet.SubnetRotation;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import lombok.AccessLevel;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * {@link SubnetBucketPool} for the case where the source prefix is larger than
 * (numerically shorter than) the bucket prefix length.
 * <p>
 * Materializes {@link Bucket} instances lazily into a sparse
 * {@link ConcurrentMap} keyed by the bucket's subnet, so memory cost scales
 * with the number of <em>actually used</em> subnets rather than the total
 * theoretical fan-out. Selection is dispatched to one of three algorithms based
 * on {@link SubnetRotation#strategy()}.
 *
 * @param <C> the contract interface type
 */
public final class FanOutBucketPool<C extends Contract> implements SubnetBucketPool<C> {

    @Getter
    private final @NotNull SubnetRotation rotation;

    @Getter(AccessLevel.NONE)
    private final @NotNull ClientConfig<C> baseOptions;

    @Getter(AccessLevel.NONE)
    private final @NotNull UnaryOperator<ClientConfig.Builder<C>> mutator;

    @Getter(AccessLevel.NONE)
    private final @NotNull Predicate<Client<C>> availability;

    @Getter(AccessLevel.NONE)
    private final @NotNull ConcurrentMap<IpPrefix, Bucket<C>> active = Concurrent.newMap();

    @Getter(AccessLevel.NONE)
    private final @NotNull AtomicReference<BigInteger> roundRobinCursor = new AtomicReference<>(BigInteger.ZERO);

    FanOutBucketPool(
        @NotNull SubnetRotation rotation,
        @NotNull ClientConfig<C> baseOptions,
        @NotNull UnaryOperator<ClientConfig.Builder<C>> mutator,
        @NotNull Predicate<Client<C>> availability
    ) {
        this.rotation = rotation;
        this.baseOptions = baseOptions;
        this.mutator = mutator;
        this.availability = availability;
    }

    @Override
    public @NotNull Stream<Bucket<C>> activeBuckets() {
        return this.active.values().stream();
    }

    @Override
    public @NotNull Client<C> selectClient() throws RateLimitException {
        Bucket<C> bucket = switch (this.rotation.strategy()) {
            case RANDOM_REJECT_SAMPLE -> this.selectRandomRejectSample();
            case LEAST_USED -> this.selectLeastUsed();
            case ROUND_ROBIN -> this.selectRoundRobin();
        };
        return bucket.selectClient();
    }

    private @NotNull Bucket<C> selectRandomRejectSample() {
        int max = this.rotation.maxRejectSamples();
        for (int i = 0; i < max; i++) {
            IpPrefix subnet = this.rotation.sourcePrefix().randomContainedSubnet(this.rotation.bucketPrefixLength());
            Bucket<C> bucket = this.active.computeIfAbsent(subnet, this::newBucket);
            if (!bucket.isSaturated()) return bucket;
        }
        return this.leastUsedActiveOrThrow();
    }

    private @NotNull Bucket<C> selectLeastUsed() {
        BigInteger totalBuckets = this.rotation.bucketCount();
        long activeMin = this.active.values().stream()
            .filter(b -> !b.isSaturated())
            .mapToLong(Bucket::getCount)
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
            IpPrefix subnet = this.rotation.sourcePrefix().randomContainedSubnet(this.rotation.bucketPrefixLength());
            Bucket<C> fresh = this.newBucket(subnet);
            Bucket<C> prior = this.active.putIfAbsent(subnet, fresh);
            if (prior == null) return fresh;
        }

        return this.active.values().stream()
            .filter(b -> !b.isSaturated())
            .min(Comparator.comparingLong(Bucket::getCount))
            .orElseThrow(this::saturationException);
    }

    private @NotNull Bucket<C> selectRoundRobin() {
        BigInteger total = this.rotation.bucketCount();
        for (BigInteger i = BigInteger.ZERO; i.compareTo(total) < 0; i = i.add(BigInteger.ONE)) {
            BigInteger cursor = this.roundRobinCursor.getAndUpdate(c -> c.add(BigInteger.ONE).mod(total));
            IpPrefix subnet = this.rotation.sourcePrefix().subnetAt(this.rotation.bucketPrefixLength(), cursor);
            Bucket<C> bucket = this.active.computeIfAbsent(subnet, this::newBucket);
            if (!bucket.isSaturated()) return bucket;
        }
        throw this.saturationException();
    }

    private @NotNull Bucket<C> leastUsedActiveOrThrow() {
        return this.active.values().stream()
            .filter(b -> !b.isSaturated())
            .min(Comparator.comparingLong(Bucket::getCount))
            .orElseThrow(this::saturationException);
    }

    private @NotNull RateLimitException saturationException() {
        return new RateLimitException(this.rotation.sourcePrefix().toString(), this.rotation.budget());
    }

    private @NotNull Bucket<C> newBucket(@NotNull IpPrefix subnet) {
        return new Bucket<>(
            subnet,
            this.rotation.budget(),
            this.rotation.softCapThreshold(),
            this.baseOptions,
            this.mutator,
            this.availability
        );
    }

}
