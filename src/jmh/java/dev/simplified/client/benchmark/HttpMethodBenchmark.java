package dev.simplified.client.benchmark;

import dev.simplified.client.request.HttpMethod;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class HttpMethodBenchmark {

    @Param({"GET", "POST", "DELETE"})
    public String name;

    @Benchmark
    public HttpMethod newLookup() {
        return HttpMethod.of(name);
    }

    @Benchmark
    public HttpMethod oldStream() {
        return Arrays.stream(HttpMethod.values())
            .filter(value -> value.name().equalsIgnoreCase(name))
            .findFirst()
            .orElse(HttpMethod.GET);
    }

}
