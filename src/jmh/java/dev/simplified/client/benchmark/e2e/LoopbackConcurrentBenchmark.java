package dev.simplified.client.benchmark.e2e;

import dev.simplified.client.Client;
import dev.simplified.client.benchmark.e2e.support.ClientFactory;
import dev.simplified.client.benchmark.e2e.support.LoopbackContract;
import dev.simplified.client.benchmark.e2e.support.LoopbackHttpsServer;
import dev.simplified.client.response.Response;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Concurrent end-to-end JMH benchmark - 16 threads hammering the loopback HTTPS path.
 *
 * <p>The single-thread {@link LoopbackThroughputBenchmark} cannot expose any of the wins
 * that come from removing contention in the Apache connection pool, because a single
 * thread never observes synchronisation it would otherwise be contending for. This
 * benchmark is the canary for the HC 4 -&gt; HC 5 migration: HC 4's
 * {@code PoolingHttpClientConnectionManager} uses {@code synchronized} blocks that pin
 * virtual-thread carriers on Java 21; HC 5 replaced them with
 * {@link ReentrantLock} and runs flat with thread count.</p>
 *
 * <p>Scenarios:</p>
 * <ul>
 *   <li>{@code cacheHit} - 16 threads short-circuiting on the response cache. Tests the
 *       library's contention behaviour on the cache replay path.</li>
 *   <li>{@code cacheMissJsonSmall} - 16 threads each issuing a real GET / decode. Tests
 *       the connection-pool contention path. <b>This is the headline number for the
 *       HC migration.</b></li>
 * </ul>
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@Threads(16)
@State(Scope.Benchmark)
public class LoopbackConcurrentBenchmark {

    private LoopbackHttpsServer server;
    private Client<LoopbackContract> client;
    private LoopbackContract contract;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        this.server = new LoopbackHttpsServer();
        this.server.start();
        this.client = ClientFactory.buildWithLoopback(this.server.clientSslContext());
        this.contract = this.client.getContract();

        // Seed the cache so cacheHit short-circuits from the first measured iteration.
        Response<?> seed = this.contract.cacheable();
        if (seed.getStatus().getCode() != 200)
            throw new IllegalStateException("Cacheable fixture failed to seed: " + seed.getStatus());
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (this.server != null) this.server.stop();
        this.server = null;
        this.client = null;
        this.contract = null;
    }

    @Benchmark
    public void cacheHit(Blackhole bh) {
        Response<?> response = this.contract.cacheable();
        bh.consume(response.getStatus());
        bh.consume(response.getBody());
    }

    @Benchmark
    public void cacheMissJsonSmall(Blackhole bh) {
        Response<?> response = this.contract.smallJson();
        bh.consume(response.getStatus());
        bh.consume(response.getBody());
    }

}
