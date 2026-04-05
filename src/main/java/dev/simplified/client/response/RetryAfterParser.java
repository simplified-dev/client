package dev.simplified.client.response;

import dev.simplified.util.StringUtil;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Non-instantiable utility class for parsing the HTTP {@code Retry-After} response header
 * into an epoch-millisecond timestamp.
 * <p>
 * The {@code Retry-After} header, defined in
 * <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-7.1.3">RFC 7231 Section 7.1.3</a>,
 * may appear in two formats:
 * <ul>
 *     <li><b>Delay-seconds</b> - a non-negative integer (optionally with trailing {@code .0}) indicating
 *         the number of seconds the client should wait before retrying
 *         (e.g. {@code Retry-After: 120})</li>
 *     <li><b>HTTP-date</b> - an RFC 822 / RFC 1123 formatted date indicating the absolute time
 *         at which the client may retry
 *         (e.g. {@code Retry-After: Wed, 21 Oct 2026 07:28:00 GMT})</li>
 * </ul>
 * <p>
 * All parse methods return {@link OptionalLong#empty()} when the header is missing, blank, or
 * not parseable in either format, making them safe to use without exception handling.
 * The delay-seconds format is attempted first as it is the most common in practice.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-7.1.3">RFC 7231 - Retry-After</a>
 */
@UtilityClass
public final class RetryAfterParser {

    /** The header names to search for when extracting retry-after values, covering both canonical and lowercase forms. */
    private static final @NotNull String[] RETRY_AFTER_HEADERS = { "Retry-After", "retry-after" };

    /** Pattern matching a non-negative integer, optionally followed by a decimal point and trailing zeros. */
    private static final @NotNull Pattern RETRY_AFTER_PATTERN = Pattern.compile("^[0-9]+\\.?0*$");

    /** Thread-unsafe RFC 822/1123 date formatter; all access must be synchronized. */
    private static final @NotNull DateFormat RFC822_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);

    /**
     * Parses a {@code Retry-After} value from a collection of header values into an
     * epoch-millisecond timestamp.
     * <p>
     * Extracts the first element of the collection and delegates to {@link #parse(String)}.
     * Returns {@link OptionalLong#empty()} if the collection is {@code null}, empty, or contains
     * only blank values.
     *
     * @param retryAfterValues the collection of header values (typically containing a single value),
     *                         or {@code null} if the header was not present
     * @return an {@link OptionalLong} containing the epoch-millisecond timestamp at which retrying
     *         is appropriate, or {@link OptionalLong#empty()} if the value is absent or unparseable
     */
    public static @NotNull OptionalLong parse(@Nullable Collection<String> retryAfterValues) {
        if (retryAfterValues == null || retryAfterValues.isEmpty())
            return OptionalLong.empty();

        String retryAfter = retryAfterValues.iterator().next();

        if (StringUtil.isBlank(retryAfter))
            return OptionalLong.empty();

        return parse(retryAfter.trim());
    }

    /**
     * Extracts and parses the {@code Retry-After} header from a complete response headers map.
     * <p>
     * Searches for both {@code "Retry-After"} and {@code "retry-after"} keys in the
     * provided map, returning the parsed result of the first non-empty match. Returns
     * {@link OptionalLong#empty()} if neither key is present or if the associated values
     * are unparseable.
     *
     * @param headers the full map of response headers, keyed by header name
     * @return an {@link OptionalLong} containing the epoch-millisecond timestamp at which retrying
     *         is appropriate, or {@link OptionalLong#empty()} if no valid {@code Retry-After} header is found
     */
    public static @NotNull OptionalLong parseFromHeaders(@NotNull Map<String, Collection<String>> headers) {
        for (String headerName : RETRY_AFTER_HEADERS) {
            Collection<String> values = headers.get(headerName);

            if (values != null && !values.isEmpty())
                return parse(values);
        }

        return OptionalLong.empty();
    }

    /**
     * Parses a raw {@code Retry-After} header value string into an epoch-millisecond timestamp.
     * <p>
     * First attempts to interpret the value as delay-seconds (a non-negative integer),
     * converting it to an absolute timestamp offset from the current system time. If that fails,
     * attempts to parse the value as an HTTP-date in RFC 822/1123 format.
     *
     * @param retryAfter the raw header value string to parse
     * @return an {@link OptionalLong} containing the resolved epoch-millisecond timestamp, or
     *         {@link OptionalLong#empty()} if the value cannot be parsed in either format
     */
    public static @NotNull OptionalLong parse(@NotNull String retryAfter) {
        // Try parsing as delay-seconds (most common)
        OptionalLong secondsResult = parseAsSeconds(retryAfter);

        if (secondsResult.isPresent())
            return secondsResult;

        // Try parsing as HTTP-date (RFC 822/1123 format)
        return parseAsHttpDate(retryAfter);
    }

    /**
     * Attempts to parse the given value as a delay-seconds integer and convert it to
     * an absolute epoch-millisecond timestamp by adding the parsed seconds to the
     * current system time.
     *
     * @param retryAfter the raw header value string to parse
     * @return an {@link OptionalLong} containing the computed future timestamp, or
     *         {@link OptionalLong#empty()} if the value does not match the delay-seconds pattern
     */
    private static @NotNull OptionalLong parseAsSeconds(@NotNull String retryAfter) {
        // Match integer or decimal seconds (e.g., "120" or "120.0")
        if (RETRY_AFTER_PATTERN.matcher(retryAfter).matches()) {
            String cleaned = retryAfter.replaceAll("\\.0*$", "");

            try {
                long seconds = Long.parseLong(cleaned);
                long deltaMillis = TimeUnit.SECONDS.toMillis(seconds);
                return OptionalLong.of(System.currentTimeMillis() + deltaMillis);
            } catch (NumberFormatException e) {
                return OptionalLong.empty();
            }
        }

        return OptionalLong.empty();
    }

    /**
     * Attempts to parse the given value as an HTTP-date in RFC 822/1123 format and
     * returns the result as an epoch-millisecond timestamp.
     * <p>
     * Access to the shared {@link DateFormat} instance is synchronized to ensure
     * thread safety, since {@link SimpleDateFormat} is not thread-safe.
     *
     * @param retryAfter the raw header value string to parse
     * @return an {@link OptionalLong} containing the parsed epoch-millisecond timestamp, or
     *         {@link OptionalLong#empty()} if the value does not conform to the expected format
     */
    private static @NotNull OptionalLong parseAsHttpDate(@NotNull String retryAfter) {
        synchronized (RFC822_FORMAT) {
            try {
                return OptionalLong.of(RFC822_FORMAT.parse(retryAfter).getTime());
            } catch (ParseException e) {
                return OptionalLong.empty();
            }
        }
    }

}
