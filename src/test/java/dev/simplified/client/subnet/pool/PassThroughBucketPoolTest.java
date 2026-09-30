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

class PassThroughBucketPoolTest {

    private static final SubnetRotation ROTATION = SubnetRotation.builder()
        .sourcePrefix("2001:db8:abcd:ef00::/60")  // /60 inside a /56 = pass-through
        .bucketPrefixLength(56)
        .build();

    private static PassThroughBucketPool<TestContract> pool() {
        return pool(c -> true);
    }

    private static PassThroughBucketPool<TestContract> pool(Predicate<Client<TestContract>> availability) {
        RateLimitManager shared = new RateLimitManager();
        ClientConfig<TestContract> base = ClientConfig.builder(TestContract.class, GsonSettings.builder().build()).build();
        return (PassThroughBucketPool<TestContract>) SubnetBucketPool.create(
            ROTATION, shared, new RouteDiscovery(base).getDefaultRoute(),
            base, UnaryOperator.identity(),
            availability
        );
    }

    private static PassThroughBucketPool<TwoRouteContract> twoRoutePool(RouteDiscovery.Metadata anchor, Predicate<Client<TwoRouteContract>> availability) {
        return (PassThroughBucketPool<TwoRouteContract>) SubnetBucketPool.create(
            ROTATION, new RateLimitManager(), anchor,
            TwoRoutes.options(), UnaryOperator.identity(), availability
        );
    }

    @Test
    @DisplayName("PassThrough binds addresses within the source prefix")
    void selectClientBindsWithinSourcePrefix() {
        PassThroughBucketPool<TestContract> p = pool();
        Client<TestContract> client = p.selectClient();
        assertThat(client, is(notNullValue()));
        assertThat(client.getOptions().getInet6Address().isPresent(), is(true));
        assertThat(IPv6Prefix.parse("2001:db8:abcd:ef00::/60").contains(client.getOptions().getInet6Address().get()), is(true));
    }

    @Test
    @DisplayName("PassThrough never throws while availability stays true, and serves every call through one client")
    void neverSaturatesWhenAvailable() {
        PassThroughBucketPool<TestContract> p = pool();
        Client<TestContract> first = p.selectClient();

        for (int i = 0; i < 50; i++)
            assertThat(p.selectClient(), is(sameInstance(first)));
    }

    @Test
    @DisplayName("PassThrough throws RateLimitException, client-enforced under its subnet, once availability fails on its client")
    void saturationThrows() {
        AtomicBoolean available = new AtomicBoolean(true);
        PassThroughBucketPool<TestContract> p = pool(c -> available.get());

        p.selectClient();
        available.set(false);

        RateLimitException ex = assertThrows(RateLimitException.class, p::selectClient);
        assertThat(ex.isServerEnforced(), is(false));
        assertThat(ex.getBucketId(), is(IPv6Prefix.parse("2001:db8:abcd:ef00::/60").toString()));
    }

    @Test
    @DisplayName("PassThrough builds no further client while its bucket is saturated")
    void saturationBuildsNoClient() {
        AtomicBoolean available = new AtomicBoolean(true);
        PassThroughBucketPool<TestContract> p = pool(c -> available.get());

        p.selectClient();
        available.set(false);

        for (int i = 0; i < 10; i++)
            assertThrows(RateLimitException.class, p::selectClient);

        assertThat(p.activeBuckets().findFirst().orElseThrow().getClientCount(), is(1));
    }

    @Test
    @DisplayName("A refusal carries the policy of the second route when its bucket is the one exhausted")
    void refusalCarriesExhaustedRoutePolicy() {
        PassThroughBucketPool<TwoRouteContract> p = twoRoutePool(TwoRoutes.anchorRoute(), TwoRoutes::open);
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
        PassThroughBucketPool<TwoRouteContract> p = twoRoutePool(anchor, c -> available.get() && TwoRoutes.open(c));

        p.selectClient();
        available.set(false);
        RateLimitException refusal = assertThrows(RateLimitException.class, p::selectClient);

        assertThat(refusal.getRateLimit(), is(sameInstance(anchor.getRateLimit())));
        assertThat(refusal.getRateLimit().getLimit(), is(TwoRoutes.ANCHOR_LIMIT));
    }

}
