package dev.simplified.client.response;

import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.tuple.pair.Pair;
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

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class ResponseGetHeadersBenchmark {

    private Map<String, Collection<String>> sampleHeaders;

    @Setup
    public void setup() {
        sampleHeaders = new HashMap<>();
        sampleHeaders.put("Content-Type", List.of("application/json"));
        sampleHeaders.put("Content-Length", List.of("1024"));
        sampleHeaders.put("Date", List.of("Wed, 30 Apr 2026 12:00:00 GMT"));
        sampleHeaders.put("Server", List.of("nginx/1.25.0"));
        sampleHeaders.put("Cache-Control", List.of("public, max-age=3600"));
        sampleHeaders.put("ETag", List.of("\"abc123\""));
        sampleHeaders.put("Vary", List.of("Accept-Encoding"));
        sampleHeaders.put("X-Request-ID", List.of("req-123456"));
        sampleHeaders.put("CF-Cache-Status", List.of("HIT"));
        sampleHeaders.put("CF-RAY", List.of("ray-123"));
        sampleHeaders.put("Strict-Transport-Security", List.of("max-age=31536000"));
        sampleHeaders.put("X-Frame-Options", List.of("DENY"));
        sampleHeaders.put("X-Content-Type-Options", List.of("nosniff"));
        sampleHeaders.put("X-XSS-Protection", List.of("1; mode=block"));
        sampleHeaders.put(NetworkDetails.REQUEST_START, List.of("2026-04-30T12:00:00Z"));
        sampleHeaders.put(NetworkDetails.DNS_START, List.of("2026-04-30T12:00:00Z"));
        sampleHeaders.put(NetworkDetails.DNS_END, List.of("2026-04-30T12:00:00.001Z"));
        sampleHeaders.put(NetworkDetails.TCP_CONNECT_START, List.of("2026-04-30T12:00:00.001Z"));
        sampleHeaders.put(NetworkDetails.TCP_CONNECT_END, List.of("2026-04-30T12:00:00.005Z"));
    }

    @Benchmark
    public ConcurrentMap<String, ConcurrentList<String>> newImperative() {
        return Response.getHeaders(sampleHeaders);
    }

    @Benchmark
    @SuppressWarnings("unchecked")
    public ConcurrentMap<String, ConcurrentList<String>> oldStream() {
        return sampleHeaders.entrySet()
            .stream()
            .filter(entry -> !entry.getValue().isEmpty())
            .filter(entry -> !NetworkDetails.isInternalHeader(entry.getKey()))
            .map(entry -> Pair.of(
                entry.getKey(),
                (ConcurrentList<String>) Concurrent.newUnmodifiableList(entry.getValue())
            ))
            .collect(Concurrent.toUnmodifiableTreeMap(String.CASE_INSENSITIVE_ORDER));
    }

    @Benchmark
    public TreeMap<String, List<String>> stdlibImperativeBaseline() {
        TreeMap<String, List<String>> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, Collection<String>> entry : sampleHeaders.entrySet()) {
            Collection<String> values = entry.getValue();
            if (values.isEmpty()) continue;
            String key = entry.getKey();
            if (NetworkDetails.isInternalHeader(key)) continue;
            sorted.put(key, List.copyOf(values));
        }
        return sorted;
    }

}
