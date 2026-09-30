package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.request.Contract;
import dev.simplified.client.route.RouteDiscovery;
import dev.simplified.client.subnet.IPv6Prefix;
import dev.simplified.gson.GsonSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that a {@link SubnetBucket} builds its one client on the first selection and never a second -
 * however many callers select it at once, and however often the client is refused, a client that
 * passed a saturation check just before the selection included - that a build that fails leaves
 * the bucket empty for the next selection to build, and that a refusal names the bucket's subnet
 * and carries the policy the client holds for the bucket it spent - a server's in place of a route's
 * declared lack of one - or for its type-level route, or the anchor route's declared policy.
 */
class SubnetBucketTest {

    private static final IPv6Prefix SUBNET = IPv6Prefix.parse("2001:db8:abcd:ef00::/56");

    private static final String ANCHOR_BUCKET_KEY = "127.0.0.1:0@2001:db8:abcd:ef00::/56";

    /**
     * Clients the buckets have built, counted by the mutator each build runs through.
     */
    private final AtomicInteger built = new AtomicInteger();

    private <C extends Contract> UnaryOperator<ClientConfig.Builder<C>> counting() {
        return builder -> {
            this.built.incrementAndGet();
            return builder;
        };
    }

    private SubnetBucket<TestContract> bucket(Predicate<Client<TestContract>> availability) {
        ClientConfig<TestContract> base = ClientConfig.builder(TestContract.class, GsonSettings.builder().build()).build();
        RouteDiscovery.Metadata anchor = new RouteDiscovery(base).getDefaultRoute();
        return new SubnetBucket<>(SUBNET, ANCHOR_BUCKET_KEY, anchor.getRateLimit(), base, this.counting(), availability);
    }

    private SubnetBucket<TwoRouteContract> twoRouteBucket(RouteDiscovery.Metadata anchor, Predicate<Client<TwoRouteContract>> availability) {
        return new SubnetBucket<>(SUBNET, anchor.getRoute() + "@" + SUBNET, anchor.getRateLimit(), TwoRoutes.options(), this.counting(), availability);
    }

    @Test
    @DisplayName("selectClient binds within the bucket's subnet")
    void selectClientBindsWithinSubnet() {
        SubnetBucket<TestContract> b = this.bucket(c -> true);
        Client<TestContract> client = b.selectClient();
        assertThat(client.getOptions().getInet6Address().isPresent(), is(true));
        assertThat(b.getSubnet().contains(client.getOptions().getInet6Address().get()), is(true));
    }

    @Test
    @DisplayName("Repeated selections build one client and return that instance every time")
    void repeatedSelectionsReturnOneClient() {
        SubnetBucket<TestContract> b = this.bucket(c -> true);
        Client<TestContract> first = b.selectClient();

        for (int i = 0; i < 20; i++)
            assertThat(b.selectClient(), is(sameInstance(first)));

        assertThat(b.getClientCount(), is(1));
        assertThat(this.built.get(), is(1));
    }

    @Test
    @DisplayName("Concurrent first selections build exactly one client and all return that instance")
    void concurrentFirstSelectionsBuildOneClient() throws Exception {
        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        try {
            for (int round = 0; round < 10; round++) {
                this.built.set(0);
                SubnetBucket<TestContract> b = this.bucket(c -> true);
                CountDownLatch ready = new CountDownLatch(threads);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Client<TestContract>>> selections = new ArrayList<>();

                for (int i = 0; i < threads; i++) {
                    selections.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return b.selectClient();
                    }));
                }

                ready.await();
                start.countDown();
                Client<TestContract> first = selections.getFirst().get(30, TimeUnit.SECONDS);

                for (Future<Client<TestContract>> selection : selections)
                    assertThat("round " + round, selection.get(30, TimeUnit.SECONDS), is(sameInstance(first)));

                assertThat("round " + round, b.getClientCount(), is(1));
                assertThat("round " + round, this.built.get(), is(1));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("A refused client is refused, client-enforced under the subnet, on every selection and never replaced")
    void refusedClientIsNotReplaced() {
        SubnetBucket<TestContract> b = this.bucket(c -> false);

        for (int i = 0; i < 10; i++) {
            RateLimitException refusal = assertThrows(RateLimitException.class, b::selectClient);
            assertThat(refusal.isServerEnforced(), is(false));
            assertThat(refusal.getBucketId(), is(SUBNET.toString()));
        }

        assertThat(b.getClientCount(), is(1));
        assertThat(this.built.get(), is(1));
    }

