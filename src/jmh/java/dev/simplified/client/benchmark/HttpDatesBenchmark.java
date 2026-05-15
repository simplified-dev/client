package dev.simplified.client.benchmark;

import dev.simplified.client.util.HttpDates;
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

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks {@link HttpDates#parse(String)} across the three accepted RFC 7231 formats plus
 * a malformed control input.
 *
 * <p>Fixtures:</p>
 * <ul>
 *   <li>{@code imf} - the preferred RFC 5322 / RFC 1123 format, hits on the first parse attempt.</li>
 *   <li>{@code rfc850} - the legacy obsoleted format, hits on the second parse attempt
 *       (after IMF-fixdate fails).</li>
 *   <li>{@code asctime} - the ANSI C output format with space-padded single-digit days,
 *       hits on the third parse attempt (worst case for the sequential parse).</li>
 *   <li>{@code malformed} - input that no format accepts; pays the full sequential cost and
 *       returns {@link Optional#empty()}.</li>
 * </ul>
 *
 * <p>For thread contention measurement, run with {@code -PjmhThreads=16}.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class HttpDatesBenchmark {

    @Param({"imf", "rfc850", "asctime", "malformed"})
    public String fixture;

    private String input;

    @Setup
    public void setUp() {
        this.input = switch (this.fixture) {
            case "imf" -> "Sun, 06 Nov 1994 08:49:37 GMT";
            case "rfc850" -> "Sunday, 06-Nov-94 08:49:37 GMT";
            case "asctime" -> "Sun Nov  6 08:49:37 1994";
            case "malformed" -> "not a date";
            default -> throw new IllegalStateException("unknown fixture " + this.fixture);
        };
    }

    @Benchmark
    public Optional<Instant> parse() {
        return HttpDates.parse(this.input);
    }

}
