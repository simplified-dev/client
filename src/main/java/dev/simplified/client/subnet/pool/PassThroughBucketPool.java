package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.request.Contract;
import dev.simplified.client.subnet.SubnetRotation;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * {@link SubnetBucketPool} for the case where the source prefix is smaller
 * than (numerically longer than) the bucket prefix length.
 * <p>
 * Holds one {@link Bucket} whose subnet is the source prefix and whose budget
 * is forced to {@link RateLimit#UNLIMITED}. Bucket-level budget tracking is
 * effectively bypassed because the source prefix shares a single upstream
 * bucket with everything else in the same /bucket-prefix subnet, so rotation
 * within the source prefix cannot relieve upstream pressure. Random addresses
 * are still bound for unpredictability.
 *
 * @param <C> the contract interface type
 */
public final class PassThroughBucketPool<C extends Contract> implements SubnetBucketPool<C> {

    @Getter
    private final @NotNull SubnetRotation rotation;
    private final @NotNull Bucket<C> bucket;

    PassThroughBucketPool(
        @NotNull SubnetRotation rotation,
        @NotNull ClientConfig<C> baseOptions,
        @NotNull UnaryOperator<ClientConfig.Builder<C>> mutator,
        @NotNull Predicate<Client<C>> availability
    ) {
        this.rotation = rotation;
        this.bucket = new Bucket<>(
            rotation.sourcePrefix(),
            RateLimit.UNLIMITED,
            Long.MAX_VALUE,
            baseOptions,
            mutator,
            availability
        );
    }

    @Override
    public @NotNull Stream<Bucket<C>> activeBuckets() {
        return Stream.of(this.bucket);
    }

    @Override
    public @NotNull Client<C> selectClient() throws RateLimitException {
        return this.bucket.selectClient();
    }

}
