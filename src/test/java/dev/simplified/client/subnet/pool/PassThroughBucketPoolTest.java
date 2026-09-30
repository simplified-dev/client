package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.ratelimit.RateLimitManager;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

class PassThroughBucketPoolTest {

    private static PassThroughBucketPool<TestContract> pool() {
        return pool(c -> true);
    }

    private static PassThroughBucketPool<TestContract> pool(Predicate<Client<TestContract>> availability) {
        SubnetRotation rotation = SubnetRotation.builder()
            .sourcePrefix("2001:db8:abcd:ef00::/60")  // /60 inside a /56 = pass-through
            .bucketPrefixLength(56)
            .build();
        RateLimitManager shared = new RateLimitManager();
        ClientConfig<TestContract> base = ClientConfig.builder(TestContract.class, GsonSettings.builder().build()).build();
        return (PassThroughBucketPool<TestContract>) SubnetBucketPool.create(
            rotation, shared, "127.0.0.1:0",
            base, UnaryOperator.identity(),
            availability
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
    @DisplayName("PassThrough never throws while availability stays true")
    void neverSaturatesWhenAvailable() {
        PassThroughBucketPool<TestContract> p = pool();
        for (int i = 0; i < 50; i++) {
            p.selectClient();
        }
    }

    @Test
    @DisplayName("PassThrough throws RateLimitException, client-enforced under its subnet, once availability fails on every spawned client")
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

}
