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

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SingleBucketPoolTest {

    private static SingleBucketPool<TestContract> pool(Predicate<Client<TestContract>> availability) {
        SubnetRotation rotation = SubnetRotation.builder()
            .sourcePrefix("2001:db8:abcd:ef00::/56")
            .bucketPrefixLength(56)
            .build();
        RateLimitManager shared = new RateLimitManager();
        ClientConfig<TestContract> base = ClientConfig.builder(TestContract.class, GsonSettings.builder().build()).build();
        return (SingleBucketPool<TestContract>) SubnetBucketPool.create(
            rotation, shared, "127.0.0.1:0",
            base, UnaryOperator.identity(), availability
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
    @DisplayName("Single bucket throws RateLimitException once availability fails on every spawned client")
    void saturationThrows() {
        AtomicReference<Boolean> available = new AtomicReference<>(true);
        SingleBucketPool<TestContract> p = pool(c -> available.get());

        p.selectClient();   // spawns one client; bucket has 1 available client
        available.set(false);

        assertThrows(RateLimitException.class, p::selectClient);
    }

    @Test
    @DisplayName("Saturation exception carries the subnet as bucket id")
    void saturationCarriesContext() {
        AtomicReference<Boolean> available = new AtomicReference<>(true);
        SingleBucketPool<TestContract> p = pool(c -> available.get());

        p.selectClient();
        available.set(false);

        RateLimitException ex = assertThrows(RateLimitException.class, p::selectClient);
        assertThat(ex.isServerEnforced(), is(false));
        assertThat(ex.getBucketId().contains("/56"), is(true));
    }

}
