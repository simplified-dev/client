package dev.simplified.client.benchmark;

import dev.simplified.client.request.expander.StringArrayQuoteExpander;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class StringArrayQuoteExpanderBenchmark {

    @Param({"5", "50"})
    public int size;

    private String[] data;
    private StringArrayQuoteExpander expander;

    @Setup
    public void setup() {
        data = new String[size];
        for (int i = 0; i < size; i++)
            data[i] = "item-" + i;
        expander = new StringArrayQuoteExpander();
    }

    @Benchmark
    public String newStringBuilder() {
        return expander.expand(data);
    }

    @Benchmark
    public String oldStreamFormat() {
        return Arrays.stream(data)
            .map(str -> String.format("\"%s\"", str))
            .collect(Collectors.joining(","));
    }

}
