package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.subnet.SubnetRotation;
import dev.simplified.gson.GsonSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;

/**
 * Tests that {@link ClientPool#create} answers a {@link DirectClientPool} for an empty rotation and
 * the {@link SubnetBucketPool} of a present rotation's prefix band.
 */
class ClientPoolTest {

    private static ClientPool<TestContract> pool(Optional<SubnetRotation> rotation) {
        ClientConfig<TestContract> base = ClientConfig.builder(TestContract.class, GsonSettings.builder().build()).build();
        Predicate<Client<TestContract>> availability = client -> true;
        return ClientPool.create(rotation, new RateLimitManager(), "127.0.0.1:0", base, UnaryOperator.identity(), availability);
    }

    private static ClientPool<TestContract> pool(int srcLen, int bucketLen) {
        return pool(Optional.of(
            SubnetRotation.builder()
                .sourcePrefix("2001:db8::/" + srcLen)
                .bucketPrefixLength(bucketLen)
                .build()
        ));
    }

    @Test
    @DisplayName("An empty rotation dispatches to DirectClientPool")
    void directForEmptyRotation() {
        assertThat(pool(Optional.empty()), instanceOf(DirectClientPool.class));
    }

    @Test
    @DisplayName("/48 source with /56 bucket dispatches to FanOutBucketPool")
    void fanOutForLargerSource() {
        assertThat(pool(48, 56), instanceOf(FanOutBucketPool.class));
    }

    @Test
    @DisplayName("/56 source with /56 bucket dispatches to SingleBucketPool")
    void singleForEqualLengths() {
        assertThat(pool(56, 56), instanceOf(SingleBucketPool.class));
    }

    @Test
    @DisplayName("/60 source with /56 bucket dispatches to PassThroughBucketPool")
    void passThroughForSmallerSource() {
        assertThat(pool(60, 56), instanceOf(PassThroughBucketPool.class));
    }

}
