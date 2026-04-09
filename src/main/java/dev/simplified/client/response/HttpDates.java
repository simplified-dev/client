package dev.simplified.client.response;

import dev.simplified.util.StringUtil;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;

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
 * {@link SimpleDateFormat} is not thread-safe; each format instance is guarded by its
 * own intrinsic lock. This class is the canonical HTTP-date parser for the client
 * library and is used by {@link RetryAfterParser} for the {@code Retry-After} HTTP-date
 * branch and by {@link CacheControl} / {@code Response.Cached} for {@code Date},
 * {@code Expires}, and {@code Last-Modified} header resolution.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-7.1.1.1">RFC 7231 - Date/Time Formats</a>
 * @see RetryAfterParser
 */
@UtilityClass
public final class HttpDates {

    /** Fixed-length RFC 5322 / RFC 1123 style (IMF-fixdate), the preferred HTTP date format. */
    private static final @NotNull DateFormat IMF_FIXDATE = gmt(new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US));

    /** Obsolete RFC 850 style with a spelled-out day name and a two-digit year. */
    private static final @NotNull DateFormat RFC_850 = gmt(new SimpleDateFormat("EEEE, dd-MMM-yy HH:mm:ss 'GMT'", Locale.US));

    /** ANSI C {@code asctime()} output format, with whitespace padding on single-digit days. */
    private static final @NotNull DateFormat ASCTIME = gmt(new SimpleDateFormat("EEE MMM d HH:mm:ss yyyy", Locale.US));

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

        Optional<Instant> imf = tryParse(IMF_FIXDATE, trimmed);

        if (imf.isPresent())
            return imf;

        Optional<Instant> rfc850 = tryParse(RFC_850, trimmed);

        if (rfc850.isPresent())
            return rfc850;

        return tryParse(ASCTIME, trimmed);
    }

    /**
     * Attempts to parse the given value using the supplied {@link DateFormat}.
     * <p>
     * Access to the shared {@code format} instance is synchronized on its intrinsic lock
     * because {@link SimpleDateFormat} is not thread-safe.
     *
     * @param format the date format to attempt
     * @param value the raw value to parse
     * @return the parsed {@link Instant}, or {@link Optional#empty()} if the value does
     *         not conform to {@code format}
     */
    private static @NotNull Optional<Instant> tryParse(@NotNull DateFormat format, @NotNull String value) {
        synchronized (format) {
            try {
                return Optional.of(format.parse(value).toInstant());
            } catch (ParseException e) {
                return Optional.empty();
            }
        }
    }

    /**
     * Configures the given date format to interpret parsed timestamps in GMT.
     *
     * @param format the date format to configure
     * @return the same date format, with its time zone set to GMT
     */
    private static @NotNull DateFormat gmt(@NotNull DateFormat format) {
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format;
    }

}
