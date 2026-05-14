package dev.simplified.client.benchmark.e2e;

import dev.simplified.client.Client;
import dev.simplified.client.benchmark.e2e.support.BenchmarkContract;
import dev.simplified.client.benchmark.e2e.support.CannedFeignClient;
import dev.simplified.client.benchmark.e2e.support.ClientFactory;
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

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end JMH benchmark that exercises the full client pipeline (request interceptor,
 * route discovery, rate limit, caching feign client, response decoder, error decoder,
 * response interceptor, Gson) against an in-process {@link CannedFeignClient}.
 *
 * <p>The canned transport returns pre-built {@code feign.Response} objects from in-memory
 * byte arrays, skipping the Apache HTTP and socket stacks so that library-level optimizations
 * are not drowned in kernel and TCP loopback noise.</p>
 *
 * <p>Scenarios:</p>
 * <ul>
 *   <li>{@code cacheHit} - GET cacheable URL, response already in cache; short-circuits in
 *       {@code CachingFeignClient} without invoking the transport.</li>
 *   <li>{@code cacheMissJsonSmall} - GET 200 B JSON, no cache-control; full decode pipeline.</li>
 *   <li>{@code cacheMissJsonLarge} - GET 64 KB JSON; exercises body buffering and Gson parse.</li>
 *   <li>{@code errorResponse} - GET 503; exercises {@code InternalErrorDecoder} path.</li>
 *   <li>{@code streamingBody} - GET {@code Response<InputStream>}; consumes the stream.</li>
 *   <li>{@code multiTypeRoundRobin} - rotates four distinct {@code Response<T>} return types
 *       so Gson {@code TypeAdapter} caches are exercised on rotation.</li>
 * </ul>
 */
@BenchmarkMode({Mode.AverageTime, Mode.Throughput})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class LibraryHotPathBenchmark {

    private Client<BenchmarkContract> client;
    private BenchmarkContract contract;
    private int typeIndex;

    @Setup(Level.Trial)
    public void setUp() {
        CannedFeignClient transport = ClientFactory.defaultCannedTransport();
        this.client = ClientFactory.buildWithCanned(transport);
        this.contract = this.client.getContract();

        // Seed the cache so the cacheHit benchmark short-circuits from iteration #1.
        Response<?> seed = this.contract.cacheable();
        if (seed.getStatus().getCode() != 200)
            throw new IllegalStateException("Cacheable fixture failed to seed: " + seed.getStatus());
    }

    @TearDown(Level.Trial)
    public void tearDown() {
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
    public void cacheMissJsonLarge(Blackhole bh) {
        Response<?> response = this.contract.largeJson();
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

    @Benchmark
    public void streamingBody(Blackhole bh) throws IOException {
        Response<InputStream> response = this.contract.streaming();
        bh.consume(response.getStatus());
        try (InputStream stream = response.getBody()) {
            int total = 0;
            byte[] buffer = new byte[1024];
            int read;
            while ((read = stream.read(buffer)) != -1)
                total += read;
            bh.consume(total);
        }
    }

    @Benchmark
    public void multiTypeRoundRobin(Blackhole bh) {
        Response<?> response = switch (this.typeIndex++ & 3) {
            case 0 -> this.contract.typeA();
            case 1 -> this.contract.typeB();
            case 2 -> this.contract.typeC();
            default -> this.contract.typeD();
        };
        bh.consume(response.getStatus());
        bh.consume(response.getBody());
    }

}
