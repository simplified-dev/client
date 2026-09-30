package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.route.RouteDiscovery;
import dev.simplified.client.subnet.IPv6Prefix;
import dev.simplified.client.subnet.SubnetRotation;
import dev.simplified.gson.GsonSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SingleBucketPoolTest {

    private static final SubnetRotation ROTATION = SubnetRotation.builder()
        .sourcePrefix("2001:db8:abcd:ef00::/56")
        .bucketPrefixLength(56)
        .build();

    private static SingleBucketPool<TestContract> pool(Predicate<Client<TestContract>> availability) {
        RateLimitManager shared = new RateLimitManager();
        ClientConfig<TestContract> base = ClientConfig.builder(TestContract.class, GsonSettings.builder().build()).build();
        return (SingleBucketPool<TestContract>) SubnetBucketPool.create(
            ROTATION, shared, new RouteDiscovery(base).getDefaultRoute(),
            base, UnaryOperator.identity(), availability
        );
    }

    private static SingleBucketPool<TwoRouteContract> twoRoutePool(RouteDiscovery.Metadata anchor, Predicate<Client<TwoRouteContract>> availability) {
        return (SingleBucketPool<TwoRouteContract>) SubnetBucketPool.create(
            ROTATION, new RateLimitManager(), anchor,
            TwoRoutes.options(), UnaryOperator.identity(), availability
        );
    }

    @Test
    @DisplayName("Single bucket returns Clients bound within the source prefix")
    void selectClientBindsWithinSubnet() {
        SingleBucketPool<TestContract> p = pool(c -> true);
        Client<TestContract> client = p.selectClient();
        assertThat(client, is(notNullValue()));
        assertThat(client.getOptions().getInet6Address().isPresent(), is(true));

        IPv6Prefix subnet = IPv6Prefix.parse("2001:db8:abcd:ef00::/56");
        assertThat(subnet.contains(client.getOptions().getInet6Address().get()), is(true));
    }

    @Test
    @DisplayName("Single bucket throws RateLimitException once availability fails on its client, and builds no other")
    void saturationThrows() {
        AtomicBoolean available = new AtomicBoolean(true);
        SingleBucketPool<TestContract> p = pool(c -> available.get());

        p.selectClient();
        available.set(false);

        for (int i = 0; i < 10; i++)
            assertThrows(RateLimitException.class, p::selectClient);

        assertThat(p.activeBuckets().findFirst().orElseThrow().getClientCount(), is(1));
    }

    @Test
    @DisplayName("Saturation exception carries the subnet as bucket id")
    void saturationCarriesContext() {
        AtomicBoolean available = new AtomicBoolean(true);
        SingleBucketPool<TestContract> p = pool(c -> available.get());

        p.selectClient();
        available.set(false);

        RateLimitException ex = assertThrows(RateLimitException.class, p::selectClient);
        assertThat(ex.isServerEnforced(), is(false));
        assertThat(ex.getBucketId(), is(ROTATION.sourcePrefix().toString()));
    }

    @Test
    @DisplayName("A refusal carries the policy of the second route when its bucket is the one exhausted")
    void refusalCarriesExhaustedRoutePolicy() {
        SingleBucketPool<TwoRouteContract> p = twoRoutePool(TwoRoutes.anchorRoute(), TwoRoutes::open);
        Client<TwoRouteContract> client = p.selectClient();

        TwoRoutes.exhaust(client, TwoRoutes.SECOND);
        RateLimitException refusal = assertThrows(RateLimitException.class, p::selectClient);

        assertThat(refusal.getRateLimit(), is(sameInstance(TwoRoutes.policyOf(client, TwoRoutes.SECOND))));
        assertThat(refusal.getRateLimit().getLimit(), is(TwoRoutes.SECOND_LIMIT));
        assertThat(refusal.getRateLimit().getResetSeconds(), is(TwoRoutes.SECOND_WINDOW));
    }

    @Test
    @DisplayName("A refusal carries the anchor route's policy when no route is exhausted")
    void refusalCarriesAnchorPolicy() {
        AtomicBoolean available = new AtomicBoolean(true);
        RouteDiscovery.Metadata anchor = TwoRoutes.anchorRoute();
        SingleBucketPool<TwoRouteContract> p = twoRoutePool(anchor, c -> available.get() && TwoRoutes.open(c));

        p.selectClient();
        available.set(false);
        RateLimitException refusal = assertThrows(RateLimitException.class, p::selectClient);

        assertThat(refusal.getRateLimit(), is(sameInstance(anchor.getRateLimit())));
        assertThat(refusal.getRateLimit().getLimit(), is(TwoRoutes.ANCHOR_LIMIT));
    }

}
