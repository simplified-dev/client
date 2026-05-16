package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.subnet.SubnetRotation;
import dev.simplified.gson.GsonSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;

class SubnetBucketPoolTest {

    private static ClientConfig<TestContract> baseConfig() {
        return ClientConfig.builder(TestContract.class, GsonSettings.builder().build()).build();
    }

    private static SubnetBucketPool<TestContract> pool(int srcLen, int bucketLen) {
        SubnetRotation rotation = SubnetRotation.builder()
            .sourcePrefix("2001:db8::/" + srcLen)
            .bucketPrefixLength(bucketLen)
            .build();
        return SubnetBucketPool.create(
            rotation,
            new RateLimitManager(),
            "127.0.0.1:0",
            baseConfig(),
            UnaryOperator.identity(),
            (Predicate<Client<TestContract>>) client -> true
        );
    }

    @Test
    @DisplayName("/40 source with /56 bucket dispatches to FanOutBucketPool")
    void fanOutForLargeSource() {
        assertThat(pool(40, 56), instanceOf(FanOutBucketPool.class));
    }

    @Test
    @DisplayName("/48 source with /56 bucket dispatches to FanOutBucketPool")
    void fanOutForHeTunnel() {
        assertThat(pool(48, 56), instanceOf(FanOutBucketPool.class));
    }

    @Test
    @DisplayName("/56 source with /56 bucket dispatches to SingleBucketPool")
    void singleForEqualLengths() {
        assertThat(pool(56, 56), instanceOf(SingleBucketPool.class));
    }

    @Test
    @DisplayName("/60 source with /56 bucket dispatches to PassThroughBucketPool")
    void passThroughForSmallerSource60() {
        assertThat(pool(60, 56), instanceOf(PassThroughBucketPool.class));
    }

    @Test
    @DisplayName("/64 source with /56 bucket dispatches to PassThroughBucketPool")
    void passThroughForSmallerSource64() {
        assertThat(pool(64, 56), instanceOf(PassThroughBucketPool.class));
    }

}
