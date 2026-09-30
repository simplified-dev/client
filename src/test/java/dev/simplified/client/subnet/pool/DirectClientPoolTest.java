package dev.simplified.client.subnet.pool;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.gson.GsonSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests that a {@link DirectClientPool} builds its one client on the first selection and never a
 * second, however many callers select it at once, that the client sends from the host's default address under the injected manager, and
 * that a rejected client is refused rather than replaced.
 */
class DirectClientPoolTest {

    /**
     * The type-level route of {@link TestContract}, which a refusal names as its bucket.
     */
    private static final String ANCHOR = "127.0.0.1:0";

    /**
     * Clients the pool has built, counted by the mutator each build runs through.
     */
    private final AtomicInteger built = new AtomicInteger();

    /**
     * The manager the mutator hands every built client.
     */
    private final RateLimitManager shared = new RateLimitManager();

    /**
     * Whether the availability predicate passes.
     */
    private final AtomicBoolean available = new AtomicBoolean(true);

    private DirectClientPool<TestContract> pool() {
        ClientConfig<TestContract> base = ClientConfig.builder(TestContract.class, GsonSettings.builder().build()).build();
        UnaryOperator<ClientConfig.Builder<TestContract>> counting = builder -> {
            this.built.incrementAndGet();
            return builder.withRateLimitManager(this.shared);
        };
        Predicate<Client<TestContract>> availability = client -> this.available.get();
        ClientPool<TestContract> pool = ClientPool.create(Optional.empty(), this.shared, ANCHOR, base, counting, availability);
        assertThat(pool, instanceOf(DirectClientPool.class));
        return (DirectClientPool<TestContract>) pool;
    }

    @Test
    @DisplayName("Creating the pool builds no client")
    void createBuildsNoClient() {
        this.pool();
        assertThat(this.built.get(), is(0));
    }

    @Test
    @DisplayName("Repeated selections build exactly one client and return that instance every time")
    void repeatedSelectionsReturnOneClient() {
        DirectClientPool<TestContract> pool = this.pool();
        Client<TestContract> first = pool.selectClient();

        for (int i = 0; i < 20; i++)
            assertThat(pool.selectClient(), is(sameInstance(first)));

        assertThat(this.built.get(), is(1));
    }

    @Test
    @DisplayName("Concurrent first selections build exactly one client and all return that instance")
    void concurrentFirstSelectionsBuildOneClient() throws Exception {
        int threads = 16;
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        try {
            for (int round = 0; round < 10; round++) {
                this.built.set(0);
                DirectClientPool<TestContract> pool = this.pool();
                CountDownLatch ready = new CountDownLatch(threads);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Client<TestContract>>> selections = new ArrayList<>();

                for (int i = 0; i < threads; i++) {
                    selections.add(executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return pool.selectClient();
                    }));
                }

                ready.await();
                start.countDown();
                Client<TestContract> first = selections.getFirst().get(30, TimeUnit.SECONDS);

                for (Future<Client<TestContract>> selection : selections)
                    assertThat("round " + round, selection.get(30, TimeUnit.SECONDS), is(sameInstance(first)));

                assertThat("round " + round, this.built.get(), is(1));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("The client binds no source address, carries no subnet prefix and holds the injected manager")
    void clientSendsFromDefaultAddress() {
        Client<TestContract> client = this.pool().selectClient();

        assertThat(client.getOptions().getInet6Address().isPresent(), is(false));
        assertThat(client.getOptions().getSubnetPrefix().isPresent(), is(false));
        assertThat(client.getRateLimitManager(), is(sameInstance(this.shared)));
    }

    @Test
    @DisplayName("A rejected client is refused, client-enforced, under the anchor route, and not replaced")
    void rejectedClientThrows() {
        DirectClientPool<TestContract> pool = this.pool();
        pool.selectClient();
        this.available.set(false);

        RateLimitException refusal = assertThrows(RateLimitException.class, pool::selectClient);
        assertThat(refusal.isServerEnforced(), is(false));
        assertThat(refusal.getBucketId(), is(ANCHOR));

        assertThrows(RateLimitException.class, pool::selectClient);
        assertThat(this.built.get(), is(1));
    }

    @Test
    @DisplayName("The same client returns once the predicate passes again")
    void sameClientReturnsAfterRejection() {
        DirectClientPool<TestContract> pool = this.pool();
        Client<TestContract> first = pool.selectClient();

        this.available.set(false);
        assertThrows(RateLimitException.class, pool::selectClient);
        this.available.set(true);

        assertThat(pool.selectClient(), is(sameInstance(first)));
        assertThat(this.built.get(), is(1));
    }

}
