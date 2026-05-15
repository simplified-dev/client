package dev.simplified.client.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

class HttpDatesTest {

    private static final Instant EXPECTED = Instant.parse("1994-11-06T08:49:37Z");

    @Test
    @DisplayName("Parses RFC 7231 IMF-fixdate format (preferred)")
    void parsesImfFixdate() {
        Optional<Instant> parsed = HttpDates.parse("Sun, 06 Nov 1994 08:49:37 GMT");
        assertThat(parsed.orElse(null), is(equalTo(EXPECTED)));
    }

    @Test
    @DisplayName("Parses obsolete RFC 850 format")
    void parsesRfc850() {
        Optional<Instant> parsed = HttpDates.parse("Sunday, 06-Nov-94 08:49:37 GMT");
        assertThat(parsed.orElse(null), is(equalTo(EXPECTED)));
    }

    @Test
    @DisplayName("Parses asctime format with double-space padded single-digit day")
    void parsesAsctimeSingleDigitDay() {
        Optional<Instant> parsed = HttpDates.parse("Sun Nov  6 08:49:37 1994");
        assertThat(parsed.orElse(null), is(equalTo(EXPECTED)));
    }

    @Test
    @DisplayName("Parses asctime format with two-digit day")
    void parsesAsctimeTwoDigitDay() {
        Optional<Instant> parsed = HttpDates.parse("Tue Nov 12 08:49:37 1994");
        assertThat(parsed.orElse(null), is(equalTo(Instant.parse("1994-11-12T08:49:37Z"))));
    }

    @Test
    @DisplayName("Returns empty for malformed input")
    void rejectsMalformed() {
        assertThat(HttpDates.parse("not a date").isPresent(), is(false));
    }

    @Test
    @DisplayName("Returns empty for null and blank input")
    void rejectsNullAndBlank() {
        assertThat(HttpDates.parse((String) null).isPresent(), is(false));
        assertThat(HttpDates.parse("").isPresent(), is(false));
        assertThat(HttpDates.parse("   ").isPresent(), is(false));
    }

    @Test
    @DisplayName("Trims surrounding whitespace before parsing")
    void trimsWhitespace() {
        Optional<Instant> parsed = HttpDates.parse("  Sun, 06 Nov 1994 08:49:37 GMT  ");
        assertThat(parsed.orElse(null), is(equalTo(EXPECTED)));
    }

}
