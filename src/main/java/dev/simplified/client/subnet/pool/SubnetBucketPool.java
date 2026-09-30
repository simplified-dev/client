package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.request.Contract;
import dev.simplified.client.route.RouteDiscovery;
import dev.simplified.client.subnet.SubnetRotation;
import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * Strategy interface for selecting a subnet {@link SubnetBucket} given a configured
 * {@link SubnetRotation}.
 * <p>
 * Three implementations cover the three relationships between the source prefix
 * and the bucket prefix length:
 * <ul>
 *   <li>{@link FanOutBucketPool} - source is larger than a single bucket
 *       (sparse fan-out across many contained subnets).</li>
 *   <li>{@link SingleBucketPool} - source equals one bucket (no /bucket-level
 *       rotation; one client bound to a random address inside the source).</li>
 *   <li>{@link PassThroughBucketPool} - source is smaller than a bucket (rotation
 *       gains nothing at this dimension; like the single bucket, it refuses a
 *       request once its bucket is saturated).</li>
 * </ul>
 * Each bucket holds one client, so a pool refuses a saturated bucket rather than
 * building a second client inside it.
 *
 * @param <C> the contract interface type
 * @see SubnetRotation
 * @see SubnetBucket
 */
public sealed interface SubnetBucketPool<C extends Contract> extends ClientPool<C>
    permits FanOutBucketPool, SingleBucketPool, PassThroughBucketPool {

    /**
     * Returns the immutable rotation configuration backing this pool.
     *
     * @return the rotation config
     */
    @NotNull SubnetRotation getRotation();

    /**
     * Returns a stream of all currently materialized buckets.
     * <p>
     * For {@link FanOutBucketPool} this is the sparse set of buckets that have
     * actually been selected so far; for {@link SingleBucketPool} and
     * {@link PassThroughBucketPool} this is exactly one bucket.
     *
     * @return a stream of live buckets
     */
    @NotNull Stream<SubnetBucket<C>> activeBuckets();

    /**
     * Selects an available {@link Client} from a bucket with remaining budget.
     *
     * @return an available client bound to an address within a non-saturated
     *         bucket's subnet
     * @throws RateLimitException if no bucket has remaining budget, or if the bucket
     *         chosen was spent between the pool's saturation check and its selection -
     *         naming that bucket's subnet, while another bucket may serve a retry
     */
    @NotNull Client<C> selectClient() throws RateLimitException;

    /**
     * Constructs the appropriate pool implementation for the given rotation configuration.
     *
     * @param <C> the contract interface type
     * @param rotation the immutable rotation configuration
     * @param sharedManager the shared rate-limit manager every built client will read and write
     * @param anchorRoute the route used as the "default" route for count-based bucket selection
     *                    (typically the contract's type-level route), whose declared policy a
     *                    refusal carries when the refused client reports no exhausted bucket
     *                    and holds no bucket for the route
     * @param baseOptions the shared base options derived by every built client
     * @param mutator the per-client mutator applied before address binding
     * @param availability the predicate a bucket's client passes when it can serve a request
     * @return a pool implementation matching the prefix-band of {@code rotation}
     */
    static <C extends Contract> @NotNull SubnetBucketPool<C> create(
        @NotNull SubnetRotation rotation,
        @NotNull RateLimitManager sharedManager,
        @NotNull RouteDiscovery.Metadata anchorRoute,
        @NotNull ClientConfig<C> baseOptions,
        @NotNull UnaryOperator<ClientConfig.Builder<C>> mutator,
        @NotNull Predicate<Client<C>> availability
    ) {
        int srcLen = rotation.sourcePrefix().length();
        int bucketLen = rotation.bucketPrefixLength();
        if (srcLen < bucketLen) return new FanOutBucketPool<>(rotation, sharedManager, anchorRoute, baseOptions, mutator, availability);
        if (srcLen == bucketLen) return new SingleBucketPool<>(rotation, anchorRoute, baseOptions, mutator, availability);
        return new PassThroughBucketPool<>(rotation, anchorRoute, baseOptions, mutator, availability);
    }

}
