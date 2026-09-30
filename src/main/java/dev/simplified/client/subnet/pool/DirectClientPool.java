package dev.simplified.client.subnet.pool;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Lazy;
import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.Proxy;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.request.Contract;
import dev.simplified.client.route.RouteDiscovery;
import dev.simplified.client.subnet.SubnetRotation;
import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * {@link ClientPool} for a {@link Proxy} built without a {@link SubnetRotation}.
 * <p>
 * Holds one {@link Client}, built on the first {@link #selectClient()}, that binds no source address the
 * per-client mutator does not name and carries no subnet prefix, so it sends from the host's default
 * address and counts against the bare route keys of the shared {@link RateLimitManager}. A second client
 * would send from the same address against the same keys, so the pool never builds one: when the
 * availability predicate rejects the client, {@link #selectClient()} throws a refusal naming the anchor
 * route and carrying the policy of the exhausted rate-limit bucket {@link Client#findRateLimitedPolicy()}
 * finds on the client. When it finds none, the refusal carries the policy {@link Client#getRateLimit()}
 * reads, or the anchor route's declared policy while the client holds no bucket for it.
 *
 * @param <C> the contract interface type
 */
public final class DirectClientPool<C extends Contract> implements ClientPool<C> {

    /**
     * The contract's type-level route, which a refusal names as its bucket and whose declared policy
     * it carries when the refused client reports no exhausted bucket and holds no bucket for the route.
     */
    private final @NotNull RouteDiscovery.Metadata anchorRoute;

    /**
     * The predicate the client passes when it can serve a request.
     */
    private final @NotNull Predicate<Client<C>> availability;

    /**
     * The pool's one client, built on first read.
     */
    @Lazy(access = AccessLevel.PRIVATE)
    private final @NotNull Client<C> client;

    /**
     * Constructs a pool whose one client is built on the first {@link #selectClient()}.
     *
     * @param anchorRoute the contract's type-level route, which a refusal names as its bucket and whose
     *     declared policy it carries when the refused client reports no exhausted bucket and holds no
     *     bucket for the route
     * @param baseOptions the shared base options the client derives from
     * @param mutator the per-client mutator applied to the base options before the client is built
     * @param availability the predicate the client passes when it can serve a request
     */
    DirectClientPool(
        @NotNull RouteDiscovery.Metadata anchorRoute,
        @NotNull ClientConfig<C> baseOptions,
        @NotNull UnaryOperator<ClientConfig.Builder<C>> mutator,
        @NotNull Predicate<Client<C>> availability
    ) {
        this.anchorRoute = anchorRoute;
        this.availability = availability;
        this.client = Client.create(mutator.apply(baseOptions.mutate()).build());
    }

    /** {@inheritDoc} */
    @Override
    public @NotNull Client<C> selectClient() throws RateLimitException {
        Client<C> client = this.getClient();

        if (!this.availability.test(client)) {
            RateLimit spent = client.findRateLimitedPolicy()
                .or(client::getRateLimit)
                .orElse(this.anchorRoute.getRateLimit());

            throw new RateLimitException(this.anchorRoute.getRoute(), spent);
        }

        return client;
    }

}
