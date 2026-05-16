package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.subnet.IPv6Prefix;
import dev.simplified.gson.GsonSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

class SubnetBucketTest {

    private static final String ANCHOR_BUCKET_KEY = "127.0.0.1:0@2001:db8:abcd:ef00::/56";

    private static SubnetBucket<TestContract> bucket(Predicate<Client<TestContract>> availability) {
        IPv6Prefix subnet = IPv6Prefix.parse("2001:db8:abcd:ef00::/56");
        ClientConfig<TestContract> base = ClientConfig.builder(TestContract.class, GsonSettings.builder().build()).build();
        return new SubnetBucket<>(subnet, ANCHOR_BUCKET_KEY, base, UnaryOperator.identity(), availability);
    }

    @Test
    @DisplayName("selectClient binds within the bucket's subnet")
    void selectClientBindsWithinSubnet() {
        SubnetBucket<TestContract> b = bucket(c -> true);
        Client<TestContract> client = b.selectClient();
        assertThat(client.getOptions().getInet6Address().isPresent(), is(true));
        assertThat(b.getSubnet().contains(client.getOptions().getInet6Address().get()), is(true));
    }

    @Test
    @DisplayName("Reuses an available existing client before spawning a new one")
    void reusesAvailableClients() {
        SubnetBucket<TestContract> b = bucket(c -> true);
        Client<TestContract> first = b.selectClient();
        Client<TestContract> second = b.selectClient();
        assertThat(second, is(first));
        assertThat(b.getClientCount(), is(1));
    }

    @Test
    @DisplayName("Spawns new clients when the availability predicate rejects existing ones")
    void spawnsWhenUnavailable() {
        SubnetBucket<TestContract> b = bucket(c -> false);
        Client<TestContract> first = b.selectClient();
        Client<TestContract> second = b.selectClient();
        assertThat(second, is(not(first)));
        assertThat(b.getClientCount(), is(2));
    }

    @Test
    @DisplayName("isSaturated false on an empty bucket")
    void emptyBucketNotSaturated() {
        SubnetBucket<TestContract> b = bucket(c -> false);
        assertThat(b.isSaturated(), is(false));
    }

    @Test
    @DisplayName("isSaturated true when no spawned client passes the availability predicate")
    void saturatedWhenAllClientsFail() {
        AtomicReference<Boolean> available = new AtomicReference<>(true);
        SubnetBucket<TestContract> b = bucket(c -> available.get());

        b.selectClient();
        assertThat(b.isSaturated(), is(false));

        available.set(false);
        assertThat(b.isSaturated(), is(true));
    }

    @Test
    @DisplayName("selectClient does not advance the shared rate-limit counter")
    void selectClientDoesNotTrack() {
        RateLimitManager shared = new RateLimitManager();
        IPv6Prefix subnet = IPv6Prefix.parse("2001:db8:abcd:ef00::/56");
        ClientConfig<TestContract> base = ClientConfig.builder(TestContract.class, GsonSettings.builder().build())
            .withRateLimitManager(shared)
            .withSubnetPrefix(subnet)
            .build();
        SubnetBucket<TestContract> b = new SubnetBucket<>(subnet, ANCHOR_BUCKET_KEY, base, UnaryOperator.identity(), c -> true);

        b.selectClient();
        b.selectClient();
        b.selectClient();

        // Tracking happens at the request-interceptor level; selectClient alone advances nothing.
        assertThat(shared.getRequestCount("127.0.0.1:0"), is(0L));
        assertThat(shared.getRequestCount("127.0.0.1:0@" + subnet), is(0L));
    }

}
