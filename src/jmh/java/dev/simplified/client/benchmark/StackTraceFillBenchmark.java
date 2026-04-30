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

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class StackTraceFillBenchmark {

    private static class WithStack extends RuntimeException {
        WithStack(String msg) { super(msg, null, true, true); }
    }

    private static class NoStack extends RuntimeException {
        NoStack(String msg) { super(msg, null, true, false); }
    }

    private static class OverrideFill extends RuntimeException {
        OverrideFill(String msg) { super(msg); }
        @Override public synchronized Throwable fillInStackTrace() { return this; }
    }

    private static final RuntimeException SHARED_NO_STACK = new RuntimeException("seed");

    @Benchmark
    public Throwable oldWithStackTrace() {
        return new WithStack("Rate limited");
    }

    @Benchmark
    public Throwable newDisabledWritableStackTrace() {
        return new NoStack("Rate limited");
    }

    @Benchmark
    public Throwable newOverrideFillInStackTrace() {
        return new OverrideFill("Rate limited");
    }

}
