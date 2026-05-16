package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.exception.RateLimitException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

class SingleBucketPoolTest {

    private static SingleBucketPool<TestContract> pool(int limit, double softCapFraction) {
        SubnetRotation rotation = SubnetRotation.builder()
            .sourcePrefix("2001:db8:abcd:ef00::/56")
            .bucketPrefixLength(56)
            .budget(RateLimit.builder().limit(limit).window(1, ChronoUnit.MINUTES).build())
            .softCapFraction(softCapFraction)
            .build();
        ClientConfig<TestContract> base = ClientConfig.builder(TestContract.class, GsonSettings.builder().build()).build();
        return (SingleBucketPool<TestContract>) SubnetBucketPool.create(
            rotation,
            base,
            UnaryOperator.identity(),
            (Predicate<Client<TestContract>>) c -> true
        );
    }

    @Test
    @DisplayName("Single bucket returns Clients bound within the source prefix")
    void selectClientBindsWithinSubnet() {
        SingleBucketPool<TestContract> p = pool(10, 0.9);
        Client<TestContract> client = p.selectClient();
        assertThat(client, is(notNullValue()));
        assertThat(client.getOptions().getInet6Address().isPresent(), is(true));

        IpPrefix subnet = IpPrefix.parse("2001:db8:abcd:ef00::/56");
        assertThat(subnet.contains(client.getOptions().getInet6Address().get()), is(true));
    }

    @Test
    @DisplayName("Single bucket throws RateLimitException at soft cap")
    void saturationThrows() {
        // softCap = floor(4 * 0.5) = 2; selectClient ok x2, third throws.
        SingleBucketPool<TestContract> p = pool(4, 0.5);
        p.selectClient();
        p.selectClient();
        assertThrows(RateLimitException.class, p::selectClient);
    }

    @Test
    @DisplayName("Saturation exception carries bucket id and rate limit")
    void saturationCarriesContext() {
        SingleBucketPool<TestContract> p = pool(2, 0.5);
        p.selectClient(); // soft cap = 1, first hit saturates
        RateLimitException ex = assertThrows(RateLimitException.class, p::selectClient);
        assertThat(ex.isServerEnforced(), is(false));
        assertThat(ex.getBucketId().contains("/56"), is(true));
    }

}
