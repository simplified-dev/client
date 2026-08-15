package dev.simplified.client.benchmark;

import dev.simplified.client.exception.ApiException;
import dev.simplified.client.exception.ErrorContext;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.request.Request;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import org.jetbrains.annotations.NotNull;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks the construction cost of an {@link ApiException} under the new primitive
 * {@link ErrorContext} model versus the prior eager construction style, across two access
 * patterns.
 *
 * <p>Scenarios:</p>
 * <ul>
 *   <li>{@code eagerConstruct_statusOnly} - Old eager pattern, caller reads only
 *       {@code status}. Pays the body / headers / details / request allocation cost
 *       up front.</li>
 *   <li>{@code lazyConstruct_statusOnly} - New lazy pattern, caller reads only
 *       {@code status}. Skips body / headers / request work entirely.</li>
 *   <li>{@code eagerConstruct_allFields} - Old eager pattern, caller reads all four
 *       lazy-eligible fields. Establishes the upper bound of what laziness must
 *       eventually defer.</li>
 *   <li>{@code lazyConstruct_allFields} - New lazy pattern, caller reads all four
 *       lazy-eligible fields. Quantifies the deferral wrapper's overhead when every
 *       field is materialized anyway.</li>
 * </ul>
 *
 * <p>Each iteration constructs from a fixed, realistic 200 B JSON body plus 15 headers
 * and a stable primitive {@link ErrorContext}.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class ApiExceptionConstructionBenchmark {

    /**
     * Realistic 200 B response body.
     */
    private byte[] bodyBytes;

    /**
     * 15 realistic response headers.
     */
    private Map<String, Collection<String>> responseHeaders;

    /**
     * Stable primitive context used for every constructed exception.
     */
    private ErrorContext context;

    @Setup(Level.Trial)
    public void setUp() {
        // ~200 byte JSON-shaped body
        String json = "{\"error\":\"Internal Server Error\",\"code\":500,\"message\":\"The server encountered an unexpected condition that prevented it from fulfilling the request\",\"trace\":\"abcdef0123456789\"}";
        this.bodyBytes = json.getBytes(StandardCharsets.UTF_8);

        // 15 representative response headers
        this.responseHeaders = new HashMap<>(15);
        this.responseHeaders.put("Content-Type", List.of("application/json; charset=utf-8"));
        this.responseHeaders.put("Content-Length", List.of(Integer.toString(this.bodyBytes.length)));
        this.responseHeaders.put("Date", List.of("Mon, 01 Apr 2026 12:00:00 GMT"));
        this.responseHeaders.put("Server", List.of("nginx/1.27.1"));
        this.responseHeaders.put("Cache-Control", List.of("no-store"));
        this.responseHeaders.put("X-Request-Id", List.of("d3adb33f-cafe-4242-c0ff-eebabe000001"));
        this.responseHeaders.put("X-RateLimit-Limit", List.of("100"));
        this.responseHeaders.put("X-RateLimit-Remaining", List.of("99"));
        this.responseHeaders.put("X-RateLimit-Reset", List.of("1700000000"));
        this.responseHeaders.put("CF-Ray", List.of("8a4e8b1e8c0e9d22-DFW"));
        this.responseHeaders.put("CF-Cache-Status", List.of("DYNAMIC"));
        this.responseHeaders.put("Strict-Transport-Security", List.of("max-age=31536000; includeSubDomains"));
        this.responseHeaders.put("Vary", List.of("Accept-Encoding, Accept"));
        this.responseHeaders.put("ETag", List.of("\"a1b2c3d4e5f60718\""));
        this.responseHeaders.put("Set-Cookie", List.of("session=abcdef; Path=/; HttpOnly"));

        this.context = new ErrorContext(
            HttpStatus.of(500),
            HttpMethod.GET,
            "https://api.example.com/v1/resource/42",
            this.responseHeaders,
            java.util.Collections.emptyMap(),
            this.bodyBytes
        );
    }

    @Benchmark
    public void eagerConstruct_statusOnly(Blackhole bh) {
        EagerApiException ex = new EagerApiException(this.context, "Bench");
        bh.consume(ex.getStatus());
    }

    @Benchmark
    public void lazyConstruct_statusOnly(Blackhole bh) {
        ApiException ex = new ApiException(null, "Bench", this.context);
        bh.consume(ex.getStatus());
    }

    @Benchmark
    public void eagerConstruct_allFields(Blackhole bh) {
        EagerApiException ex = new EagerApiException(this.context, "Bench");
        bh.consume(ex.getStatus());
        bh.consume(ex.getBody());
        bh.consume(ex.getDetails());
        bh.consume(ex.getHeaders());
        bh.consume(ex.getRequest());
    }

    @Benchmark
    public void lazyConstruct_allFields(Blackhole bh) {
        ApiException ex = new ApiException(null, "Bench", this.context);
        bh.consume(ex.getStatus());
        bh.consume(ex.getBody());
        bh.consume(ex.getDetails());
        bh.consume(ex.getHeaders());
        bh.consume(ex.getRequest());
    }

    /**
     * Reproduction of the pre-refactor eager construction pattern, used purely as a
     * baseline for the lazy implementation in {@link ApiException}. Mirrors the field set
     * and constructor work of the prior shape so the benchmark numbers reflect the same
     * unit of work.
     */
    private static final class EagerApiException extends RuntimeException {

        private final @NotNull String name;
        private final @NotNull HttpStatus status;
        private final @NotNull Optional<byte[]> body;
        private final @NotNull NetworkDetails details;
        private final @NotNull ConcurrentMap<String, ConcurrentList<String>> headers;
        private final @NotNull Request request;

        EagerApiException(@NotNull ErrorContext context, @NotNull String name) {
            super("bench", null, true, true);
            this.name = name;
            this.status = context.status();
            this.body = context.bodyBytes().length == 0 ? Optional.empty() : Optional.of(context.bodyBytes());
            this.details = new NetworkDetails(context.responseHeaders(), context.requestHeaders());
            this.headers = Response.getHeaders(toMutableHeaders(context.responseHeaders()));
            this.request = new Request.Impl(context.requestMethod(), context.requestUrl());
        }

        @NotNull HttpStatus getStatus() { return this.status; }
        @NotNull Optional<byte[]> getBody() { return this.body; }
        @NotNull NetworkDetails getDetails() { return this.details; }
        @NotNull ConcurrentMap<String, ConcurrentList<String>> getHeaders() { return this.headers; }
        @NotNull Request getRequest() { return this.request; }
        @NotNull String getName() { return this.name; }

        private static @NotNull Map<String, Collection<String>> toMutableHeaders(@NotNull Map<String, Collection<String>> source) {
            if (source.isEmpty())
                return Collections.emptyMap();

            Map<String, Collection<String>> copy = new HashMap<>(source.size());
            for (Map.Entry<String, Collection<String>> entry : source.entrySet())
                copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            return copy;
        }

    }

}
