package dev.simplified.client.benchmark;

import dev.simplified.client.response.HttpStatus;
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
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class HttpStatusBenchmark {

    @Param({"200", "404", "500"})
    public int code;

    @Benchmark
    public HttpStatus newLookup() {
        return HttpStatus.of(code);
    }

    @Benchmark
    public HttpStatus oldLinearScan() {
        for (HttpStatus s : HttpStatus.values()) {
            if (s.getCode() == code)
                return s;
        }
        throw new IllegalArgumentException("Invalid HTTP status code: " + code);
    }

    @Benchmark
    public void newLookupConsume(Blackhole bh) {
        bh.consume(HttpStatus.of(code));
    }

    @Benchmark
    public void oldLinearScanConsume(Blackhole bh) {
        for (HttpStatus s : HttpStatus.values()) {
            if (s.getCode() == code) {
                bh.consume(s);
                return;
            }
        }
    }

}
