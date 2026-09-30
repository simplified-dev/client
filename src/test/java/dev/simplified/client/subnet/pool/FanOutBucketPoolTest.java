package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.route.RouteDiscovery;
import dev.simplified.client.subnet.IPv6Prefix;
import dev.simplified.client.subnet.SubnetRotation;
import dev.simplified.client.subnet.SubnetSelectionStrategy;
import dev.simplified.gson.GsonSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.Inet6Address;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FanOutBucketPoolTest {

    private static final String ANCHOR_ROUTE = "127.0.0.1:0";

    private record PoolHandle(
        FanOutBucketPool<TestContract> pool,
        RateLimitManager sharedManager
    ) {
        Client<TestContract> selectClient() { return pool.selectClient(); }
        java.util.stream.Stream<SubnetBucket<TestContract>> activeBuckets() { return pool.activeBuckets(); }
    }

    private static PoolHandle pool(
        String sourceCidr,
        int bucketLen,
        SubnetSelectionStrategy strategy,
        Predicate<Client<TestContract>> availability
    ) {
        SubnetRotation rotation = SubnetRotation.builder()
            .sourcePrefix(sourceCidr)
            .bucketPrefixLength(bucketLen)
            .strategy(strategy)
            .build();
        RateLimitManager shared = new RateLimitManager();
        ClientConfig<TestContract> base = ClientConfig.builder(TestContract.class, GsonSettings.builder().build()).build();
        FanOutBucketPool<TestContract> pool = (FanOutBucketPool<TestContract>) SubnetBucketPool.create(
            rotation, shared, new RouteDiscovery(base).getDefaultRoute(),
            base, UnaryOperator.identity(), availability
        );
        return new PoolHandle(pool, shared);
    }

    /**
     * Builds a round-robin pool over {@link TwoRouteContract} fanning a /127 out to two /128 buckets.
     */
    private static FanOutBucketPool<TwoRouteContract> twoRoutePool(
        RouteDiscovery.Metadata anchor,
        Predicate<Client<TwoRouteContract>> availability
    ) {
        SubnetRotation rotation = SubnetRotation.builder()
            .sourcePrefix("2001:db8::/127")
            .bucketPrefixLength(128)
            .strategy(SubnetSelectionStrategy.ROUND_ROBIN)
            .build();
        return (FanOutBucketPool<TwoRouteContract>) SubnetBucketPool.create(
            rotation, new RateLimitManager(), anchor,
            TwoRoutes.options(), UnaryOperator.identity(), availability
        );
    }

    private static IPv6Prefix subnetOf(Client<?> client, int bucketLen) {
        return IPv6Prefix.of(client.getOptions().getInet6Address().orElseThrow().getAddress(), bucketLen);
    }

    /**
     * Simulates the request interceptor advancing the per-(route, subnet) counter for the bucket a
     * given Client landed in, so the LEAST_USED strategy can observe that the bucket has been used.
     */
    private static void simulateRequest(RateLimitManager shared, Client<TestContract> client, int bucketLen) {
        Inet6Address ip = client.getOptions().getInet6Address().orElseThrow();
        IPv6Prefix subnet = IPv6Prefix.of(ip.getAddress(), bucketLen);
        String key = ANCHOR_ROUTE + "@" + subnet;
        shared.trackRequest(key, RateLimit.builder().limit(1000).window(1, ChronoUnit.MINUTES).build());
    }

    /**
     * Returns a predicate that treats clients bound to addresses in {@code saturated} as
     * unavailable; mutating the set during the test progressively saturates buckets at the
     * given bucket prefix length.
     */
    private static Predicate<Client<TestContract>> saturationSetPredicate(Set<IPv6Prefix> saturated, int bucketLen) {
        return client -> {
            Inet6Address ip = client.getOptions().getInet6Address().orElseThrow();
            IPv6Prefix subnet = IPv6Prefix.of(ip.getAddress(), bucketLen);
            return !saturated.contains(subnet);
        };
    }

    @Test
    @DisplayName("Round-robin cycles through every contained bucket index")
    void roundRobinCycles() {
        // /126 source, /128 bucket -> 4 contained buckets
        PoolHandle p = pool("2001:db8::/126", 128,
            SubnetSelectionStrategy.ROUND_ROBIN, c -> true);

        for (int i = 0; i < 8; i++) p.selectClient();

        Set<IPv6Prefix> subnets = p.activeBuckets().map(SubnetBucket::getSubnet).collect(Collectors.toSet());
        assertThat(subnets.size(), is(4));
    }

    @Test
    @DisplayName("Round-robin throws once every contained bucket is saturated")
    void roundRobinSaturationThrows() {
        Set<IPv6Prefix> saturated = ConcurrentHashMap.newKeySet();
        PoolHandle p = pool("2001:db8::/126", 128,
            SubnetSelectionStrategy.ROUND_ROBIN, saturationSetPredicate(saturated, 128));

        // 4 calls each materialize a fresh bucket with one client; saturate each one as it appears.
        for (int i = 0; i < 4; i++) {
            Client<TestContract> c = p.selectClient();
            saturated.add(IPv6Prefix.of(c.getOptions().getInet6Address().orElseThrow().getAddress(), 128));
        }

        assertThrows(RateLimitException.class, p::selectClient);
    }

    @Test
    @DisplayName("Least-used spreads load before reusing any bucket")
    void leastUsedSpreadsFirst() {
        // /120 source gives 256 contained /128s - enough headroom that 4 random picks practically
        // never collide, so the spread assertion below is deterministic in practice.
        PoolHandle p = pool("2001:db8::/120", 128,
            SubnetSelectionStrategy.LEAST_USED, c -> true);

        // LEAST_USED prefers fresh buckets only once an active bucket has count > 0; simulate the
        // interceptor advancing the per-(route, subnet) counter on each selection so the spread
        // behavior shows up. With 4 calls + simulated tracking, we expect 4 distinct subnets.
        for (int i = 0; i < 4; i++) {
            Client<TestContract> c = p.selectClient();
            simulateRequest(p.sharedManager(), c, 128);
        }
        Set<IPv6Prefix> subnets = p.activeBuckets().map(SubnetBucket::getSubnet).collect(Collectors.toSet());
        assertThat(subnets.size(), is(4));
    }

    @Test
    @DisplayName("Least-used throws when every bucket is saturated")
    void leastUsedSaturationThrows() {
        Set<IPv6Prefix> saturated = ConcurrentHashMap.newKeySet();
        PoolHandle p = pool("2001:db8::/126", 128,
            SubnetSelectionStrategy.LEAST_USED, saturationSetPredicate(saturated, 128));

        for (int i = 0; i < 4; i++) {
            Client<TestContract> c = p.selectClient();
            saturated.add(IPv6Prefix.of(c.getOptions().getInet6Address().orElseThrow().getAddress(), 128));
        }

        assertThrows(RateLimitException.class, p::selectClient);
    }

    @Test
    @DisplayName("Random reject-sample throws when every bucket is saturated")
    void randomSaturationThrows() {
        Set<IPv6Prefix> saturated = ConcurrentHashMap.newKeySet();
        PoolHandle p = pool("2001:db8::/126", 128,
            SubnetSelectionStrategy.RANDOM_REJECT_SAMPLE, saturationSetPredicate(saturated, 128));

        // Random may hit the same subnet repeatedly - keep trying until all four are saturated.
        int successful = 0;
        for (int i = 0; i < 64 && saturated.size() < 4; i++) {
            try {
                Client<TestContract> c = p.selectClient();
                saturated.add(IPv6Prefix.of(c.getOptions().getInet6Address().orElseThrow().getAddress(), 128));
                successful++;
            } catch (RateLimitException ignored) {
                break;
            }
        }
        assertThat(successful, is(lessThanOrEqualTo(64)));
        assertThrows(RateLimitException.class, p::selectClient);
    }

    @Test
    @DisplayName("Random reject-sample materializes buckets sparsely")
    void randomSparseMaterialization() {
        PoolHandle p = pool("2001:db8::/120", 128,
            SubnetSelectionStrategy.RANDOM_REJECT_SAMPLE, c -> true);

        for (int i = 0; i < 5; i++) p.selectClient();
        assertThat(p.activeBuckets().count(), is(lessThanOrEqualTo(5L)));
        assertThat(p.activeBuckets().count(), is(greaterThan(0L)));
    }

    @Test
    @DisplayName("Each spawned client binds within its bucket's subnet")
    void clientBindsWithinBucket() {
        PoolHandle p = pool("2001:db8::/120", 128,
            SubnetSelectionStrategy.RANDOM_REJECT_SAMPLE, c -> true);

        for (int i = 0; i < 5; i++) {
            Client<TestContract> client = p.selectClient();
            assertThat(client.getOptions().getInet6Address().isPresent(), is(true));
            assertThat(IPv6Prefix.parse("2001:db8::/120").contains(client.getOptions().getInet6Address().get()), is(true));
        }
    }

    @Test
    @DisplayName("Saturation exception carries source prefix as bucket id")
    void saturationCarriesSourcePrefix() {
        Set<IPv6Prefix> saturated = ConcurrentHashMap.newKeySet();
        PoolHandle p = pool("2001:db8::/126", 128,
            SubnetSelectionStrategy.ROUND_ROBIN, saturationSetPredicate(saturated, 128));

        for (int i = 0; i < 4; i++) {
            Client<TestContract> c = p.selectClient();
            saturated.add(IPv6Prefix.of(c.getOptions().getInet6Address().orElseThrow().getAddress(), 128));
        }

        RateLimitException ex = assertThrows(RateLimitException.class, p::selectClient);
        assertThat(ex.isServerEnforced(), is(false));
        assertThat(ex.getBucketId().contains("/126"), is(true));
    }

    @Test
    @DisplayName("A bucket spent between the pool's saturation check and its selection refuses under its subnet rather than building a second client")
    void checkThenSelectRaceRefuses() {
        AtomicBoolean spendAfterCheck = new AtomicBoolean(false);
        FanOutBucketPool<TwoRouteContract> p = twoRoutePool(TwoRoutes.anchorRoute(), client -> {
            boolean open = TwoRoutes.open(client);

            // Another request spends the bucket right after the pool's check passes it.
            if (open && spendAfterCheck.getAndSet(false))
                TwoRoutes.exhaust(client, TwoRoutes.SECOND);

            return open;
        });

        Client<TwoRouteContract> first = p.selectClient();
        p.selectClient();
        spendAfterCheck.set(true);

        // Round-robin returns to the first bucket: its check passes, then its selection is refused.
        RateLimitException refusal = assertThrows(RateLimitException.class, p::selectClient);

        assertThat(refusal.isServerEnforced(), is(false));
        assertThat(refusal.getBucketId(), is(subnetOf(first, 128).toString()));
        assertThat(refusal.getRateLimit(), is(sameInstance(TwoRoutes.policyOf(first, TwoRoutes.SECOND))));
        assertThat(p.activeBuckets().count(), is(2L));
        assertThat(p.activeBuckets().mapToInt(SubnetBucket::getClientCount).sum(), is(2));
    }

    @Test
    @DisplayName("With every bucket saturated on the second route, the refusal names the source prefix and carries the second route's policy")
    void saturationCarriesExhaustedRoutePolicy() {
        FanOutBucketPool<TwoRouteContract> p = twoRoutePool(TwoRoutes.anchorRoute(), TwoRoutes::open);

        TwoRoutes.exhaust(p.selectClient(), TwoRoutes.SECOND);
        TwoRoutes.exhaust(p.selectClient(), TwoRoutes.SECOND);
        RateLimitException refusal = assertThrows(RateLimitException.class, p::selectClient);

        assertThat(refusal.getBucketId(), is(IPv6Prefix.parse("2001:db8::/127").toString()));
        assertThat(refusal.getRateLimit().getLimit(), is(TwoRoutes.SECOND_LIMIT));
        assertThat(refusal.getRateLimit().getResetSeconds(), is(TwoRoutes.SECOND_WINDOW));
    }

    @Test
    @DisplayName("With every bucket saturated and no route exhausted, the refusal carries the anchor route's policy")
    void saturationCarriesAnchorPolicy() {
        AtomicBoolean available = new AtomicBoolean(true);
        RouteDiscovery.Metadata anchor = TwoRoutes.anchorRoute();
        FanOutBucketPool<TwoRouteContract> p = twoRoutePool(anchor, client -> available.get() && TwoRoutes.open(client));

        p.selectClient();
        p.selectClient();
        available.set(false);
        RateLimitException refusal = assertThrows(RateLimitException.class, p::selectClient);

        assertThat(refusal.getBucketId(), is(IPv6Prefix.parse("2001:db8::/127").toString()));
        assertThat(refusal.getRateLimit(), is(sameInstance(anchor.getRateLimit())));
        assertThat(refusal.getRateLimit().getLimit(), is(TwoRoutes.ANCHOR_LIMIT));
    }

    @Test
    @DisplayName("With every bucket spent under a server's policy on a route declaring no limit, the refusal carries the server's policy")
    void saturationOfUnlimitedRouteCarriesServerPolicy() {
        PoolHandle p = pool("2001:db8::/127", 128, SubnetSelectionStrategy.ROUND_ROBIN, c -> !c.isRateLimited());
        long now = System.currentTimeMillis();

        for (int i = 0; i < 2; i++) {
            Client<TestContract> client = p.selectClient();
            TwoRoutes.answer(client, client.getRouteDiscovery().getDefaultRoute(), 5, 0, 60, now);
        }

        RateLimitException refusal = assertThrows(RateLimitException.class, p::selectClient);

        assertThat(refusal.getBucketId(), is(IPv6Prefix.parse("2001:db8::/127").toString()));
        assertThat(refusal.getRateLimit().isUnlimited(), is(false));
        assertThat(refusal.getRateLimit().getLimit(), is(5L));
        assertThat(refusal.getRateLimit().getResetEpochMillis(), is(now + 60_000L));
    }

    @Test
    @DisplayName("With every bucket saturated and no route exhausted, the refusal carries the policy a client holds for its type-level route over the declared one")
    void saturationCarriesHeldAnchorPolicy() {
        AtomicBoolean available = new AtomicBoolean(true);
        FanOutBucketPool<TwoRouteContract> p = twoRoutePool(TwoRoutes.anchorRoute(), client -> available.get() && TwoRoutes.open(client));
        Client<TwoRouteContract> first = p.selectClient();
        long now = System.currentTimeMillis();

        p.selectClient();
        TwoRoutes.answer(first, first.getRouteDiscovery().getDefaultRoute(), 50, 10, 90, now);
        available.set(false);
        RateLimitException refusal = assertThrows(RateLimitException.class, p::selectClient);

        assertThat(refusal.getRateLimit().getLimit(), is(50L));
        assertThat(refusal.getRateLimit().getResetEpochMillis(), is(now + 90_000L));
    }

}