    @Test
    @DisplayName("A client that passes the saturation check and is refused on selection is refused, not replaced")
    void checkThenSelectRaceRefuses() {
        AtomicInteger passes = new AtomicInteger(Integer.MAX_VALUE);
        SubnetBucket<TestContract> b = this.bucket(c -> passes.getAndDecrement() > 0);
        Client<TestContract> first = b.selectClient();

        // The pool's check passes, then the bucket is spent before the selection tests it again.
        passes.set(1);
        assertThat(b.isSaturated(), is(false));
        RateLimitException refusal = assertThrows(RateLimitException.class, b::selectClient);

        assertThat(refusal.getBucketId(), is(SUBNET.toString()));
        assertThat(b.getClientCount(), is(1));
        assertThat(this.built.get(), is(1));

        passes.set(Integer.MAX_VALUE);
        assertThat(b.selectClient(), is(sameInstance(first)));
        assertThat(this.built.get(), is(1));
    }

    @Test
    @DisplayName("A refusal carries the policy of the route the client reports exhausted")
    void refusalCarriesExhaustedRoutePolicy() {
        SubnetBucket<TwoRouteContract> b = this.twoRouteBucket(TwoRoutes.anchorRoute(), TwoRoutes::open);
        Client<TwoRouteContract> client = b.selectClient();

        TwoRoutes.exhaust(client, TwoRoutes.SECOND);
        RateLimitException refusal = assertThrows(RateLimitException.class, b::selectClient);

        assertThat(refusal.getBucketId(), is(SUBNET.toString()));
        assertThat(refusal.getRateLimit(), is(sameInstance(TwoRoutes.policyOf(client, TwoRoutes.SECOND))));
        assertThat(refusal.getRateLimit().getLimit(), is(TwoRoutes.SECOND_LIMIT));
        assertThat(refusal.getRateLimit().getResetSeconds(), is(TwoRoutes.SECOND_WINDOW));
    }

    @Test
    @DisplayName("A refusal carries the anchor route's policy when the client reports no exhausted route")
    void refusalCarriesAnchorPolicy() {
        AtomicBoolean available = new AtomicBoolean(true);
        RouteDiscovery.Metadata anchor = TwoRoutes.anchorRoute();
        SubnetBucket<TwoRouteContract> b = this.twoRouteBucket(anchor, c -> available.get() && TwoRoutes.open(c));

        b.selectClient();
        available.set(false);
        RateLimitException refusal = assertThrows(RateLimitException.class, b::selectClient);

        assertThat(refusal.getRateLimit(), is(sameInstance(anchor.getRateLimit())));
        assertThat(refusal.getRateLimit().getLimit(), is(TwoRoutes.ANCHOR_LIMIT));
        assertThat(refusal.getRateLimit().getResetSeconds(), is(TwoRoutes.ANCHOR_WINDOW));
    }

    @Test
    @DisplayName("A refusal of a route declaring no limit, spent under a server's policy, carries the server's policy rather than the unlimited sentinel")
    void refusalOfUnlimitedRouteCarriesServerPolicy() {
        SubnetBucket<TestContract> b = this.bucket(c -> !c.isRateLimited());
        Client<TestContract> client = b.selectClient();
        RouteDiscovery.Metadata route = client.getRouteDiscovery().getDefaultRoute();
        long now = System.currentTimeMillis();
        assertThat(route.getRateLimit().isUnlimited(), is(true));

        TwoRoutes.answer(client, route, 5, 0, 60, now);
        RateLimitException refusal = assertThrows(RateLimitException.class, b::selectClient);

        assertThat(refusal.getBucketId(), is(SUBNET.toString()));
        assertThat(refusal.getRateLimit().isUnlimited(), is(false));
        assertThat(refusal.getRateLimit().getLimit(), is(5L));
        assertThat(refusal.getRateLimit().getResetEpochMillis(), is(now + 60_000L));
    }

