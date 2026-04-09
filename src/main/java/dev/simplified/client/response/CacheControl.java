package dev.simplified.client.response;

import dev.simplified.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Immutable parsed view of an HTTP {@code Cache-Control} response header, honoring the
 * directives defined in
 * <a href="https://datatracker.ietf.org/doc/html/rfc7234#section-5.2">RFC 7234 Section 5.2</a>
 * and the cache-extension directives {@code stale-while-revalidate} and {@code stale-if-error}
 * from <a href="https://datatracker.ietf.org/doc/html/rfc5861">RFC 5861</a>.
 * <p>
 * Parsing is lenient: unknown directives are ignored, directive names are case-insensitive,
 * values may be quoted, and multi-valued {@code Cache-Control} headers are concatenated
 * before splitting. Invalid numeric arguments (e.g. {@code max-age=abc}) are dropped rather
 * than propagated as exceptions, matching the style of {@link RetryAfterParser}.
 * <p>
 * The sentinel {@link #EMPTY} represents a response that carries no {@code Cache-Control}
 * header at all. Use {@link #parse(Collection)} or
 * {@link #parseFromHeaders(Map)} from the appropriate call site.
 *
 * @param maxAge the {@code max-age} directive in seconds, if present
 * @param sMaxAge the {@code s-maxage} directive in seconds, if present
 * @param noStore {@code true} if {@code no-store} is set - the response must not be cached
 * @param noCache {@code true} if {@code no-cache} is set - a cached entry must be revalidated
 *                before reuse
 * @param mustRevalidate {@code true} if {@code must-revalidate} or {@code proxy-revalidate}
 *                       is set - a stale cached entry must not be served without successful
 *                       revalidation
 * @param isPrivate {@code true} if {@code private} is set - the response is intended only
 *                  for a private cache (this client is private per RFC 7234 §1.2, so this
 *                  restriction is satisfied)
 * @param isPublic {@code true} if {@code public} is set - explicitly allows caching even by
 *                 shared caches, including responses that would otherwise be uncacheable
 * @param immutable {@code true} if {@code immutable} is set - signals the origin will not
 *                  change the representation for its freshness lifetime
 * @param staleWhileRevalidate the {@code stale-while-revalidate} window in seconds, if present
 * @param staleIfError the {@code stale-if-error} window in seconds, if present
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7234#section-5.2">RFC 7234 - Cache-Control</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc5861">RFC 5861 - HTTP Cache-Control Extensions for Stale Content</a>
 * @see RetryAfterParser
 */
public record CacheControl(
    @NotNull OptionalLong maxAge,
    @NotNull OptionalLong sMaxAge,
    boolean noStore,
    boolean noCache,
    boolean mustRevalidate,
    boolean isPrivate,
    boolean isPublic,
    boolean immutable,
    @NotNull OptionalLong staleWhileRevalidate,
    @NotNull OptionalLong staleIfError
) {

    /** The canonical {@code Cache-Control} header name. */
    public static final @NotNull String HEADER_KEY = "Cache-Control";

    /** Sentinel representing an absent {@code Cache-Control} header - all directives default to their empty state. */
    public static final @NotNull CacheControl EMPTY = new CacheControl(
        OptionalLong.empty(),
        OptionalLong.empty(),
        false,
        false,
        false,
        false,
        false,
        false,
        OptionalLong.empty(),
        OptionalLong.empty()
    );

    /**
     * Parses a {@code Cache-Control} header into a {@code CacheControl} instance.
     * <p>
     * Multi-valued headers are concatenated with {@code ", "} before tokenizing so that
     * servers which split directives across multiple header lines are handled correctly.
     * Unknown directives are silently ignored. Numeric arguments that fail to parse are
     * treated as absent.
     *
     * @param headerValues the raw {@code Cache-Control} header values, may be {@code null}
     *                     or empty
     * @return the parsed directives, or {@link #EMPTY} if the input is absent or contains
     *         no recognized directives
     */
    public static @NotNull CacheControl parse(@Nullable Collection<String> headerValues) {
        if (headerValues == null || headerValues.isEmpty())
            return EMPTY;

        String joined = String.join(", ", headerValues);

        if (StringUtil.isBlank(joined))
            return EMPTY;

        OptionalLong maxAge = OptionalLong.empty();
        OptionalLong sMaxAge = OptionalLong.empty();
        boolean noStore = false;
        boolean noCache = false;
        boolean mustRevalidate = false;
        boolean isPrivate = false;
        boolean isPublic = false;
        boolean immutable = false;
        OptionalLong staleWhileRevalidate = OptionalLong.empty();
        OptionalLong staleIfError = OptionalLong.empty();

        for (String rawToken : joined.split(",")) {
            String token = rawToken.trim();

            if (token.isEmpty())
                continue;

            int eq = token.indexOf('=');
            String name = (eq < 0 ? token : token.substring(0, eq)).trim().toLowerCase(java.util.Locale.ROOT);
            String value = (eq < 0 ? "" : unquote(token.substring(eq + 1).trim()));

            switch (name) {
                case "max-age" -> maxAge = parseSeconds(value);
                case "s-maxage" -> sMaxAge = parseSeconds(value);
                case "no-store" -> noStore = true;
                case "no-cache" -> noCache = true;
                case "must-revalidate", "proxy-revalidate" -> mustRevalidate = true;
                case "private" -> isPrivate = true;
                case "public" -> isPublic = true;
                case "immutable" -> immutable = true;
                case "stale-while-revalidate" -> staleWhileRevalidate = parseSeconds(value);
                case "stale-if-error" -> staleIfError = parseSeconds(value);
                default -> { /* unknown directive - ignore per RFC 7234 §5.2 */ }
            }
        }

        return new CacheControl(
            maxAge,
            sMaxAge,
            noStore,
            noCache,
            mustRevalidate,
            isPrivate,
            isPublic,
            immutable,
            staleWhileRevalidate,
            staleIfError
        );
    }

    /**
     * Extracts and parses the {@code Cache-Control} header from a full response headers map.
     * <p>
     * Performs a case-insensitive lookup for {@link #HEADER_KEY} in the provided map and
     * delegates to {@link #parse(Collection)}. The wildcard element type accepts both
     * Feign-style {@code Map<String, Collection<String>>} and project-style
     * {@code ConcurrentMap<String, ConcurrentList<String>>}.
     *
     * @param headers the full map of response headers, keyed by header name
     * @return the parsed directives, or {@link #EMPTY} if no {@code Cache-Control} header
     *         is present
     */
    public static @NotNull CacheControl parseFromHeaders(@NotNull Map<String, ? extends Collection<String>> headers) {
        for (Map.Entry<String, ? extends Collection<String>> entry : headers.entrySet()) {
            if (HEADER_KEY.equalsIgnoreCase(entry.getKey()))
                return parse(entry.getValue());
        }

        return EMPTY;
    }

    /**
     * Removes a surrounding pair of double quotes from the given value, if present.
     *
     * @param value the raw directive argument
     * @return the unquoted value, or the original value if it was not quoted
     */
    private static @NotNull String unquote(@NotNull String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
            return value.substring(1, value.length() - 1);

        return value;
    }

    /**
     * Parses a non-negative integer number of seconds from a directive argument.
     * <p>
     * Returns {@link OptionalLong#empty()} if the value is blank, not an integer, or
     * negative, matching the lenient failure mode of {@link RetryAfterParser}.
     *
     * @param value the raw seconds value to parse
     * @return the parsed seconds, or {@link OptionalLong#empty()} if the value is
     *         invalid
     */
    private static @NotNull OptionalLong parseSeconds(@NotNull String value) {
        if (StringUtil.isBlank(value))
            return OptionalLong.empty();

        try {
            long seconds = Long.parseLong(value.trim());

            if (seconds < 0)
                return OptionalLong.empty();

            return OptionalLong.of(seconds);
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

}
