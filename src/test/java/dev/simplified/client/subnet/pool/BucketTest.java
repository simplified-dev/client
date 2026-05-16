package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.subnet.IpPrefix;
import dev.simplified.gson.GsonSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.temporal.ChronoUnit;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class BucketTest {

    private static Bucket<TestContract> bucket(int limit, double softCapFraction) {
        IpPrefix subnet = IpPrefix.parse("2001:db8:abcd:ef00::/56");
        RateLimit budget = RateLimit.builder().limit(limit).window(1, ChronoUnit.MINUTES).build();
        long softCap = (long) Math.floor(limit * softCapFraction);
        ClientConfig<TestContract> base = ClientConfig.builder(TestContract.class, GsonSettings.builder().build()).build();
        return new Bucket<>(
            subnet,
            budget,
            softCap,
            base,
            UnaryOperator.identity(),
            (Predicate<Client<TestContract>>) c -> true
        );
    }

    @Test
    @DisplayName("selectClient binds within the bucket's subnet")
    void selectClientBindsWithinSubnet() {
        Bucket<TestContract> b = bucket(10, 0.9);
        Client<TestContract> client = b.selectClient();
        assertThat(client.getOptions().getInet6Address().isPresent(), is(true));
        assertThat(b.getSubnet().contains(client.getOptions().getInet6Address().get()), is(true));
    }

    @Test
    @DisplayName("isSaturated flips at the soft cap threshold")
    void saturationThreshold() {
        // softCap = floor(10 * 0.5) = 5
        Bucket<TestContract> b = bucket(10, 0.5);
        for (int i = 0; i < 4; i++) {
            b.selectClient();
            assertThat(b.isSaturated(), is(false));
        }
        b.selectClient(); // count now 5, which equals softCap
        assertThat(b.isSaturated(), is(true));
    }

    @Test
    @DisplayName("Reuses existing available clients before spawning new ones")
    void reusesAvailableClients() {
        Bucket<TestContract> b = bucket(100, 0.9);
        Client<TestContract> first = b.selectClient();
        Client<TestContract> second = b.selectClient();
        // With availability predicate always true, the same Client should be reused.
        assertThat(second, is(first));
    }

    @Test
    @DisplayName("Spawns new client when no existing client is available")
    void spawnsWhenUnavailable() {
        IpPrefix subnet = IpPrefix.parse("2001:db8:abcd:ef00::/56");
        ClientConfig<TestContract> base = ClientConfig.builder(TestContract.class, GsonSettings.builder().build()).build();
        // Availability predicate returns false -> existing clients always unavailable.
        Bucket<TestContract> b = new Bucket<>(
            subnet,
            RateLimit.UNLIMITED,
            Long.MAX_VALUE,
            base,
            UnaryOperator.identity(),
            (Predicate<Client<TestContract>>) c -> false
        );

        Client<TestContract> first = b.selectClient();
        Client<TestContract> second = b.selectClient();
        assertThat(second, is(org.hamcrest.Matchers.not(first)));
    }

    @Test
    @DisplayName("Tracks request count after each selectClient call")
    void requestCountTracked() {
        Bucket<TestContract> b = bucket(100, 0.9);
        assertThat(b.getCount(), is(0L));
        b.selectClient();
        assertThat(b.getCount(), is(1L));
        b.selectClient();
        assertThat(b.getCount(), is(2L));
    }

    @Test
    @DisplayName("Unlimited budget never saturates")
    void unlimitedNeverSaturates() {
        IpPrefix subnet = IpPrefix.parse("2001:db8:abcd:ef00::/56");
        ClientConfig<TestContract> base = ClientConfig.builder(TestContract.class, GsonSettings.builder().build()).build();
        Bucket<TestContract> b = new Bucket<>(
            subnet,
            RateLimit.UNLIMITED,
            Long.MAX_VALUE,
            base,
            UnaryOperator.identity(),
            (Predicate<Client<TestContract>>) c -> true
        );
        for (int i = 0; i < 50; i++) b.selectClient();
        assertThat(b.isSaturated(), is(false));
    }

}
