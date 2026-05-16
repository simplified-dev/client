package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.subnet.IpPrefix;
import dev.simplified.client.subnet.SubnetRotation;
import dev.simplified.gson.GsonSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.temporal.ChronoUnit;
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
            .budget(RateLimit.builder().limit(2).window(1, ChronoUnit.MINUTES).build())
            .softCapFraction(0.5)
            .build();
        ClientConfig<TestContract> base = ClientConfig.builder(TestContract.class, GsonSettings.builder().build()).build();
        return (PassThroughBucketPool<TestContract>) SubnetBucketPool.create(
            rotation,
            base,
            UnaryOperator.identity(),
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
        assertThat(IpPrefix.parse("2001:db8:abcd:ef00::/60").contains(client.getOptions().getInet6Address().get()), is(true));
    }

    @Test
    @DisplayName("PassThrough ignores configured budget - never throws on saturation")
    void neverSaturates() {
        PassThroughBucketPool<TestContract> p = pool();
        // Configured limit=2, softCap=1 - but PassThrough forces UNLIMITED budget.
        for (int i = 0; i < 50; i++) {
            p.selectClient();
        }
        // No throw expected.
        assertThat(p.activeBuckets().findFirst().orElseThrow().getRemaining(), is(Long.MAX_VALUE));
    }

    @Test
    @DisplayName("PassThrough overrides budget to UNLIMITED regardless of configuration")
    void budgetForcedUnlimited() {
        PassThroughBucketPool<TestContract> p = pool();
        Bucket<TestContract> bucket = p.activeBuckets().findFirst().orElseThrow();
        assertThat(bucket.getBudget().getRateLimit().get(), is(RateLimit.UNLIMITED));
    }

}
