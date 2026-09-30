package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.route.RouteDiscovery;
import dev.simplified.gson.GsonSettings;

import java.util.List;
import java.util.Map;

/**
 * Fixtures over {@link TwoRouteContract}: its routes and policies, its options, its anchor route,
 * an availability predicate checking both routes, a way to spend a route's bucket on a client, and
 * a way to apply a server's rate-limit headers to a route's bucket on a client of any contract.
 */
final class TwoRoutes {

    /**
     * The type-level route of {@link TwoRouteContract}.
     */
    static final String ANCHOR = "127.0.0.1:0";

    /**
     * The request limit of {@link #ANCHOR}.
     */
    static final long ANCHOR_LIMIT = 100L;

    /**
     * The window of {@link #ANCHOR}, in seconds.
     */
    static final long ANCHOR_WINDOW = 60L;

    /**
     * The route of {@link TwoRouteContract#second()}.
     */
    static final String SECOND = "127.0.0.1:0/second";

    /**
     * The request limit of {@link #SECOND}.
     */
    static final long SECOND_LIMIT = 3L;

    /**
     * The window of {@link #SECOND}, in seconds.
     */
    static final long SECOND_WINDOW = 30L;

    private TwoRoutes() {
    }

    /**
     * Builds options for {@link TwoRouteContract}.
     *
     * @return the options
     */
    static ClientConfig<TwoRouteContract> options() {
        return ClientConfig.builder(TwoRouteContract.class, GsonSettings.builder().build()).build();
    }

    /**
     * Resolves the anchor route a proxy over {@link TwoRouteContract} hands its pool.
     *
     * @return the type-level route
     */
    static RouteDiscovery.Metadata anchorRoute() {
        return new RouteDiscovery(options()).getDefaultRoute();
    }

    /**
     * Passes a client while neither route's bucket is exhausted.
     *
     * @param client the client to test
     * @return whether both routes have budget left
     */
    static boolean open(Client<TwoRouteContract> client) {
        return !client.isRateLimited() && !client.isRateLimited(() -> SECOND);
    }

    /**
     * Resolves the policy a client holds for one of the routes.
     *
     * @param client the client
     * @param route the route
     * @return the route's policy on that client
     */
    static RateLimit policyOf(Client<TwoRouteContract> client, String route) {
        return client.getRouteDiscovery().findByRoute(route).orElseThrow().getRateLimit();
    }

    /**
     * Spends a route's bucket on a client, as sending as many requests as its limit allows would.
     *
     * @param client the client whose bucket to spend
     * @param route the route whose bucket to spend
     */
    static void exhaust(Client<TwoRouteContract> client, String route) {
        RouteDiscovery.Metadata metadata = client.getRouteDiscovery().findByRoute(route).orElseThrow();

        for (long i = 0; i < metadata.getRateLimit().getLimit(); i++)
            client.getRateLimitManager().trackRequest(metadata.getBucketKey(), metadata.getRateLimit());
    }

    /**
     * Applies a server's rate-limit headers to a route's bucket on a client, as a response to a
     * request sent first through the route would: the request opens the bucket under the route's
     * declared policy, and the headers then replace it with the server's.
     *
     * @param client the client whose bucket the response reaches
     * @param route the route the request was sent through
     * @param limit the request limit the server names
     * @param remaining the requests the server reports remaining
     * @param resetSeconds the seconds until the server's quota resets
     * @param now the epoch-millisecond timestamp the response was received at
     */
    static void answer(Client<?> client, RouteDiscovery.Metadata route, long limit, long remaining, long resetSeconds, long now) {
        RateLimitManager manager = client.getRateLimitManager();
        manager.trackRequest(route.getBucketKey(), route.getRateLimit(), now);
        manager.updateFromHeaders(
            route,
            null,
            Map.of(
                "X-RateLimit-Limit", List.of(String.valueOf(limit)),
                "X-RateLimit-Remaining", List.of(String.valueOf(remaining)),
                "X-RateLimit-Reset", List.of(String.valueOf(resetSeconds))
            ),
            now,
            manager.nextSequence()
        );
    }

}
