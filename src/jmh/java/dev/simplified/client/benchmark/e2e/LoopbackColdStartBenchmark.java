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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Time-to-first-success benchmark for the loopback HTTPS path. Each measured invocation
 * runs in a fresh fork so DNS resolution, TCP handshake, TLS handshake, and Gson adapter
 * generation all happen from a cold state.
 *
 * <p>This is the only benchmark capable of demonstrating Phase 4 cold-start wins
 * (B1 Apache pool prewarm, B2 DNS preresolve, B4 Gson adapter prewarm). Compare the
 * baseline run captured here against the post-Phase-4 run.</p>
 *
 * <p>Note: the HTTPS server in {@link LoopbackHttpsServer} starts in {@link Setup} per
 * invocation, so the server-startup cost (cert load, keystore parse, socket bind) is
 * included in the measured region. That cost is constant across pre and post measurements
 * and cancels in the delta - we care about the relative drop, not the absolute number.</p>
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 5)
@Fork(value = 5, warmups = 0)
@State(Scope.Benchmark)
public class LoopbackColdStartBenchmark {

    private LoopbackHttpsServer server;
    private Client<LoopbackContract> client;

    @Setup(Level.Trial)
    public void startServer() throws Exception {
        this.server = new LoopbackHttpsServer();
        this.server.start();
    }

    @TearDown(Level.Trial)
    public void stopServer() {
        if (this.server != null) this.server.stop();
        this.server = null;
    }

    @Setup(Level.Invocation)
    public void freshClient() {
        // Each measured invocation gets a brand-new Client - cold adapter cache, cold
        // connection pool, no warmed-up DNS at the Apache layer. This is the entry point
        // Phase 4's B1/B4 affect.
        this.client = ClientFactory.buildWithLoopback(this.server.clientSslContext());
    }

    @TearDown(Level.Invocation)
    public void disposeClient() {
        this.client = null;
    }

    @Benchmark
    public void firstRequest(Blackhole bh) {
        Response<?> response = this.client.getContract().smallJson();
        bh.consume(response.getStatus());
        bh.consume(response.getBody());
    }

}
