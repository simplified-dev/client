package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.subnet.IPv6Prefix;
import dev.simplified.client.subnet.SubnetRotation;
import dev.simplified.gson.GsonSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

class PassThroughBucketPoolTest {

    private static PassThroughBucketPool<TestContract> pool() {
        SubnetRotation rotation = SubnetRotation.builder()
            .sourcePrefix("2001:db8:abcd:ef00::/60")  // /60 inside a /56 = pass-through
            .bucketPrefixLength(56)
            .build();
        RateLimitManager shared = new RateLimitManager();
        ClientConfig<TestContract> base = ClientConfig.builder(TestContract.class, GsonSettings.builder().build()).build();
        return (PassThroughBucketPool<TestContract>) SubnetBucketPool.create(
            rotation, shared, "127.0.0.1:0",
            base, UnaryOperator.identity(),
            (Predicate<Client<TestContract>>) c -> true
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

}
