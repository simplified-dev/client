package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.Proxy;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.request.Contract;
import dev.simplified.client.route.RouteDiscovery;
import dev.simplified.client.subnet.SubnetRotation;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Source of the {@link Client} a {@link Proxy} serves each call through.
 * <p>
 * {@link SubnetBucketPool} binds clients to random addresses inside the subnets a {@link SubnetRotation}
 * names; {@link DirectClientPool} holds one client on the host's default source address. Every client a
 * pool builds shares the proxy's one {@link RateLimitManager}, and a pool throws {@link RateLimitException}
 * rather than building another client. It refuses when no client can serve a request, and a pool spreading
 * its clients over several subnet buckets also refuses when the one bucket it chose was spent between its
 * saturation check and its selection; that refusal names the bucket's subnet, and a retry may be served by
 * another bucket. The refusal carries the policy the refused client holds for the rate-limit bucket it
 * spent, as {@link Client#findRateLimitedPolicy()} finds it, or the policy it holds for the contract's
 * type-level route when it reports none spent, as {@link Client#getRateLimit()} reads it, or that route's
 * declared policy while it holds no bucket for the route.
 *
 * @param <C> the contract interface type
 * @see Proxy
 * @see SubnetRotation
 */
public sealed interface ClientPool<C extends Contract> permits SubnetBucketPool, DirectClientPool {

    /**
     * Selects a client able to serve a request.
     *
     * @return an available client
     * @throws RateLimitException if no client of this pool can serve a request, or if the one subnet
     *     bucket a pool spreading its clients over several chose was spent between the pool's saturation
     *     check and its selection - naming that bucket's subnet, while another bucket may serve a retry
     */
    @NotNull Client<C> selectClient() throws RateLimitException;

    /**
     * Constructs the {@link SubnetBucketPool} matching the prefix band of a present rotation, or a
     * {@link DirectClientPool} when the rotation is empty.
     *
     * @param <C> the contract interface type
     * @param rotation the rotation, or empty for one client on the host's default source address
     * @param sharedManager the shared rate-limit manager every built client will read and write
     * @param anchorRoute the contract's type-level route, whose route count-based bucket selection reads
     *     and a pool's refusal names when no rotation is present, and whose declared policy a refusal
     *     carries when the refused client reports no exhausted bucket and holds no bucket for the route
     * @param baseOptions the shared base options derived by every built client
     * @param mutator the per-client mutator applied to the base options before any address binding
     * @param availability the predicate a client passes when it can serve a request
     * @return a {@link SubnetBucketPool} matching the prefix band of a present {@code rotation}, or a
     *     {@link DirectClientPool} for an empty one
     */
    static <C extends Contract> @NotNull ClientPool<C> create(
        @NotNull Optional<SubnetRotation> rotation,
        @NotNull RateLimitManager sharedManager,
        @NotNull RouteDiscovery.Metadata anchorRoute,
        @NotNull ClientConfig<C> baseOptions,
        @NotNull UnaryOperator<ClientConfig.Builder<C>> mutator,
        @NotNull Predicate<Client<C>> availability
    ) {
        return rotation
            .<ClientPool<C>>map(value -> SubnetBucketPool.create(value, sharedManager, anchorRoute, baseOptions, mutator, availability))
            .orElseGet(() -> new DirectClientPool<>(anchorRoute, baseOptions, mutator, availability));
    }

}
