package dev.simplified.client.benchmark.e2e;

import dev.simplified.client.Client;
import dev.simplified.client.benchmark.e2e.support.ClientFactory;
import dev.simplified.client.benchmark.e2e.support.LoopbackContract;
import dev.simplified.client.benchmark.e2e.support.LoopbackHttpsServer;
import dev.simplified.client.exception.ApiException;
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

/**
 * End-to-end JMH benchmark that exercises the full client pipeline over a real loopback
 * HTTPS socket served by {@link LoopbackHttpsServer}.
 *
 * <p>Unlike {@link LibraryHotPathBenchmark} (which short-circuits the transport via a
 * {@code CannedFeignClient}), this benchmark goes through Apache HttpClient, real DNS
 * lookup, real TCP, real TLS, and the response cache. Use it to measure transport-layer
 * optimizations (B1 pool prewarm, B2 DNS preresolve, B5 timing-header round-trip) where
 * an in-process bench cannot show the delta.</p>
 *
 * <p>Steady-state measurement: connections are kept alive across iterations so the JIT
 * sees a warm pipeline. For cold-start measurement, see {@link LoopbackColdStartBenchmark}.</p>
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class LoopbackThroughputBenchmark {

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

    @Benchmark
    public void errorResponse(Blackhole bh) {
        try {
            this.contract.error();
        } catch (ApiException ex) {
            bh.consume(ex.getStatus());
            bh.consume(ex.getBody());
        }
    }

}
