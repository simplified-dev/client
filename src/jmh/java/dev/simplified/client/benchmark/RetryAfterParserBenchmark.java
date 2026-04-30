package dev.simplified.client.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class RetryAfterParserBenchmark {

    private static final Pattern TRAILING_ZERO_DECIMAL = Pattern.compile("\\.0*$");
    private static final String SAMPLE = "120.000";

    @Benchmark
    public String newStaticPattern() {
        return TRAILING_ZERO_DECIMAL.matcher(SAMPLE).replaceAll("");
    }

    @Benchmark
    public String oldReplaceAll() {
        return SAMPLE.replaceAll("\\.0*$", "");
    }

}
