package dev.simplified.client.cache;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.NoArgsConstructor;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.tuple.pair.Pair;
import org.jetbrains.annotations.NotNull;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * Key records used to identify cached HTTP responses in {@link ResponseCache}.
 * <p>
 * The cache uses a two-level structure - {@link UrlKey} selects an inner map of
 * {@code Vary}-variants, and {@link VaryFingerprint}, taken from the request that produced a
 * variant, selects a specific variant within that map. This lets Caffeine evict whole URL
 * buckets atomically while still respecting content-negotiation variants on cache hits. A flat
 * composite key would force O(N) iteration for Vary lookups because Caffeine does not support
 * partial-key scans.
 * <p>
 * Both records are immutable value types with content-based equality. {@code UrlKey}
 * canonicalizes query strings by sorting parameters alphabetically so that a HashMap-based
 * {@code @QueryMap} with non-deterministic iteration order cannot silently fragment the
 * cache across logically identical requests. {@code VaryFingerprint} backs its value map
 * with an unmodifiable {@link ConcurrentMap} built via the project's standard
 * {@link Concurrent#toUnmodifiableTreeMap(java.util.Comparator) toUnmodifiableSortedMap}
 * collector, inheriting content-based equality through the
 * {@code dev.simplified.collection.atomic.AtomicMap#equals(Object)} delegate.
 * <p>
 * {@link #fingerprint(String)} computes the stand-in a key holds for a value configured on a
 * client, and {@link #queryFingerprints(Map)} the stand-in a {@code UrlKey} holds for a client's
 * static query parameters, so a key never holds a configured value itself.
 *
 * @see ResponseCache
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class CacheKey {

    /**
     * The key {@link #fingerprint(String)} signs values under, drawn at random once per JVM.
     */
    private static final @NotNull SecretKeySpec FINGERPRINT_KEY = randomKey();

    /**
     * Computes the stand-in a cache key holds for a value configured on a client, in place of the
     * value itself.
     * <p>
     * The fingerprint is an HMAC-SHA256 of the value under a key drawn at random once per JVM,
     * encoded as unpadded Base64url. Within one JVM equal values have equal fingerprints, so a
     * variant keyed by the fingerprint of a value answers every request carrying that value, and
     * nothing that prints a fingerprint discloses the value.
     *
     * @param value the configured value
     * @return the value's fingerprint
     */
    public static @NotNull String fingerprint(@NotNull String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(FINGERPRINT_KEY);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Renders the stand-in a cache key holds for a client's static query parameters: each
     * parameter's URL-encoded name and the {@linkplain #fingerprint(String) fingerprint} of its
     * value, joined as a query string in the order of their names.
     * <p>
     * A client appends it, with {@link #withQuery(String, String)}, to the URL it keys each request
     * by, while its transport appends the parameters themselves to the request it sends, so
     * requests sent with different static queries are keyed apart, requests sent with the same
     * ones alike, and no key holds a static query's value.
     *
     * @param queries the static query parameters
     * @return the stand-in query string, empty when there are no parameters
     */
    public static @NotNull String queryFingerprints(@NotNull Map<String, String> queries) {
        StringJoiner joined = new StringJoiner("&");
        new TreeMap<>(queries).forEach((name, value) -> joined.add(URLEncoder.encode(name, StandardCharsets.UTF_8) + "=" + fingerprint(value)));
        return joined.toString();
    }

    /**
     * Appends a query string to a URL, after the URL's own query when it has one.
     *
     * @param url the URL
     * @param query the query string to append, without a leading {@code ?} or {@code &}
     * @return {@code url} when {@code query} is empty, otherwise {@code url} followed by
     *         {@code ?}, or {@code &} when it has a query, and {@code query}
     */
    public static @NotNull String withQuery(@NotNull String url, @NotNull String query) {
        if (query.isEmpty())
            return url;

        return url + (url.indexOf('?') < 0 ? "?" : "&") + query;
    }

    /**
     * Draws the key {@link #fingerprint(String)} signs values under.
     *
     * @return a random 256-bit HMAC-SHA256 key
     */
    private static @NotNull SecretKeySpec randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return new SecretKeySpec(key, "HmacSHA256");
    }

    /**
     * Canonical identifier for an HTTP response keyed by method and canonicalized URL.
     * <p>
     * The URL is canonicalized at construction via {@link #canonicalizeUrl(String)}, which
     * sorts query parameters by name so that requests with the same logical query produce
     * the same {@code UrlKey} regardless of parameter iteration order on the caller side.
     *
     * @param method the HTTP method (cacheable methods are normally {@link HttpMethod#GET}
     *               and {@link HttpMethod#HEAD}, but the key itself is method-agnostic)
     * @param url the canonicalized request URL including scheme, host, path, and sorted query string
     */
    public record UrlKey(@NotNull HttpMethod method, @NotNull String url) {

        /**
         * Factory that canonicalizes the given URL before constructing the key.
         *
         * @param method the HTTP method
         * @param url the raw request URL
         * @return a new {@code UrlKey} whose URL has been canonicalized
         */
        public static @NotNull UrlKey of(@NotNull HttpMethod method, @NotNull String url) {
            return new UrlKey(method, canonicalizeUrl(url));
        }

        /**
         * Sorts the query-string parameters of {@code url} by name to produce a stable
         * canonical form.
         * <p>
         * Stable ordering is critical because a {@code @QueryMap(HashMap)} argument on a
         * Feign endpoint has non-deterministic iteration order, which would otherwise
         * silently fragment the cache across logically identical requests. Multi-valued
         * parameters preserve their source order within a name group; duplicate names are
         * kept and appended in insertion order. The path, scheme, and any fragment are
         * returned unchanged.
         *
         * @param url the raw URL to canonicalize
         * @return the canonicalized URL with query parameters sorted by name
         */
        public static @NotNull String canonicalizeUrl(@NotNull String url) {
            int queryStart = url.indexOf('?');

            if (queryStart < 0)
                return url;

            String base = url.substring(0, queryStart);
            String query = url.substring(queryStart + 1);

            if (query.isEmpty())
                return base;

            // TreeMap keyed by raw parameter name preserves deterministic ordering;
            // values are a list to preserve source order within a name group.
            TreeMap<String, java.util.List<String>> sorted = new TreeMap<>();

            for (String pair : query.split("&")) {
                if (pair.isEmpty())
                    continue;

                int eq = pair.indexOf('=');
                String name = eq < 0 ? pair : pair.substring(0, eq);
                String value = eq < 0 ? null : pair.substring(eq + 1);

                sorted.computeIfAbsent(decode(name), k -> new java.util.ArrayList<>()).add(value);
            }

            StringBuilder out = new StringBuilder(base);
            out.append('?');
            boolean first = true;

            for (Map.Entry<String, java.util.List<String>> entry : sorted.entrySet()) {
                String encodedName = java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8);

                for (String raw : entry.getValue()) {
                    if (!first)
                        out.append('&');

                    out.append(encodedName);

                    if (raw != null) {
                        out.append('=');
                        out.append(raw);
                    }

                    first = false;
                }
            }

            return out.toString();
        }

        /**
         * URL-decodes a raw query-parameter name for case-insensitive comparison during
         * canonicalization. Raw values are left encoded to preserve the wire form.
         *
         * @param raw the raw encoded name
         * @return the decoded name, or the original value if decoding fails
         */
        private static @NotNull String decode(@NotNull String raw) {
            try {
                return URLDecoder.decode(raw, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return raw;
            }
        }
    }

    /**
     * Content-negotiation fingerprint of a request: its values of the headers a cached
     * response's {@code Vary} header names.
     * <p>
     * A variant is held under the fingerprint of the request that produced it, and a later
     * request selects it when its own fingerprint over the same names is equal, per
     * <a href="https://datatracker.ietf.org/doc/html/rfc7234#section-4.1">RFC 7234 §4.1</a>. A
     * header the request does not carry has the empty value, so two requests that both omit it
     * match on it.
     * <p>
     * The backing map is an unmodifiable {@link ConcurrentMap} produced by
     * {@link Concurrent#toUnmodifiableTreeMap(java.util.Comparator)} with
     * {@link String#CASE_INSENSITIVE_ORDER}. Record equality delegates to the map's
     * {@code equals}, which in turn delegates through
     * {@code dev.simplified.collection.atomic.AtomicMap#equals(Object)} to the backing
     * sorted map, giving content-based equality without any manual overrides.
     *
     * @param values the request's value of each header the response's {@code Vary} names, keyed
     *               by lowercased header name
     */
    public record VaryFingerprint(@NotNull ConcurrentMap<String, String> values) {

        /**
         * Sentinel fingerprint of every request against a response with no {@code Vary} header.
         */
        public static final @NotNull VaryFingerprint EMPTY = new VaryFingerprint(Concurrent.newUnmodifiableMap());

        /**
         * Builds the fingerprint of a request over the given {@code Vary} header names. Vary
         * names are lowercased for comparison; request header values are looked up
         * case-insensitively and joined with {@code ", "} if multi-valued, and a header the
         * request does not carry has the empty value. Returns {@link #EMPTY} if
         * {@code varyNames} is empty.
         *
         * @param varyNames the set of header names listed in a response's {@code Vary} header
         * @param requestHeaders the request's headers (any map whose values are collections of
         *                       strings)
         * @return the fingerprint for this {@code (varyNames, requestHeaders)} pair
         */
        public static @NotNull VaryFingerprint of(
            @NotNull Set<String> varyNames,
            @NotNull Map<String, ? extends Collection<String>> requestHeaders
        ) {
            if (varyNames.isEmpty())
                return EMPTY;

            ConcurrentMap<String, String> values = varyNames.stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .map(name -> Pair.of(name, joinHeaderValues(findHeaderValues(requestHeaders, name))))
                .collect(Concurrent.toUnmodifiableTreeMap(String.CASE_INSENSITIVE_ORDER));

            return new VaryFingerprint(values);
        }

        /**
         * Performs a case-insensitive header lookup in the given map.
         *
         * @param headers the header map to search
         * @param name the header name (case-insensitive)
         * @return the matching values, or an empty collection if the header is absent
         */
        private static @NotNull Collection<String> findHeaderValues(
            @NotNull Map<String, ? extends Collection<String>> headers,
            @NotNull String name
        ) {
            for (Map.Entry<String, ? extends Collection<String>> entry : headers.entrySet()) {
                if (name.equalsIgnoreCase(entry.getKey()))
                    return entry.getValue();
            }

            return Collections.emptyList();
        }

        /**
         * Joins a collection of header values into the canonical comma-separated form
         * used for fingerprint comparison. Returns an empty string if the collection is
         * null or empty.
         *
         * @param values the collection of header values
         * @return the joined representation, or {@code ""} if none
         */
        private static @NotNull String joinHeaderValues(@NotNull Collection<String> values) {
            if (values.isEmpty())
                return "";

            return String.join(", ", values);
        }
    }

}
