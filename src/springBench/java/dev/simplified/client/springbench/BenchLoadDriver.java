package dev.simplified.client.springbench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Virtual-thread HTTP load generator targeting the Spring bench. Spawns one
 * virtual thread per VU, each running a tight loop of {@link HttpClient} calls
 * for the configured duration, then prints aggregated latency + throughput
 * numbers to stdout.
 *
 * <p>CLI flags (all optional, with defaults):</p>
 * <ul>
 *   <li>{@code --url <base>}        default {@code http://127.0.0.1:8080}</li>
 *   <li>{@code --vus <int>}         default {@code 500}</li>
 *   <li>{@code --duration <secs>}   default {@code 30}</li>
 *   <li>{@code --warmup <secs>}     default {@code 5}</li>
 *   <li>{@code --scenario miss|hit} default {@code miss}</li>
 *   <li>{@code --tag <string>}      free-form label printed with the summary</li>
 * </ul>
 *
 * <p>Why one virtual thread per VU rather than async {@code sendAsync}: this
 * mirrors the way real Spring traffic flows through the bench - each Spring
 * controller invocation is one thread (virtual or platform) parked on the
 * Feign call. The load driver should look like the production caller.</p>
 */
public final class BenchLoadDriver {

    private BenchLoadDriver() { }

    public static void main(String[] args) throws Exception {
        String baseUrl = arg(args, "--url", "http://127.0.0.1:8080");
        int vus = Integer.parseInt(arg(args, "--vus", "500"));
        int durationSecs = Integer.parseInt(arg(args, "--duration", "30"));
        int warmupSecs = Integer.parseInt(arg(args, "--warmup", "5"));
        String scenario = arg(args, "--scenario", "miss");
        String tag = arg(args, "--tag", "");

        if (!scenario.equals("miss") && !scenario.equals("hit"))
            throw new IllegalArgumentException("--scenario must be miss or hit, got: " + scenario);

        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

        System.out.printf("[driver] url=%s vus=%d duration=%ds warmup=%ds scenario=%s tag=%s%n",
            baseUrl, vus, durationSecs, warmupSecs, scenario, tag);

        // Warm up the driver-side HTTP client + the Spring app's prewarm path so
        // the measurement window doesn't see cold-start tax.
        if (warmupSecs > 0) {
            System.out.println("[driver] warming up...");
            runWindow(http, baseUrl, scenario, vus, Duration.ofSeconds(warmupSecs), null, null, null);
        }

        AtomicLong success = new AtomicLong();
        AtomicLong failure = new AtomicLong();
        ConcurrentHashMap<Long, long[]> threadLatencies = new ConcurrentHashMap<>();
        ConcurrentHashMap<Long, Integer> threadCounts = new ConcurrentHashMap<>();

        long start = System.nanoTime();
        runWindow(http, baseUrl, scenario, vus, Duration.ofSeconds(durationSecs),
            success, failure, (latencyNanos) -> {
                long tid = Thread.currentThread().threadId();
                long[] arr = threadLatencies.computeIfAbsent(tid, k -> new long[16384]);
                int idx = threadCounts.getOrDefault(tid, 0);
                if (idx < arr.length) {
                    arr[idx] = latencyNanos;
                    threadCounts.put(tid, idx + 1);
                }
            });
        long elapsedNanos = System.nanoTime() - start;

        long totalSamples = 0;
        for (Integer count : threadCounts.values()) totalSamples += count;
        long[] all = new long[(int) totalSamples];
        int offset = 0;
        for (var entry : threadLatencies.entrySet()) {
            int count = threadCounts.getOrDefault(entry.getKey(), 0);
            System.arraycopy(entry.getValue(), 0, all, offset, count);
            offset += count;
        }
        Arrays.sort(all, 0, offset);

        long requests = success.get() + failure.get();
        double elapsedSecs = elapsedNanos / 1_000_000_000.0;
        double rps = requests / elapsedSecs;
        double errorRate = requests == 0 ? 0.0 : (failure.get() * 100.0 / requests);

        System.out.println();
        System.out.println("====================================================");
        System.out.printf("[result] tag=%s scenario=%s vus=%d%n", tag, scenario, vus);
        System.out.printf("  duration       : %.2f s%n", elapsedSecs);
        System.out.printf("  requests       : %d total (%.0f req/s)%n", requests, rps);
        System.out.printf("  success/fail   : %d / %d (%.2f%% errors)%n", success.get(), failure.get(), errorRate);
        if (offset > 0) {
            System.out.printf("  latency p50    : %s%n", fmt(all[(int) (offset * 0.50)]));
            System.out.printf("  latency p90    : %s%n", fmt(all[(int) (offset * 0.90)]));
            System.out.printf("  latency p95    : %s%n", fmt(all[(int) (offset * 0.95)]));
            System.out.printf("  latency p99    : %s%n", fmt(all[(int) (offset * 0.99)]));
            System.out.printf("  latency p99.9  : %s%n", fmt(all[(int) (offset * 0.999)]));
            System.out.printf("  latency max    : %s%n", fmt(all[offset - 1]));
        }
        System.out.println("====================================================");
    }

    @FunctionalInterface
    private interface LatencySink {
        void record(long latencyNanos);
    }

    private static void runWindow(
        HttpClient http,
        String baseUrl,
        String scenario,
        int vus,
        Duration duration,
        AtomicLong success,
        AtomicLong failure,
        LatencySink sink
    ) throws InterruptedException {
        long deadline = System.nanoTime() + duration.toNanos();
        CountDownLatch done = new CountDownLatch(vus);

        for (int i = 0; i < vus; i++) {
            int vuId = i;
            Thread.ofVirtual().name("vu-" + vuId).start(() -> {
                try {
                    long localSeed = vuId * 0x9E3779B97F4A7C15L + System.nanoTime();
                    while (System.nanoTime() < deadline) {
                        URI uri = buildUri(baseUrl, scenario, localSeed++);
                        HttpRequest req = HttpRequest.newBuilder()
                            .uri(uri)
                            .timeout(Duration.ofSeconds(30))
                            .GET()
                            .build();
                        long t0 = System.nanoTime();
                        try {
                            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
                            long latency = System.nanoTime() - t0;
                            if (sink != null) sink.record(latency);
                            if (resp.statusCode() == 200) {
                                if (success != null) success.incrementAndGet();
                            } else {
                                if (failure != null) failure.incrementAndGet();
                            }
                        } catch (Exception e) {
                            long latency = System.nanoTime() - t0;
                            if (sink != null) sink.record(latency);
                            if (failure != null) failure.incrementAndGet();
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
    }

    private static URI buildUri(String baseUrl, String scenario, long seed) {
        if (scenario.equals("hit"))
            return URI.create(baseUrl + "/mojang/user/CraftedFury/cached");
        String username = "bench" + Long.toHexString(Math.abs(seed));
        return URI.create(baseUrl + "/mojang/user/" + username);
    }

    private static String arg(String[] args, String key, String fallback) {
        for (int i = 0; i + 1 < args.length; i++)
            if (args[i].equals(key)) return args[i + 1];
        return fallback;
    }

    private static String fmt(long nanos) {
        if (nanos >= 1_000_000) return String.format("%.2f ms", nanos / 1_000_000.0);
        if (nanos >= 1_000) return String.format("%.2f us", nanos / 1_000.0);
        return nanos + " ns";
    }

}
