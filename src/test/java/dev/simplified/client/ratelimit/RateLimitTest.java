package dev.simplified.client.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

class RateLimitTest {

    /** A clock reading in the same era as the epoch resets GitHub sends. */
    private static final long NOW = 1_790_000_000_000L;

    private static final long NOW_SECOND = NOW / 1000L;

    private static Map<String, Collection<String>> headers(String... pairs) {
        Map<String, Collection<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        for (int i = 0; i < pairs.length; i += 2)
            headers.put(pairs[i], List.of(pairs[i + 1]));

        return headers;
    }

    @Test
    @DisplayName("An epoch X-RateLimit-Reset yields a policy that resets at that instant")
    void epochResetEndsAtThatInstant() {
        long resetSecond = NOW_SECOND + 3600L;
        RateLimit limit = RateLimit.fromHeaders(
            headers("x-ratelimit-limit", "60", "x-ratelimit-reset", Long.toString(resetSecond)),
            NOW
        ).orElseThrow();

        assertThat(limit.getLimit(), is(60L));
        assertThat(limit.getResetEpochMillis(), is(resetSecond * 1000L));
        assertThat(limit.getResetSeconds(), is(3600L));
        assertThat(limit.getWindowDurationMillis(), is(3_600_000L));
    }

    @Test
    @DisplayName("An epoch reset converts to seconds until then rounded up")
    void epochResetRoundsSecondsUp() {
        long resetSecond = NOW_SECOND + 60L;
        RateLimit limit = RateLimit.fromHeaders(60, resetSecond, NOW + 400L);

        assertThat(limit.getResetEpochMillis(), is(resetSecond * 1000L));
        assertThat(limit.getResetSeconds(), is(60L));
    }

    @Test
    @DisplayName("An epoch reset that has already passed converts to zero seconds, never negative")
    void pastEpochResetIsNeverNegative() {
        long resetSecond = NOW_SECOND - 10L;
        RateLimit limit = RateLimit.fromHeaders(60, resetSecond, NOW);

        assertThat(limit.getResetSeconds(), is(0L));
        assertThat(limit.getResetEpochMillis(), is(resetSecond * 1000L));
    }

    @Test
    @DisplayName("A delta RateLimit-Reset yields a policy that resets that many seconds after receipt")
    void deltaResetAnchorsToReceipt() {
        RateLimit limit = RateLimit.fromHeaders(
            headers("RateLimit-Limit", "120", "RateLimit-Reset", "42"),
            NOW
        ).orElseThrow();

        assertThat(limit.getLimit(), is(120L));
        assertThat(limit.getResetSeconds(), is(42L));
        assertThat(limit.getResetEpochMillis(), is(NOW + 42_000L));
        assertThat(limit.getWindowDurationMillis(), is(42_000L));
    }

    @Test
    @DisplayName("fromHeaders(limit, reset) reads a GitHub epoch reset as an instant, not a window length")
    void twoArgumentOverloadReadsEpoch() {
        long resetSecond = System.currentTimeMillis() / 1000L + 3600L;
        RateLimit limit = RateLimit.fromHeaders(60, resetSecond);

        assertThat(limit.getResetEpochMillis(), is(resetSecond * 1000L));
        assertThat(limit.getResetSeconds(), is(allOf(greaterThanOrEqualTo(3599L), lessThanOrEqualTo(3601L))));
    }

    @Test
    @DisplayName("A client-configured policy carries no reset instant")
    void clientConfiguredHasNoResetInstant() {
        RateLimit limit = RateLimit.builder().limit(10).window(1, ChronoUnit.MINUTES).build();

        assertThat(limit.getResetEpochMillis(), is(0L));
        assertThat(limit.getWindowDurationMillis(), is(60_000L));
    }

    @Test
    @DisplayName("Remaining is read from RateLimit-Remaining before X-RateLimit-Remaining")
    void remainingPrefersRfcHeader() {
        OptionalLong remaining = RateLimit.remainingFromHeaders(
            headers("RateLimit-Remaining", "7", "X-RateLimit-Remaining", "9")
        );

        assertThat(remaining, is(OptionalLong.of(7L)));
    }

    @Test
    @DisplayName("Remaining falls back to X-RateLimit-Remaining and is empty when absent")
    void remainingFallsBackToXHeader() {
        assertThat(RateLimit.remainingFromHeaders(headers("x-ratelimit-remaining", "59")), is(OptionalLong.of(59L)));
        assertThat(RateLimit.remainingFromHeaders(headers("x-ratelimit-limit", "60")), is(OptionalLong.empty()));
    }

}
