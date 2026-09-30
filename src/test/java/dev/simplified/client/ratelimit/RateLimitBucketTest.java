package dev.simplified.client.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class RateLimitBucketTest {

    /** A clock reading in the same era as the epoch resets GitHub sends. */
    private static final long NOW = 1_790_000_000_000L;

    private static final long NOW_SECOND = NOW / 1000L;

    private static void exhaust(RateLimitBucket bucket, long count, long now) {
        for (long i = 0; i < count; i++) {
            assertThat("request " + i + " is refused early", bucket.isRateLimited(now), is(false));
            bucket.trackRequest(now);
        }
    }

    @Test
    @DisplayName("A server policy with an epoch reset ends the bucket's window at that instant")
    void epochResetEndsWindowAtThatInstant() {
        long resetSecond = NOW_SECOND + 3600L;
        RateLimitBucket bucket = new RateLimitBucket(RateLimit.UNLIMITED, NOW);

        bucket.updateRateLimit(RateLimit.fromHeaders(60, resetSecond, NOW), NOW);

        assertThat(bucket.getWindow().end(), is(resetSecond * 1000L));
    }

    @Test
    @DisplayName("A server policy with a delta reset ends the window that many seconds after receipt")
    void deltaResetEndsWindowAfterReceipt() {
        RateLimitBucket bucket = new RateLimitBucket(RateLimit.UNLIMITED, NOW);
        bucket.updateRateLimit(RateLimit.fromHeaders(10, 30, NOW), NOW);

        exhaust(bucket, 10, NOW);

        assertThat(bucket.getWindow().end(), is(NOW + 30_000L));
        assertThat(bucket.isRateLimited(NOW + 29_999L), is(true));
        assertThat(bucket.isRateLimited(NOW + 30_000L), is(false));
    }

    @Test
    @DisplayName("A bucket exhausted under a server window becomes usable once the reset instant passes")
    void exhaustedBucketUsableAtResetInstant() {
        long resetMillis = (NOW_SECOND + 900L) * 1000L;
        RateLimitBucket bucket = new RateLimitBucket(RateLimit.UNLIMITED, NOW);
        bucket.updateRateLimit(RateLimit.fromHeaders(5, resetMillis / 1000L, NOW), NOW);

        exhaust(bucket, 5, NOW);

        assertThat(bucket.isRateLimited(NOW), is(true));
        assertThat(bucket.isRateLimited(resetMillis - 1L), is(true));
        assertThat(bucket.isRateLimited(resetMillis), is(false));
        assertThat(bucket.getCount(resetMillis), is(0L));

        bucket.trackRequest(resetMillis);
        assertThat(bucket.getCount(resetMillis), is(1L));
    }

    @Test
    @DisplayName("A bucket exhausted under an epoch-millisecond reset becomes usable at that instant")
    void exhaustedBucketUsableAtMillisecondReset() {
        long resetMillis = NOW + 90_500L;
        RateLimitBucket bucket = new RateLimitBucket(RateLimit.UNLIMITED, NOW);
        bucket.updateRateLimit(RateLimit.fromHeaders(5, resetMillis, NOW), NOW);

        exhaust(bucket, 5, NOW);

        assertThat(bucket.isRateLimited(resetMillis - 1L), is(true));
        assertThat(bucket.isRateLimited(resetMillis), is(false));
    }

    @Test
    @DisplayName("Remaining reads the full limit once the window has ended, before any request rotates it")
    void remainingRecoversWithoutRotation() {
        long resetMillis = (NOW_SECOND + 60L) * 1000L;
        RateLimitBucket bucket = new RateLimitBucket(RateLimit.UNLIMITED, NOW);
        bucket.updateRateLimit(RateLimit.fromHeaders(3, resetMillis / 1000L, NOW), NOW);

        exhaust(bucket, 3, NOW);

        assertThat(bucket.getRemaining(resetMillis - 1L), is(0L));
        assertThat(bucket.getRemaining(resetMillis), is(3L));
    }

    @Test
    @DisplayName("A server policy arriving after the window ended clears the count")
    void updateAfterWindowEndedClearsCount() {
        long firstReset = (NOW_SECOND + 60L) * 1000L;
        long secondReset = firstReset + 3_600_000L;
        RateLimitBucket bucket = new RateLimitBucket(RateLimit.UNLIMITED, NOW);
        bucket.updateRateLimit(RateLimit.fromHeaders(5, firstReset / 1000L, NOW), NOW);
        exhaust(bucket, 5, NOW);

        long later = firstReset + 10_000L;
        bucket.updateRateLimit(RateLimit.fromHeaders(5, secondReset / 1000L, later), later);

        assertThat(bucket.getWindow().count(), is(0L));
        assertThat(bucket.getWindow().end(), is(secondReset));
        assertThat(bucket.isRateLimited(later), is(false));
    }

    @Test
    @DisplayName("Syncing remaining sets the count to the limit less remaining, clamped to the limit")
    void syncRemainingSetsCount() {
        RateLimitBucket bucket = new RateLimitBucket(RateLimit.UNLIMITED, NOW);
        bucket.updateRateLimit(RateLimit.fromHeaders(60, NOW_SECOND + 3600L, NOW), NOW);

        bucket.syncRemaining(45);
        assertThat(bucket.getCount(NOW), is(15L));

        bucket.syncRemaining(100);
        assertThat(bucket.getCount(NOW), is(0L));

        bucket.syncRemaining(-5);
        assertThat(bucket.getCount(NOW), is(60L));
        assertThat(bucket.isRateLimited(NOW), is(true));
    }

    @Test
    @DisplayName("A server update for an earlier request than the last one applied is ignored whole")
    void earlierServerUpdateIsIgnored() {
        long resetSecond = NOW_SECOND + 3600L;
        RateLimitBucket bucket = new RateLimitBucket(RateLimit.UNLIMITED, NOW);

        assertThat(bucket.updateFromServer(RateLimit.fromHeaders(60, resetSecond, NOW), OptionalLong.of(57), NOW, 3), is(true));
        assertThat(bucket.updateFromServer(RateLimit.fromHeaders(60, resetSecond - 3600L, NOW), OptionalLong.of(58), NOW, 2), is(false));

        assertThat(bucket.getCount(NOW), is(3L));
        assertThat(bucket.getWindow().end(), is(resetSecond * 1000L));
    }

    @Test
    @DisplayName("Server updates racing to apply leave the latest request's count, whatever order they land in")
    void racingServerUpdatesKeepTheLatest() throws Exception {
        int responses = 32;
        RateLimit policy = RateLimit.fromHeaders(1000, NOW_SECOND + 3600L, NOW);
        ExecutorService pool = Executors.newFixedThreadPool(8);

        try {
            for (int round = 0; round < 100; round++) {
                RateLimitBucket bucket = new RateLimitBucket(RateLimit.UNLIMITED, NOW);
                List<Integer> order = IntStream.rangeClosed(1, responses).boxed().collect(Collectors.toList());
                Collections.shuffle(order);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> landed = new ArrayList<>();

                for (int sequence : order) {
                    landed.add(pool.submit(() -> {
                        start.await();
                        return bucket.updateFromServer(policy, OptionalLong.of(1000L - sequence), NOW, sequence);
                    }));
                }

                start.countDown();

                for (Future<?> future : landed)
                    future.get();

                assertThat("round " + round, bucket.getCount(NOW), is((long) responses));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Requests acquired together are admitted no further than the limit, and each admitted one is counted")
    void concurrentAcquiresAdmitOnlyTheLimit() throws Exception {
        int threads = 32;
        RateLimit policy = RateLimit.builder().limit(3).window(60, ChronoUnit.SECONDS).build();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            for (int round = 0; round < 200; round++) {
                RateLimitBucket bucket = new RateLimitBucket(policy, NOW);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> acquired = new ArrayList<>();

                for (int i = 0; i < threads; i++) {
                    acquired.add(pool.submit(() -> {
                        start.await();
                        return bucket.tryAcquire(NOW);
                    }));
                }

                start.countDown();
                long admitted = 0;

                for (Future<Boolean> future : acquired) {
                    if (future.get())
                        admitted++;
                }

                assertThat("round " + round, admitted, is(3L));
                assertThat("round " + round, bucket.getCount(NOW), is(3L));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("A refused acquire counts nothing, and an unlimited bucket admits every request without counting it")
    void refusedAcquireCountsNothing() {
        RateLimitBucket bucket = new RateLimitBucket(RateLimit.builder().limit(1).window(60, ChronoUnit.SECONDS).build(), NOW);
        RateLimitBucket unlimited = new RateLimitBucket(RateLimit.UNLIMITED, NOW);

        assertThat(bucket.tryAcquire(NOW), is(true));
        assertThat(bucket.tryAcquire(NOW), is(false));
        assertThat(bucket.getCount(NOW), is(1L));
        assertThat(bucket.tryAcquire(NOW + 60_000L), is(true));
        assertThat(unlimited.tryAcquire(NOW), is(true));
        assertThat(unlimited.getCount(NOW), is(0L));
    }

    @Test
    @DisplayName("Requests racing across a window boundary are admitted no further than the limit in the window opened there")
    void acquiresRacingABoundaryAdmitOnlyTheLimit() throws Exception {
        int threads = 32;
        int attempts = 4;
        long limit = 5L;
        long boundary = NOW + 60_000L;
        RateLimit policy = RateLimit.builder().limit(limit).window(60, ChronoUnit.SECONDS).build();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        try {
            for (int round = 0; round < 300; round++) {
                // Nothing is counted in the window ending at the boundary, so a request counted in
                // it while a racing rotation opens the next window would be admitted and erased.
                RateLimitBucket bucket = new RateLimitBucket(policy, NOW);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Integer>> acquired = new ArrayList<>();

                for (int i = 0; i < threads; i++) {
                    acquired.add(pool.submit(() -> {
                        start.await();
                        int admitted = 0;

                        for (int attempt = 0; attempt < attempts; attempt++) {
                            if (bucket.tryAcquire(boundary))
                                admitted++;
                        }

                        return admitted;
                    }));
                }

                start.countDown();
                long admitted = 0;

                for (Future<Integer> future : acquired)
                    admitted += future.get();

                assertThat("round " + round, admitted, is(limit));
                assertThat("round " + round, bucket.getWindow(), is(new RateLimitBucket.Window(boundary, boundary + 60_000L, limit)));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("A client-configured window still runs its duration from when it opened")
    void clientConfiguredWindowRunsItsDuration() {
        RateLimitBucket bucket = new RateLimitBucket(RateLimit.builder().limit(2).window(10, ChronoUnit.SECONDS).build(), NOW);

        exhaust(bucket, 2, NOW);

        assertThat(bucket.getWindow().end(), is(NOW + 10_000L));
        assertThat(bucket.isRateLimited(NOW + 9_999L), is(true));
        assertThat(bucket.isRateLimited(NOW + 10_000L), is(false));
    }

}
