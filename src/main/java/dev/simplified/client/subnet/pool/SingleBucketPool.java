package dev.simplified.client.subnet.pool;

import dev.simplified.annotations.Getter;
import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.request.Contract;
import dev.simplified.client.subnet.SubnetRotation;
import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * {@link SubnetBucketPool} for the case where the source prefix length equals
 * the bucket prefix length.
 * <p>
 * Holds exactly one {@link SubnetBucket} whose subnet is the configured source
 * prefix. There is no bucket-level selection - all rotation happens at the
 * address-within-bucket dimension. When the bucket exceeds its soft cap,
 * {@link #selectClient()} throws.
 *
 * @param <C> the contract interface type
 */
public final class SingleBucketPool<C extends Contract> implements SubnetBucketPool<C> {

    @Getter
    private final @NotNull SubnetRotation rotation;
    private final @NotNull SubnetBucket<C> bucket;

    SingleBucketPool(
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
        if (this.bucket.isSaturated())
            throw new RateLimitException(this.bucket.getSubnet().toString(), RateLimit.UNLIMITED);
        return this.bucket.selectClient();
    }

}
