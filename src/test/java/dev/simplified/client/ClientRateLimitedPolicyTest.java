package dev.simplified.client;

import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.ratelimit.RateLimitConfig;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.request.Contract;
import dev.simplified.client.route.Route;
import dev.simplified.client.route.RouteDiscovery;
import dev.simplified.gson.GsonSettings;
import feign.RequestLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Tests that {@link Client#findRateLimitedPolicy()} finds no policy while every route has budget
 * left, finds the policy an exhausted route's bucket enforces - the server's where a server's
 * headers gave it one, so never the unlimited sentinel of a route that declares no limit - chooses
 * the exhausted bucket whose window ends last whichever route it belongs to, and reaches a route's
 * bucket through the quota its latest response named; and that {@link Client#getRateLimit()} reads
 * the policy the type-level route's bucket holds. The client is built over a transport that answers
 * everything with an empty {@code 200}.
 */
class ClientRateLimitedPolicyTest {

    private static final String ANCHOR = "127.0.0.1:0";

    private static final String SECOND = "127.0.0.1:0/second";

    private static final String THIRD = "127.0.0.1:0/third";

    private static final String OPEN = "127.0.0.1:0/open";

    @Route(value = ANCHOR, rateLimit = @RateLimitConfig(limit = 100, window = 60))
    interface Resource extends Contract {

        @Route(value = SECOND, rateLimit = @RateLimitConfig(limit = 3, window = 30))
        @RequestLine("GET /second")
        String second();

        @Route(value = THIRD, rateLimit = @RateLimitConfig(limit = 2, window = 120))
        @RequestLine("GET /third")
        String third();

        @Route(OPEN)
        @RequestLine("GET /open")
        String open();

    }

    private final Client<Resource> client = new Client<>(
        ClientConfig.builder(Resource.class, GsonSettings.builder().build()).build(),
        (request, options) -> feign.Response.builder()
            .status(200)
            .reason("OK")
            .request(request)
            .headers(Map.of())
            .body(new byte[0])
            .build()
    );

    private RouteDiscovery.Metadata route(String route) {
        return this.client.getRouteDiscovery().findByRoute(route).orElseThrow();
    }

    private void spend(RouteDiscovery.Metadata route, long requests) {
        for (long i = 0; i < requests; i++)
            this.client.getRateLimitManager().trackRequest(route.getBucketKey(), route.getRateLimit());
    }

    private void exhaust(RouteDiscovery.Metadata route) {
        this.spend(route, route.getRateLimit().getLimit());
    }

    private static Map<String, Collection<String>> headers(long limit, long remaining, long resetSeconds) {
        return Map.of(
            "X-RateLimit-Limit", List.of(String.valueOf(limit)),
            "X-RateLimit-Remaining", List.of(String.valueOf(remaining)),
            "X-RateLimit-Reset", List.of(String.valueOf(resetSeconds))
        );
    }

    private void answer(RouteDiscovery.Metadata route, Map<String, Collection<String>> headers, long now) {
        RateLimitManager manager = this.client.getRateLimitManager();
        manager.updateFromHeaders(route, null, headers, now, manager.nextSequence());
    }

    @Test
    @DisplayName("No policy is found while every route has budget left")
    void noPolicyWhileBudgetRemains() {
        assertThat(this.client.findRateLimitedPolicy().isEmpty(), is(true));

        RouteDiscovery.Metadata second = this.route(SECOND);
        this.spend(second, second.getRateLimit().getLimit() - 1);

        assertThat(this.client.findRateLimitedPolicy().isEmpty(), is(true));
    }

    @Test
    @DisplayName("The policy of an exhausted method-level route's bucket is found")
    void findsExhaustedMethodRoutePolicy() {
        RouteDiscovery.Metadata second = this.route(SECOND);
        this.exhaust(second);

        RateLimit found = this.client.findRateLimitedPolicy().orElseThrow();
        assertThat(found, is(sameInstance(second.getRateLimit())));
        assertThat(found.getLimit(), is(3L));
        assertThat(found.getResetSeconds(), is(30L));
    }

    @Test
    @DisplayName("A route declaring no limit, spent under a server's policy, reports the server's policy rather than the unlimited sentinel")
    void routeDeclaringNoLimitReportsServerPolicy() {
        RateLimitManager manager = this.client.getRateLimitManager();
        RouteDiscovery.Metadata open = this.route(OPEN);
        long now = System.currentTimeMillis();
        assertThat(open.getRateLimit().isUnlimited(), is(true));

        // The request side opens the bucket under the declared policy, then the server names its own.
        this.spend(open, 1);
        assertThat(manager.getRateLimit(open.getBucketKey()).orElseThrow().isUnlimited(), is(true));
        this.answer(open, headers(5, 0, 60), now);

        RateLimit found = this.client.findRateLimitedPolicy().orElseThrow();
        assertThat(found.isUnlimited(), is(false));
        assertThat(found.getLimit(), is(5L));
        assertThat(found.getResetEpochMillis(), is(now + 60_000L));
    }

    @Test
    @DisplayName("Of two exhausted method-level routes, the one whose window ends last is found, whichever it is")
    void choosesLaterWindowAmongMethodRoutes() {
        RouteDiscovery.Metadata second = this.route(SECOND);
        RouteDiscovery.Metadata third = this.route(THIRD);
        this.exhaust(second);
        this.exhaust(third);

        // The type-level route has budget; the third route's 120-second window outlasts the second's 30.
        assertThat(this.client.isRateLimited(), is(false));
        assertThat(this.client.findRateLimitedPolicy().orElseThrow(), is(sameInstance(third.getRateLimit())));

        // A server's reset ten minutes out now ends the second route's window last.
        long now = System.currentTimeMillis();
        this.answer(second, headers(3, 0, 600), now);

        RateLimit found = this.client.findRateLimitedPolicy().orElseThrow();
        assertThat(found.getLimit(), is(3L));
        assertThat(found.getResetEpochMillis(), is(now + 600_000L));
    }

    @Test
    @DisplayName("The type-level route is found when its window ends last, and not merely for coming first")
    void typeLevelRouteFoundOnlyWhenItsWindowEndsLast() {
        RouteDiscovery.Metadata anchor = this.client.getRouteDiscovery().getDefaultRoute();
        RouteDiscovery.Metadata second = this.route(SECOND);
        RouteDiscovery.Metadata third = this.route(THIRD);
        this.exhaust(second);
        this.exhaust(anchor);

        assertThat(this.client.findRateLimitedPolicy().orElseThrow(), is(sameInstance(anchor.getRateLimit())));

        this.exhaust(third);
        assertThat(this.client.findRateLimitedPolicy().orElseThrow(), is(sameInstance(third.getRateLimit())));
    }

    @Test
    @DisplayName("A route whose latest response named an exhausted quota reports the quota bucket's policy")
    void findsPolicyThroughExhaustedQuota() {
        RateLimitManager manager = this.client.getRateLimitManager();
        RouteDiscovery.Metadata second = this.route(SECOND);
        long now = System.currentTimeMillis();
        Map<String, Collection<String>> headers = Map.of(
            "X-RateLimit-Limit", List.of("5"),
            "X-RateLimit-Remaining", List.of("0"),
            "X-RateLimit-Reset", List.of("60"),
            "X-RateLimit-Resource", List.of("core")
        );

        this.answer(second, headers, now);

        assertThat(manager.hasBucket(second.getBucketKey()), is(false));
        RateLimit found = this.client.findRateLimitedPolicy().orElseThrow();
        assertThat(found.getLimit(), is(5L));
        assertThat(found.getResetEpochMillis(), is(now + 60_000L));
    }

    @Test
    @DisplayName("getRateLimit reads the policy the type-level bucket holds, and nothing before the bucket exists")
    void getRateLimitReadsHeldPolicy() {
        RouteDiscovery.Metadata anchor = this.client.getRouteDiscovery().getDefaultRoute();
        assertThat(this.client.getRateLimit().isEmpty(), is(true));

        this.spend(anchor, 1);
        assertThat(this.client.getRateLimit().orElseThrow(), is(sameInstance(anchor.getRateLimit())));

        long now = System.currentTimeMillis();
        this.answer(anchor, headers(50, 10, 90), now);

        RateLimit held = this.client.getRateLimit().orElseThrow();
        assertThat(held.getLimit(), is(50L));
        assertThat(held.getResetEpochMillis(), is(now + 90_000L));
        assertThat(this.client.findRateLimitedPolicy().isEmpty(), is(true));
    }

}
