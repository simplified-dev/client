package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.request.Contract;
import dev.simplified.client.subnet.SubnetRotation;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * {@link SubnetBucketPool} for the case where the source prefix is smaller (numerically longer
 * than) the bucket prefix length.
 * <p>
 * Holds one {@link SubnetBucket} whose subnet is the source prefix. The source prefix shares a
 * single upstream bucket with everything else in the same /bucket-prefix subnet, so rotation
 * within the source prefix cannot relieve upstream pressure; saturation here only fires if the
 * availability predicate fails on every spawned client. Random addresses are still bound for
 * unpredictability.
 *
 * @param <C> the contract interface type
 */
public final class PassThroughBucketPool<C extends Contract> implements SubnetBucketPool<C> {

    @Getter
    private final @NotNull SubnetRotation rotation;
    private final @NotNull SubnetBucket<C> bucket;

    PassThroughBucketPool(
        @NotNull SubnetRotation rotation,
        @NotNull String anchorRouteId,
        @NotNull ClientConfig<C> baseOptions,
        @NotNull UnaryOperator<ClientConfig.Builder<C>> mutator,
        @NotNull Predicate<Client<C>> availability
    ) {
        this.rotation = rotation;
        this.bucket = new SubnetBucket<>(
            rotation.sourcePrefix(),
            anchorRouteId + "@" + rotation.sourcePrefix(),
            baseOptions,
            mutator,
            availability
        );
    }

    @Override
    public @NotNull Stream<SubnetBucket<C>> activeBuckets() {
        return Stream.of(this.bucket);
    }

    @Override
    public @NotNull Client<C> selectClient() throws RateLimitException {
        return this.bucket.selectClient();
    }

}
