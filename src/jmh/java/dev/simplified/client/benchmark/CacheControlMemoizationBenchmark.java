package dev.simplified.client.benchmark;

import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.request.Request;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks {@link Response.CachedImpl} freshness and stale-if-error decision paths to
 * measure the cost of re-parsing the {@code Cache-Control} header on every directive
 * lookup.
 *
 * <p>Scenarios:</p>
 * <ul>
 *   <li>{@code freshnessCheck} - {@code isFresh(now)}; current implementation parses the
 *       header once.</li>
 *   <li>{@code staleReplayDecision} - {@code canServeStaleOnError(now)}; parses twice
 *       (via {@code staleIfError} and {@code freshnessLifetime}).</li>
 *   <li>{@code fullPipeline} - simulates the {@code CachingFeignClient} stale-path
 *       sequence: {@code isFresh} + {@code mustRevalidate} + {@code canServeStaleOnError};
 *       parses four times.</li>
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class CacheControlMemoizationBenchmark {

    private Response.CachedImpl<byte[]> cached;
    private Instant now;

    @Setup
    public void setUp() {
        ConcurrentMap<String, ConcurrentList<String>> headers = Concurrent.newMap();
        headers.put("Cache-Control", Concurrent.newList(List.of(
            "public, max-age=300, s-maxage=60, stale-if-error=120, stale-while-revalidate=30"
        )));
        headers.put("Date", Concurrent.newList(List.of("Sun, 06 Nov 1994 08:49:37 GMT")));
        headers.put("Age", Concurrent.newList(List.of("17")));
        headers.put("ETag", Concurrent.newList(List.of("\"abc123\"")));

        byte[] body = new byte[]{0};
        Response.DirectImpl<byte[]> source = new Response.DirectImpl<>(
            HttpStatus.OK,
            new Request.Impl(HttpMethod.GET, "https://example.com/r"),
            () -> NetworkDetails.EMPTY,
            headers,
            () -> body
        );

        this.cached = Response.CachedImpl.from(source);
        this.now = Instant.parse("1994-11-06T08:54:37Z"); // 5 minutes after Date
    }

    @Benchmark
    public boolean freshnessCheck() {
        return this.cached.isFresh(this.now);
    }

    @Benchmark
    public boolean staleReplayDecision() {
        return this.cached.canServeStaleOnError(this.now);
    }

    @Benchmark
    public void fullPipeline(Blackhole bh) {
        bh.consume(this.cached.isFresh(this.now));
        bh.consume(this.cached.mustRevalidate());
        bh.consume(this.cached.canServeStaleOnError(this.now));
    }

}
