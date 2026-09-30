package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.Proxy;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.request.Contract;
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
 * pool builds shares the proxy's one {@link RateLimitManager}, and a pool with no client able to serve a
 * request throws {@link RateLimitException} rather than building another.
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
     * @throws RateLimitException if no client of this pool can serve a request
     */
    @NotNull Client<C> selectClient() throws RateLimitException;

    /**
     * Constructs the {@link SubnetBucketPool} matching the prefix band of a present rotation, or a
     * {@link DirectClientPool} when the rotation is empty.
     *
     * @param <C> the contract interface type
     * @param rotation the rotation, or empty for one client on the host's default source address
     * @param sharedManager the shared rate-limit manager every spawned client will read and write
     * @param anchorRouteId the contract's type-level route, which count-based bucket selection reads
     *     and a pool's refusal names when no rotation is present
     * @param baseOptions the shared base options derived by every spawned client
     * @param mutator the per-client mutator applied to the base options before any address binding
     * @param availability the predicate a client passes when it can serve a request
     * @return a {@link SubnetBucketPool} matching the prefix band of a present {@code rotation}, or a
     *     {@link DirectClientPool} for an empty one
     */
    static <C extends Contract> @NotNull ClientPool<C> create(
        @NotNull Optional<SubnetRotation> rotation,
        @NotNull RateLimitManager sharedManager,
        @NotNull String anchorRouteId,
        @NotNull ClientConfig<C> baseOptions,
        @NotNull UnaryOperator<ClientConfig.Builder<C>> mutator,
        @NotNull Predicate<Client<C>> availability
    ) {
        return rotation
            .<ClientPool<C>>map(value -> SubnetBucketPool.create(value, sharedManager, anchorRouteId, baseOptions, mutator, availability))
            .orElseGet(() -> new DirectClientPool<>(anchorRouteId, baseOptions, mutator, availability));
    }

}
