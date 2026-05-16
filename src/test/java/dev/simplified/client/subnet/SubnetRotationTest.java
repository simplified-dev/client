package dev.simplified.client.subnet;

import dev.simplified.client.ratelimit.RateLimit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.temporal.ChronoUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubnetRotationTest {

    @Test
    @DisplayName("Builder defaults match documented values")
    void builderDefaults() {
        SubnetRotation r = SubnetRotation.builder()
            .sourcePrefix("2602:fa02:0a00::/40")
            .bucketPrefixLength(56)
            .budget(RateLimit.builder().limit(200).window(1, ChronoUnit.MINUTES).build())
            .build();

        assertThat(r.strategy(), is(SubnetSelectionStrategy.RANDOM_REJECT_SAMPLE));
        assertThat(r.softCapFraction(), is(0.9));
        assertThat(r.maxRejectSamples(), is(16));
    }

    @Test
    @DisplayName("Builder accepts CIDR string for source prefix")
    void builderParsesCidr() {
        SubnetRotation r = SubnetRotation.builder()
            .sourcePrefix("2001:470:1::/48")
            .bucketPrefixLength(56)
            .build();
        assertThat(r.sourcePrefix(), is(IpPrefix.parse("2001:470:1::/48")));
    }

    @Test
    @DisplayName("Rejects bucketPrefixLength below 0")
    void rejectsBucketLengthBelowZero() {
        assertThrows(IllegalArgumentException.class, () ->
            SubnetRotation.builder()
                .sourcePrefix("2001:db8::/48")
                .bucketPrefixLength(-1)
                .build());
    }

    @Test
    @DisplayName("Rejects bucketPrefixLength above 128")
    void rejectsBucketLengthAbove128() {
        assertThrows(IllegalArgumentException.class, () ->
            SubnetRotation.builder()
                .sourcePrefix("2001:db8::/48")
                .bucketPrefixLength(129)
                .build());
    }

    @Test
    @DisplayName("Allows bucket prefix length larger than source (fan-out)")
    void allowsFanOutBucketLength() {
        SubnetRotation r = SubnetRotation.builder()
            .sourcePrefix("2001:db8::/48")
            .bucketPrefixLength(56)
            .build();
        assertThat(r.bucketCount().longValueExact(), is(256L));
    }

    @Test
    @DisplayName("Allows bucket prefix length equal to source (single)")
    void allowsEqualBucketLength() {
        SubnetRotation r = SubnetRotation.builder()
            .sourcePrefix("2001:db8::/56")
            .bucketPrefixLength(56)
            .build();
        assertThat(r.bucketCount().longValueExact(), is(1L));
    }

    @Test
    @DisplayName("Allows bucket prefix length smaller than source (pass-through)")
    void allowsPassThroughBucketLength() {
        SubnetRotation r = SubnetRotation.builder()
            .sourcePrefix("2001:db8::/60")
            .bucketPrefixLength(56)
            .build();
        // /60 is contained in a single /56 - no useful partition
        assertThat(r.bucketCount().intValue(), is(0));
    }

    @Test
    @DisplayName("Rejects softCapFraction outside (0, 1]")
    void rejectsBadSoftCap() {
        assertThrows(IllegalArgumentException.class, () ->
            SubnetRotation.builder()
                .sourcePrefix("2001:db8::/48")
                .bucketPrefixLength(56)
                .softCapFraction(0.0)
                .build());
        assertThrows(IllegalArgumentException.class, () ->
            SubnetRotation.builder()
                .sourcePrefix("2001:db8::/48")
                .bucketPrefixLength(56)
                .softCapFraction(-0.1)
                .build());
        assertThrows(IllegalArgumentException.class, () ->
            SubnetRotation.builder()
                .sourcePrefix("2001:db8::/48")
                .bucketPrefixLength(56)
                .softCapFraction(1.1)
                .build());
    }

    @Test
    @DisplayName("Accepts softCapFraction at the boundary 1.0")
    void acceptsSoftCapAtBoundary() {
        SubnetRotation r = SubnetRotation.builder()
            .sourcePrefix("2001:db8::/48")
            .bucketPrefixLength(56)
            .softCapFraction(1.0)
            .build();
        assertThat(r.softCapFraction(), is(1.0));
    }

    @Test
    @DisplayName("Rejects maxRejectSamples below 1")
    void rejectsZeroMaxSamples() {
        assertThrows(IllegalArgumentException.class, () ->
            SubnetRotation.builder()
                .sourcePrefix("2001:db8::/48")
                .bucketPrefixLength(56)
                .maxRejectSamples(0)
                .build());
    }

    @Test
    @DisplayName("build() fails fast when required fields are unset")
    void rejectsUnsetRequiredFields() {
        assertThrows(IllegalStateException.class, () -> SubnetRotation.builder().build());
        assertThrows(IllegalStateException.class, () ->
            SubnetRotation.builder().sourcePrefix("2001:db8::/48").build());
    }

    @Test
    @DisplayName("softCapThreshold computes floor(limit * fraction)")
    void softCapThresholdValue() {
        SubnetRotation r = SubnetRotation.builder()
            .sourcePrefix("2001:db8::/48")
            .bucketPrefixLength(56)
            .budget(RateLimit.builder().limit(200).window(1, ChronoUnit.MINUTES).build())
            .softCapFraction(0.9)
            .build();
        assertThat(r.softCapThreshold(), is(180L));
    }

    @Test
    @DisplayName("softCapThreshold returns MAX_VALUE for unlimited budget")
    void softCapThresholdUnlimited() {
        SubnetRotation r = SubnetRotation.builder()
            .sourcePrefix("2001:db8::/48")
            .bucketPrefixLength(56)
            .budget(RateLimit.UNLIMITED)
            .build();
        assertThat(r.softCapThreshold(), is(Long.MAX_VALUE));
    }

}