    @Test
    @DisplayName("A refusal with no exhausted bucket carries the policy the client holds for its type-level route over the declared one")
    void refusalCarriesHeldAnchorPolicy() {
        AtomicBoolean available = new AtomicBoolean(true);
        RouteDiscovery.Metadata anchor = TwoRoutes.anchorRoute();
        SubnetBucket<TwoRouteContract> b = this.twoRouteBucket(anchor, c -> available.get() && TwoRoutes.open(c));
        Client<TwoRouteContract> client = b.selectClient();
        long now = System.currentTimeMillis();

        TwoRoutes.answer(client, client.getRouteDiscovery().getDefaultRoute(), 50, 10, 90, now);
        available.set(false);
        RateLimitException refusal = assertThrows(RateLimitException.class, b::selectClient);

        assertThat(refusal.getRateLimit().getLimit(), is(50L));
        assertThat(refusal.getRateLimit().getResetEpochMillis(), is(now + 90_000L));
    }

    @Test
    @DisplayName("A build that fails leaves the bucket empty and unsaturated, and the next selection builds one client")
    void failedBuildIsRetried() {
        IllegalStateException failure = new IllegalStateException("Build failed");
        AtomicInteger calls = new AtomicInteger();
        ClientConfig<TestContract> base = ClientConfig.builder(TestContract.class, GsonSettings.builder().build()).build();
        RouteDiscovery.Metadata anchor = new RouteDiscovery(base).getDefaultRoute();
        UnaryOperator<ClientConfig.Builder<TestContract>> failingOnce = builder -> {
            if (calls.getAndIncrement() == 0)
                throw failure;

            this.built.incrementAndGet();
            return builder;
        };
        SubnetBucket<TestContract> b = new SubnetBucket<>(SUBNET, ANCHOR_BUCKET_KEY, anchor.getRateLimit(), base, failingOnce, c -> true);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, b::selectClient);
        assertThat(thrown, is(sameInstance(failure)));
        assertThat(b.getClientCount(), is(0));
        assertThat(b.isSaturated(), is(false));

        Client<TestContract> client = b.selectClient();
        assertThat(b.getClientCount(), is(1));
        assertThat(this.built.get(), is(1));

        assertThat(b.selectClient(), is(sameInstance(client)));
        assertThat(this.built.get(), is(1));
        assertThat(calls.get(), is(2));
    }

    @Test
    @DisplayName("isSaturated false on a bucket that has built no client, and builds none")
    void emptyBucketNotSaturated() {
        SubnetBucket<TestContract> b = this.bucket(c -> false);
        assertThat(b.isSaturated(), is(false));
        assertThat(b.getClientCount(), is(0));
        assertThat(this.built.get(), is(0));
    }

    @Test
    @DisplayName("isSaturated true while the bucket's client fails the availability predicate")
    void saturatedWhenClientFails() {
        AtomicBoolean available = new AtomicBoolean(true);
        SubnetBucket<TestContract> b = this.bucket(c -> available.get());

        b.selectClient();
        assertThat(b.isSaturated(), is(false));

        available.set(false);
        assertThat(b.isSaturated(), is(true));
    }

    @Test
    @DisplayName("selectClient does not advance the shared rate-limit counter")
    void selectClientDoesNotTrack() {
        RateLimitManager shared = new RateLimitManager();
        ClientConfig<TestContract> base = ClientConfig.builder(TestContract.class, GsonSettings.builder().build())
            .withRateLimitManager(shared)
            .withSubnetPrefix(SUBNET)
            .build();
        RouteDiscovery.Metadata anchor = new RouteDiscovery(base).getDefaultRoute();
        SubnetBucket<TestContract> b = new SubnetBucket<>(SUBNET, ANCHOR_BUCKET_KEY, anchor.getRateLimit(), base, UnaryOperator.identity(), c -> true);

        b.selectClient();
        b.selectClient();
        b.selectClient();

        // Tracking happens at the request-interceptor level; selectClient alone advances nothing.
        assertThat(shared.getRequestCount("127.0.0.1:0"), is(0L));
        assertThat(shared.getRequestCount("127.0.0.1:0@" + SUBNET), is(0L));
    }

}
