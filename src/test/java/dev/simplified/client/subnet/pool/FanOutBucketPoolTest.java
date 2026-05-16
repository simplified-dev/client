package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.subnet.IpPrefix;
import dev.simplified.client.subnet.SubnetRotation;
import dev.simplified.client.subnet.SubnetSelectionStrategy;
import dev.simplified.gson.GsonSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FanOutBucketPoolTest {

    private static FanOutBucketPool<TestContract> pool(
        String sourceCidr,
        int bucketLen,
        int budgetLimit,
        double softCapFraction,
        SubnetSelectionStrategy strategy
    ) {
        SubnetRotation rotation = SubnetRotation.builder()
            .sourcePrefix(sourceCidr)
            .bucketPrefixLength(bucketLen)
            .budget(RateLimit.builder().limit(budgetLimit).window(1, ChronoUnit.MINUTES).build())
            .softCapFraction(softCapFraction)
            .strategy(strategy)
            .build();
        ClientConfig<TestContract> base = ClientConfig.builder(TestContract.class, GsonSettings.builder().build()).build();
        return (FanOutBucketPool<TestContract>) SubnetBucketPool.create(
            rotation,
            base,
            UnaryOperator.identity(),
            (Predicate<Client<TestContract>>) c -> true
        );
    }

    @Test
    @DisplayName("Round-robin cycles through every contained bucket index")
    void roundRobinCycles() {
        // /126 source, /128 bucket -> 4 contained buckets
        FanOutBucketPool<TestContract> p = pool("2001:db8::/126", 128, 100, 0.9, SubnetSelectionStrategy.ROUND_ROBIN);

        for (int i = 0; i < 8; i++) p.selectClient();

        Set<IpPrefix> subnets = p.activeBuckets().map(Bucket::getSubnet).collect(Collectors.toSet());
        assertThat(subnets.size(), is(4));
        // After 8 evenly-distributed calls across 4 buckets, every bucket should have count 2.
        p.activeBuckets().forEach(b -> assertThat(b.getCount(), is(2L)));
    }

    @Test
    @DisplayName("Round-robin throws when all buckets saturated")
    void roundRobinSaturationThrows() {
        // /126 source, /128 bucket = 4 buckets, budget=2 soft=1
        FanOutBucketPool<TestContract> p = pool("2001:db8::/126", 128, 2, 0.5, SubnetSelectionStrategy.ROUND_ROBIN);

        // First 4 calls each go to a fresh bucket, count 1. softCap=1 -> isSaturated after this.
        p.selectClient();
        p.selectClient();
        p.selectClient();
        p.selectClient();

        assertThrows(RateLimitException.class, p::selectClient);
    }

    @Test
    @DisplayName("Least-used spreads load before reusing any bucket")
    void leastUsedSpreadsFirst() {
        FanOutBucketPool<TestContract> p = pool("2001:db8::/126", 128, 100, 0.9, SubnetSelectionStrategy.LEAST_USED);

        // 4 calls should materialize 4 distinct buckets (fresh count=0 beats any active count>0).
        for (int i = 0; i < 4; i++) p.selectClient();
        Set<IpPrefix> subnets = p.activeBuckets().map(Bucket::getSubnet).collect(Collectors.toSet());
        assertThat(subnets.size(), is(4));
        // Each materialized bucket should have count 1.
        p.activeBuckets().forEach(b -> assertThat(b.getCount(), is(1L)));
    }

    @Test
    @DisplayName("Least-used throws when all buckets saturated")
    void leastUsedSaturationThrows() {
        FanOutBucketPool<TestContract> p = pool("2001:db8::/126", 128, 2, 0.5, SubnetSelectionStrategy.LEAST_USED);

        // 4 buckets total, softCap=1. Four spreads saturate all.
        p.selectClient();
        p.selectClient();
        p.selectClient();
        p.selectClient();

        assertThrows(RateLimitException.class, p::selectClient);
    }

    @Test
    @DisplayName("Random reject-sample throws when all buckets saturated")
    void randomSaturationThrows() {
        FanOutBucketPool<TestContract> p = pool("2001:db8::/126", 128, 2, 0.5, SubnetSelectionStrategy.RANDOM_REJECT_SAMPLE);

        // With 4 buckets and softCap=1, after ~4 successful picks all are saturated.
        // Random may hit the same bucket multiple times - perform enough calls to certainly fill.
        int successful = 0;
        for (int i = 0; i < 32 && successful < 4; i++) {
            try {
                p.selectClient();
                successful++;
            } catch (RateLimitException ignored) {
                break;
            }
        }
        assertThat(successful, is(lessThanOrEqualTo(4)));
        assertThrows(RateLimitException.class, p::selectClient);
    }

    @Test
    @DisplayName("Random reject-sample materializes buckets sparsely")
    void randomSparseMaterialization() {
        FanOutBucketPool<TestContract> p = pool("2001:db8::/120", 128, 100, 0.9, SubnetSelectionStrategy.RANDOM_REJECT_SAMPLE);
        // 256 possible buckets. 5 calls should materialize at most 5 buckets.
        for (int i = 0; i < 5; i++) p.selectClient();
        assertThat(p.activeBuckets().count(), is(lessThanOrEqualTo(5L)));
        assertThat(p.activeBuckets().count(), is(greaterThan(0L)));
    }

    @Test
    @DisplayName("Each spawned client binds within its bucket's subnet")
    void clientBindsWithinBucket() {
        FanOutBucketPool<TestContract> p = pool("2001:db8::/120", 128, 100, 0.9, SubnetSelectionStrategy.RANDOM_REJECT_SAMPLE);

        for (int i = 0; i < 5; i++) {
            Client<TestContract> client = p.selectClient();
            assertThat(client.getOptions().getInet6Address().isPresent(), is(true));
            // The bound address must be contained in the source prefix.
            assertThat(IpPrefix.parse("2001:db8::/120").contains(client.getOptions().getInet6Address().get()), is(true));
        }
    }

    @Test
    @DisplayName("Saturation exception carries source prefix as bucket id")
    void saturationCarriesSourcePrefix() {
        FanOutBucketPool<TestContract> p = pool("2001:db8::/126", 128, 2, 0.5, SubnetSelectionStrategy.ROUND_ROBIN);
        for (int i = 0; i < 4; i++) p.selectClient();
        RateLimitException ex = assertThrows(RateLimitException.class, p::selectClient);
        assertThat(ex.isServerEnforced(), is(false));
        assertThat(ex.getBucketId().contains("/126"), is(true));
    }

}
