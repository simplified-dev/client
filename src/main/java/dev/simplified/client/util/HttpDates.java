package dev.simplified.client.util;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.client.cache.CacheControl;
import dev.simplified.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Non-instantiable utility class for parsing HTTP date header values into {@link Instant}.
 * <p>
 * Per <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-7.1.1.1">RFC 7231
 * Section 7.1.1.1</a>, HTTP dates appear in three historical formats that a compliant
 * parser must all accept:
 * <ul>
 *   <li><b>IMF-fixdate</b> - the preferred format, fixed-length RFC 5322 / RFC 1123 style
 *       (e.g. {@code Sun, 06 Nov 1994 08:49:37 GMT})</li>
 *   <li><b>RFC 850</b> - an obsolete but still-encountered format with a spelled-out day
 *       name and a two-digit year (e.g. {@code Sunday, 06-Nov-94 08:49:37 GMT})</li>
 *   <li><b>asctime</b> - the ANSI C {@code asctime()} output format
 *       (e.g. {@code Sun Nov  6 08:49:37 1994})</li>
 * </ul>
 * <p>
 * Formats are tried in the preferred order on each invocation. All parse methods return
 * {@link Optional#empty()} when the value is missing, blank, or not parseable in any
 * accepted format, making them safe to use without exception handling.
 * <p>
 * Each format is held as an immutable, thread-safe {@link DateTimeFormatter}; parses run
 * lock-free. This is the canonical HTTP-date parser for the client library and is used by
 * {@link RetryAfterParser} for the {@code Retry-After} HTTP-date branch and by
 * {@link CacheControl} / {@code Response.Cached} for {@code Date}, {@code Expires}, and
 * {@code Last-Modified} header resolution.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-7.1.1.1">RFC 7231 - Date/Time Formats</a>
 * @see RetryAfterParser
 */
@UtilityClass
public final class HttpDates {

    /**
     * Fixed-length RFC 5322 / RFC 1123 style (IMF-fixdate) with the leading day-of-week
     * prefix stripped before parsing. Matches the lenient day-of-week handling of the
     * legacy {@link SimpleDateFormat} - servers sometimes emit an inconsistent
     * day name for the encoded date, and we accept the date the way browsers do.
     */
    private static final @NotNull DateTimeFormatter IMF_FIXDATE = DateTimeFormatter
        .ofPattern("dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .withZone(ZoneOffset.UTC);

    /**
     * Obsolete RFC 850 style with the spelled-out day-name prefix stripped. The two-digit
     * year is pivoted at 1970 so {@code 70-99} resolves to {@code 1970-1999} and
     * {@code 00-69} to {@code 2000-2069}, matching the sliding-window behaviour of the
     * legacy {@link SimpleDateFormat} for HTTP-era dates.
     */
    private static final @NotNull DateTimeFormatter RFC_850 = new DateTimeFormatterBuilder()
        .appendPattern("dd-MMM-")
        .appendValueReduced(ChronoField.YEAR, 2, 2, 1970)
        .appendPattern(" HH:mm:ss 'GMT'")
        .toFormatter(Locale.US)
        .withZone(ZoneOffset.UTC);

    /**
     * ANSI C {@code asctime()} output format with the leading day-of-week prefix stripped.
     * The day-of-month field is space-padded for single-digit days
     * ({@code "Nov  6 08:49:37 1994"}), so the input is normalised by collapsing the
     * doubled space before parsing with a single-space pattern.
     */
    private static final @NotNull DateTimeFormatter ASCTIME = DateTimeFormatter
        .ofPattern("MMM d HH:mm:ss yyyy", Locale.US)
        .withZone(ZoneOffset.UTC);

    /**
     * Parses an HTTP date header value from a collection of header values.
     * <p>
     * Extracts the first element of the collection and delegates to {@link #parse(String)}.
     * Returns {@link Optional#empty()} if the collection is {@code null}, empty, or contains
     * only a blank first element.
     *
     * @param headerValues the collection of header values (typically containing a single entry),
     *                     or {@code null} if the header was not present
     * @return the parsed {@link Instant} if the first element is a valid HTTP date,
     *         otherwise {@link Optional#empty()}
     */
    public static @NotNull Optional<Instant> parse(@Nullable Collection<String> headerValues) {
        if (headerValues == null || headerValues.isEmpty())
            return Optional.empty();

        return parse(headerValues.iterator().next());
    }

    /**
     * Extracts and parses a named HTTP date header from a full response headers map.
     * <p>
     * Performs a case-insensitive lookup for {@code headerName} in the provided map and
     * parses the first value found. Returns {@link Optional#empty()} if the header is
     * absent or the value is unparseable. The wildcard element type accepts both
     * Feign-style {@code Map<String, Collection<String>>} and project-style
     * {@code ConcurrentMap<String, ConcurrentList<String>>}.
     *
     * @param headers the full map of response headers, keyed by header name
     * @param headerName the header name to look up (case-insensitive)
     * @return the parsed {@link Instant} if a valid HTTP date was found under the given name,
     *         otherwise {@link Optional#empty()}
     */
    public static @NotNull Optional<Instant> parseFromHeaders(@NotNull Map<String, ? extends Collection<String>> headers, @NotNull String headerName) {
        for (Map.Entry<String, ? extends Collection<String>> entry : headers.entrySet()) {
            if (headerName.equalsIgnoreCase(entry.getKey()))
                return parse(entry.getValue());
        }

        return Optional.empty();
    }

    /**
     * Parses a raw HTTP date header value into an {@link Instant}.
     * <p>
     * Tries each accepted format in the preferred order (IMF-fixdate, RFC 850, asctime)
     * and returns the first successful parse. Returns {@link Optional#empty()} if the
     * value is {@code null}, blank, or does not match any accepted format.
     *
     * @param raw the raw header value, may be {@code null}
     * @return the parsed {@link Instant}, or {@link Optional#empty()} if the value is
     *         absent or unparseable
     */
    public static @NotNull Optional<Instant> parse(@Nullable String raw) {
        if (StringUtil.isBlank(raw))
            return Optional.empty();

        assert raw != null;
        String trimmed = raw.trim();

        int afterComma = stripDayPrefix(trimmed, ',');
        if (afterComma > 0) {
            String body = trimmed.substring(afterComma).trim();
            Optional<Instant> imf = tryParse(IMF_FIXDATE, body);
            if (imf.isPresent()) return imf;

            Optional<Instant> rfc850 = tryParse(RFC_850, body);
            if (rfc850.isPresent()) return rfc850;
        }

        int afterSpace = stripDayPrefix(trimmed, ' ');
        if (afterSpace > 0)
            return tryParse(ASCTIME, collapseDoubleSpace(trimmed.substring(afterSpace).trim()));

        return Optional.empty();
    }

    /**
     * Attempts to parse the given value using the supplied {@link DateTimeFormatter}.
     * <p>
     * The formatter is immutable and thread-safe; no synchronisation is required.
     *
     * @param formatter the formatter to attempt
     * @param value the raw value to parse
     * @return the parsed {@link Instant}, or {@link Optional#empty()} if the value does
     *         not conform to {@code formatter}
     */
    private static @NotNull Optional<Instant> tryParse(@NotNull DateTimeFormatter formatter, @NotNull String value) {
        try {
            return Optional.of(LocalDateTime.parse(value, formatter).toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * Collapses the doubled-space padding the asctime spec uses for single-digit days
     * ({@code "Nov  6"}) into a single space so a one-space pattern can parse both
     * one- and two-digit day forms. Returns the input unchanged when no doubled space is
     * present, so the common two-digit-day branch pays no allocation.
     *
     * @param value the raw asctime candidate
     * @return the input with the first occurrence of {@code "  "} collapsed to {@code " "}
     */
    private static @NotNull String collapseDoubleSpace(@NotNull String value) {
        int idx = value.indexOf("  ");
        if (idx < 0) return value;
        return value.substring(0, idx) + value.substring(idx + 1);
    }

    /**
     * Returns the index immediately after the first occurrence of {@code delimiter} in
     * {@code value}, or {@code -1} if the delimiter is absent. Used to strip the leading
     * day-of-week prefix (whose contents we deliberately ignore for parser leniency).
     *
     * @param value the raw input
     * @param delimiter the character separating the prefix from the date body
     *                  ({@code ','} for IMF-fixdate / RFC 850, {@code ' '} for asctime)
     * @return the index just after the delimiter, or {@code -1} if not found
     */
    private static int stripDayPrefix(@NotNull String value, char delimiter) {
        int idx = value.indexOf(delimiter);
        return idx < 0 ? -1 : idx + 1;
    }

}
