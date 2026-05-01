package dev.simplified.client.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.CacheControl;
import dev.simplified.client.response.Response;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Merged facade over the client's HTTP response cache and the "last response" observability
 * channel, replacing the legacy unbounded {@code recentResponses} list with an RFC 7234
 * compliant private cache.
 * <p>
 * The cache is a two-level Caffeine structure: the outer {@link Cache} is keyed by
 * {@link CacheKey.UrlKey} (HTTP method + canonicalized URL) and holds an inner
 * {@link java.util.concurrent.ConcurrentMap ConcurrentHashMap} of
 * {@link CacheKey.VaryFingerprint} to {@link Response.CachedImpl}. This lets Caffeine evict
 * whole URL buckets while still honouring content-negotiation variants, and avoids the
 * O(N) partial-key scans a flat composite-key layout would require.
 * <p>
 * Eviction is driven by three layered mechanisms:
 * <ul>
 *   <li><b>Per-entry freshness</b> - {@link ResponseCacheExpiry} computes a TTL for each
 *       bucket from each variant's {@link Response.CachedImpl#freshnessLifetime()
 *       freshness lifetime} plus its {@code stale-if-error} window, then clamps it to
 *       the constructor-supplied safety fallback</li>
 *   <li><b>Weight-based eviction</b> - {@link ResponseCacheWeigher} sums raw-body bytes,
 *       header bytes, and an object-graph overhead per variant, with a total cap of
 *       the constructor-supplied max cache bytes</li>
 *   <li><b>Explicit invalidation</b> - unsafe HTTP methods invalidate the target URL
 *       (and any {@code Location} / {@code Content-Location} redirects) per
 *       <a href="https://datatracker.ietf.org/doc/html/rfc7234#section-4.4">RFC 7234 §4.4</a></li>
 * </ul>
 * <p>
 * In addition to the cache itself, this facade owns the client's single-slot
 * "last response" observability reference, exposing it via {@link #getLastResponse()}.
 * Because {@link dev.simplified.client.exception.ApiException ApiException} implements
 * {@link Response}, the same {@link AtomicReference} carries both success and error
 * snapshots, replacing the conflated roles of the old {@code recentResponses} list.
 * <p>
 * This class is a <b>private/user-agent cache</b> per
 * <a href="https://datatracker.ietf.org/doc/html/rfc7234#section-1.2">RFC 7234 §1.2</a>;
 * the {@code Authorization}-header restriction from §3.2, which only binds shared caches,
 * is therefore <b>not</b> applied. Responses to authenticated requests are stored
 * normally.
 *
 * @see ResponseCacheExpiry
 * @see ResponseCacheWeigher
 * @see CachingFeignClient
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7234">RFC 7234 - HTTP/1.1 Caching</a>
 */
public final class ResponseCache {

    /** HTTP status codes that are cacheable by default per RFC 7231 §6.1. */
    private static final @NotNull Set<Integer> DEFAULT_CACHEABLE_STATUSES = Set.of(
        200, 203, 204, 300, 301, 404, 405, 410, 414, 501
    );

    /** Hop-by-hop headers that must not be stored with a cached response per RFC 7230 §6.1. */
    private static final @NotNull Set<String> HOP_BY_HOP_HEADERS = Set.of(
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade"
    );

    /**
     * Additional headers stripped from stored responses because they describe the transport
     * encoding of the wire bytes rather than the cached representation. Apache HttpClient
     * transparently decompresses {@code Content-Encoding: gzip} before Feign sees the body,
     * so the stored "raw" bytes are the decoded content and the original encoding headers
     * would misrepresent them on replay.
     */
    private static final @NotNull Set<String> TRANSPORT_HEADERS = Set.of(
        "content-encoding",
        "content-length"
    );

    /**
     * Marker response header set by {@link CachingFeignClient} on fresh or 304-replay cache hits.
     * <p>
     * Deliberately <b>not</b> prefixed with {@code X-Internal-} so that
     * {@link Response#getHeaders(Map)} preserves it in the public view. This lets callers
     * observe cache hits and lets {@link #store(Response.Impl, byte[])} skip re-storing
     * entries that originated from the cache itself.
     */
    public static final @NotNull String CACHE_HIT_HEADER = "X-Cache-Hit";

    /**
     * Marker response header set by {@link CachingFeignClient} on stale-if-error replays.
     * <p>
     * Not prefixed with {@code X-Internal-} so that callers can distinguish a stale replay
     * from a fresh or revalidated cache hit.
     */
    public static final @NotNull String CACHE_STALE_HEADER = "X-Cache-Served-Stale";

    /** The Caffeine-backed two-level cache of URL bucket -> Vary variants. */
    private final @NotNull Cache<CacheKey.UrlKey, java.util.concurrent.ConcurrentMap<CacheKey.VaryFingerprint, CacheEntry<?>>> cache;

    /** The single-slot observability reference returned from {@link #getLastResponse()}. */
    private final @NotNull AtomicReference<Response<?>> lastResponse = new AtomicReference<>();

    /**
     * Constructs a new response cache with the given byte cap and safety fallback.
     * <p>
     * Caffeine is configured with weight-based eviction capped at {@code maxCacheBytes},
     * a custom {@link ResponseCacheExpiry} whose safety fallback is
     * {@code cacheSafetyFallbackMillis}, and statistics recording enabled so that
     * {@link #getStats()} can report hit/miss/eviction counts.
     *
     * @param maxCacheBytes the maximum total weight of all cached variants in bytes
     * @param cacheSafetyFallbackMillis the absolute upper bound on any entry's lifetime,
     *                                   in milliseconds, regardless of response-advertised
     *                                   freshness
     */
    public ResponseCache(long maxCacheBytes, long cacheSafetyFallbackMillis) {
        this.cache = Caffeine.newBuilder()
            .maximumWeight(maxCacheBytes)
            .weigher(new ResponseCacheWeigher())
            .expireAfter(new ResponseCacheExpiry(Duration.ofMillis(cacheSafetyFallbackMillis)))
            .recordStats()
            .build();
    }

    // ===== Observability =====

    /**
     * Returns the most recently observed response, whether successful or erroneous.
     * <p>
     * Updated by {@link #recordLastResponse(Response)} from the decoder and error-decoder
     * pipelines on every completed exchange, including fresh cache hits replayed through
     * the decoder. Because {@link dev.simplified.client.exception.ApiException} implements
     * {@link Response}, the same reference carries both outcomes.
     *
     * @return the most recent response, or {@link Optional#empty()} if the client has not
     *         yet issued a request
     */
    public @NotNull Optional<Response<?>> getLastResponse() {
        return Optional.ofNullable(this.lastResponse.get());
    }

    /**
     * Records a newly-observed response as the most recent one.
     * <p>
     * Called from {@link dev.simplified.client.decoder.InternalResponseDecoder} for
     * successful and decode-failure outcomes and from
     * {@link dev.simplified.client.decoder.InternalErrorDecoder} for HTTP error outcomes.
     *
     * @param response the response to record
     */
    public void recordLastResponse(@NotNull Response<?> response) {
        this.lastResponse.set(response);
    }

    /**
     * Returns the underlying Caffeine {@link CacheStats} for diagnostic inspection
     * (hit/miss/eviction counts, load penalties).
     *
     * @return a snapshot of the cache's statistics
     */
    public @NotNull CacheStats getStats() {
        return this.cache.stats();
    }

    // ===== Cache operations =====

    /**
     * Looks up a cached entry matching the given request.
     * <p>
     * Resolves the outer {@link CacheKey.UrlKey} bucket, then iterates the inner Vary map
     * and returns the first entry whose response's
     * {@link Response.CachedImpl#varyHeaderNames() vary header names} match the current
     * request's headers. Returns {@link Optional#empty()} if no bucket or variant matches.
     * Freshness and revalidation decisions are the caller's responsibility (typically
     * {@link CachingFeignClient}).
     *
     * @param method the HTTP method of the lookup request
     * @param url the raw URL of the lookup request (will be canonicalized internally)
     * @param requestHeaders the lookup request's headers, used for Vary matching
     * @return the matching cached entry, or {@link Optional#empty()} if none
     */
    public @NotNull Optional<CacheEntry<?>> lookup(
        @NotNull HttpMethod method,
        @NotNull String url,
        @NotNull Map<String, ? extends Collection<String>> requestHeaders
    ) {
        CacheKey.UrlKey key = CacheKey.UrlKey.of(method, url);
        java.util.concurrent.ConcurrentMap<CacheKey.VaryFingerprint, CacheEntry<?>> variants = this.cache.getIfPresent(key);

        if (variants == null || variants.isEmpty())
            return Optional.empty();

        for (CacheEntry<?> candidate : variants.values()) {
            Response.CachedImpl<?> response = candidate.response();
            Set<String> varyNames = response.varyHeaderNames();
            CacheKey.VaryFingerprint lookupFp = CacheKey.VaryFingerprint.of(varyNames, requestHeaders);
            CacheKey.VaryFingerprint storedFp = CacheKey.VaryFingerprint.of(varyNames, toMultimap(response.getHeaders()));

            if (lookupFp.equals(storedFp))
                return Optional.of(candidate);
        }

        return Optional.empty();
    }

    /**
     * Stores a decoded response and its captured body bytes in the cache if it passes the
     * RFC 7234 §3 storage predicate. No-ops when the storage predicate rejects the response
     * for any reason (see the class-level Javadoc and inline rules below).
     * <p>
     * Storage rules:
     * <ul>
     *   <li>{@code Cache-Control: no-store} -> skip</li>
     *   <li>method is not {@link HttpMethod#isCacheable() cacheable} -> skip</li>
     *   <li>{@code Vary: *} -> skip</li>
     *   <li>status not in the default cacheable set {@code {200, 203, 204, 300, 301, 404,
     *       405, 410, 414, 501}} unless explicit freshness is present -> skip</li>
     *   <li>response carries the {@link #CACHE_HIT_HEADER} marker (replay from this cache)
     *       -> skip</li>
     * </ul>
     * <p>
     * Streaming responses skip this overload entirely - the decoder pipeline routes them
     * around the cache because their bodies cannot be replayed.
     * <p>
     * Before storage, hop-by-hop headers, {@code Content-Encoding}, and {@code Content-Length}
     * are stripped from the response headers so that a replayed entry cannot misrepresent the
     * stored body's transport framing.
     *
     * @param decoded the decoded response to consider for caching
     * @param body the captured body bytes to store alongside {@code decoded} for replay
     */
    public void store(@NotNull Response.Impl<?> decoded, byte @NotNull [] body) {
        if (!shouldStore(decoded))
            return;

        CacheEntry<?> entry = buildEntry(decoded, body);
        Response.CachedImpl<?> cached = entry.response();

        CacheKey.UrlKey key = CacheKey.UrlKey.of(cached.getRequest().getMethod(), cached.getRequest().getUrl());
        CacheKey.VaryFingerprint fingerprint = CacheKey.VaryFingerprint.of(cached.varyHeaderNames(), toMultimap(cached.getHeaders()));

        java.util.concurrent.ConcurrentMap<CacheKey.VaryFingerprint, CacheEntry<?>> variants = this.cache.asMap()
            .computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        variants.put(fingerprint, entry);
    }

    /**
     * Wildcard-capturing helper that builds a fresh {@link CacheEntry} from a decoded
     * response and its captured body bytes.
     *
     * @param decoded the decoded response to wrap
     * @param body the captured body bytes
     * @param <T> the decoded body type, captured from the wildcard at the call site
     * @return a new entry pairing the sanitized cached view with the body bytes
     */
    private static <T> @NotNull CacheEntry<T> buildEntry(@NotNull Response.Impl<T> decoded, byte @NotNull [] body) {
        Response.Impl<T> sanitized = stripTransportHeaders(decoded);
        return new CacheEntry<>(Response.CachedImpl.from(sanitized), body);
    }

    /**
     * Invalidates every variant stored under the given URL, for every HTTP method.
     * <p>
     * Called by {@link CachingFeignClient} after unsafe-method successes for the target
     * URL plus any {@code Location} and {@code Content-Location} redirects, per RFC 7234 §4.4.
     *
     * @param url the URL whose cached entries should be removed; may be {@code null} to
     *            make propagation from optional response headers painless at call sites
     */
    public void invalidate(@Nullable String url) {
        if (url == null || url.isEmpty())
            return;

        String canonical = CacheKey.UrlKey.canonicalizeUrl(url);
        this.cache.asMap().keySet().removeIf(key -> key.url().equals(canonical));
    }

    /**
     * Refreshes a cached variant after a successful {@code 304 Not Modified} revalidation
     * per <a href="https://datatracker.ietf.org/doc/html/rfc7234#section-4.3.4">RFC 7234
     * §4.3.4</a>.
     * <p>
     * Builds a new {@link Response.CachedImpl} whose headers are the cached entry's headers
     * overlaid with the end-to-end headers from the 304 response, never touching
     * {@code Content-Length}, {@code Content-Encoding}, or {@code Transfer-Encoding}. The
     * raw body, status, and request are carried forward unchanged from the cached entry.
     * The resulting entry replaces the old one under the same {@code (UrlKey, VaryFingerprint)}
     * pair, which triggers {@link ResponseCacheExpiry#expireAfterUpdate} and resets the
     * bucket's TTL from the newly-parsed {@code Cache-Control} directives.
     * <p>
     * If no variant is found at the given key/fingerprint, the method is a no-op - the
     * entry was likely evicted by the safety fallback or weight pressure between the
     * original read and the revalidation.
     *
     * @param key the URL bucket of the cached variant
     * @param fingerprint the Vary fingerprint of the cached variant
     * @param new304Headers the headers returned on the {@code 304} revalidation response
     */
    public void updateOn304(
        @NotNull CacheKey.UrlKey key,
        @NotNull CacheKey.VaryFingerprint fingerprint,
        @NotNull Map<String, ? extends Collection<String>> new304Headers
    ) {
        java.util.concurrent.ConcurrentMap<CacheKey.VaryFingerprint, CacheEntry<?>> variants = this.cache.getIfPresent(key);

        if (variants == null)
            return;

        CacheEntry<?> existing = variants.get(fingerprint);

        if (existing == null)
            return;

        variants.put(fingerprint, mergeHeaders(existing, new304Headers));
    }

    // ===== Internals =====

    /**
     * Applies the RFC 7234 §3 storage predicate to a decoded response.
     *
     * @param decoded the decoded response to evaluate
     * @return {@code true} if the response is eligible for caching
     */
    private static boolean shouldStore(@NotNull Response.Impl<?> decoded) {
        HttpMethod method = decoded.getRequest().getMethod();

        if (!method.isCacheable())
            return false;

        if (hasHeader(decoded.getHeaders(), CACHE_HIT_HEADER))
            return false;

        CacheControl cc = CacheControl.parseFromHeaders(decoded.getHeaders());

        if (cc.noStore())
            return false;

        if (hasVaryWildcard(decoded.getHeaders()))
            return false;

        int status = decoded.getStatus().getCode();

        if (DEFAULT_CACHEABLE_STATUSES.contains(status))
            return true;

        // Non-default-cacheable statuses are only stored if explicit freshness was advertised.
        return cc.maxAge().isPresent() || cc.sMaxAge().isPresent() || hasHeader(decoded.getHeaders(), "Expires");
    }

    /**
     * Builds a copy of the given decoded response with hop-by-hop and transport-framing
     * headers stripped from its anchor's header map.
     *
     * @param decoded the decoded response to sanitize
     * @param <T> the decoded body type
     * @return a new {@link Response.Impl} carrying the same anchor bytes and decoder but
     *         with stripped headers
     */
    private static <T> @NotNull Response.Impl<T> stripTransportHeaders(@NotNull Response.Impl<T> decoded) {
        feign.Response anchor = decoded.getAnchor();
        Map<String, Collection<String>> sanitized = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        for (Map.Entry<String, Collection<String>> entry : anchor.headers().entrySet()) {
            String lower = entry.getKey().toLowerCase(Locale.ROOT);

            if (HOP_BY_HOP_HEADERS.contains(lower) || TRANSPORT_HEADERS.contains(lower))
                continue;

            sanitized.put(entry.getKey(), entry.getValue());
        }

        feign.Response sanitizedAnchor = anchor.toBuilder()
            .headers(sanitized)
            .build();

        return decoded.withAnchor(sanitizedAnchor);
    }

    /**
     * Merges the headers from a 304 response into an existing cached entry, producing a
     * fresh {@link CacheEntry} whose response anchor carries the merged headers while the
     * body bytes, status, and request are inherited from the existing entry.
     *
     * @param existing the cached entry to refresh
     * @param new304Headers the headers from the 304 response
     * @param <T> the decoded body type
     * @return a new {@code CacheEntry} with merged headers and the existing body bytes
     */
    private static <T> @NotNull CacheEntry<T> mergeHeaders(
        @NotNull CacheEntry<T> existing,
        @NotNull Map<String, ? extends Collection<String>> new304Headers
    ) {
        Response.CachedImpl<T> existingResponse = existing.response();
        feign.Response anchor = existingResponse.getAnchor();
        Map<String, Collection<String>> merged = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        merged.putAll(anchor.headers());

        for (Map.Entry<String, ? extends Collection<String>> entry : new304Headers.entrySet()) {
            String name = entry.getKey();
            String lower = name.toLowerCase(Locale.ROOT);

            if (HOP_BY_HOP_HEADERS.contains(lower) || TRANSPORT_HEADERS.contains(lower))
                continue;

            if (entry.getValue() == null || entry.getValue().isEmpty())
                continue;

            merged.put(name, new ArrayList<>(entry.getValue()));
        }

        feign.Response mergedAnchor = anchor.toBuilder()
            .headers(merged)
            .build();

        return new CacheEntry<>(Response.CachedImpl.from(existingResponse.withAnchor(mergedAnchor)), existing.body());
    }

    /**
     * Returns {@code true} if the given headers contain a case-insensitive entry for
     * {@code name}.
     *
     * @param headers the header map to search
     * @param name the header name
     * @return {@code true} if the header is present
     */
    private static boolean hasHeader(@NotNull ConcurrentMap<String, ConcurrentList<String>> headers, @NotNull String name) {
        return headers.getOptional(name).isPresent();
    }

    /**
     * Returns {@code true} if the {@code Vary} header is present and has a value of {@code *}.
     *
     * @param headers the header map to search
     * @return {@code true} if {@code Vary: *} is present
     */
    private static boolean hasVaryWildcard(@NotNull ConcurrentMap<String, ConcurrentList<String>> headers) {
        return headers.getOptional("Vary")
            .flatMap(ConcurrentList::findFirst)
            .map(value -> "*".equals(value.trim()))
            .orElse(false);
    }

    /**
     * Adapts a {@link ConcurrentMap} of {@link ConcurrentList} values into a plain
     * {@code Map<String, Collection<String>>} for use with Feign-style APIs and helpers
     * that expect a standard multimap shape.
     *
     * @param source the project-style concurrent header map
     * @return a read-only view of the same headers as a plain multimap
     */
    private static @NotNull Map<String, Collection<String>> toMultimap(@NotNull ConcurrentMap<String, ConcurrentList<String>> source) {
        if (source.isEmpty())
            return Collections.emptyMap();

        Map<String, Collection<String>> out = new HashMap<>(source.size());

        for (Map.Entry<String, ConcurrentList<String>> entry : source.entrySet())
            out.put(entry.getKey(), List.copyOf(entry.getValue()));

        return Collections.unmodifiableMap(out);
    }

}
