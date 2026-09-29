package dev.simplified.client.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import dev.simplified.client.decoder.InternalErrorDecoder;
import dev.simplified.client.decoder.InternalResponseDecoder;
import dev.simplified.client.exception.ApiException;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Facade over the client's RFC 7234 private HTTP response cache and its "last response"
 * observability channel.
 * <p>
 * The cache is a two-level Caffeine structure: the outer {@link Cache} is keyed by
 * {@link CacheKey.UrlKey} (HTTP method + canonicalized URL) and holds a bucket, an inner
 * {@link ConcurrentHashMap} of
 * {@link CacheKey.VaryFingerprint} to {@link CacheEntry}. This lets Caffeine evict
 * whole URL buckets while still honouring content-negotiation variants, and avoids the
 * O(N) partial-key scans a flat composite-key layout would require.
 * <p>
 * Variants are selected per
 * <a href="https://datatracker.ietf.org/doc/html/rfc7234#section-4.1">RFC 7234 §4.1</a>. A
 * variant is held under the {@link CacheKey.VaryFingerprint fingerprint} of the request that
 * produced it - that request's values of the headers its response's {@code Vary} names - and
 * answers a later request only when that request carries the same values; of several variants
 * one request matches, the most recent by {@code Date} answers. Callers pass a
 * request's headers as the request leaves the client, the client's configured static and
 * dynamic headers included, to {@link #store}, {@link #lookup} and {@link #updateOn304} alike. A
 * response that varies on a header the transport sets below the cache with a value those
 * headers do not fix is not stored.
 * <p>
 * A bucket is never changed in place. {@link #store} and
 * {@link #updateOn304} write a new bucket, holding the current bucket's variants plus the
 * stored or refreshed one, through {@code compute} on the cache's {@link Cache#asMap() map
 * view}, so Caffeine weighs the populated bucket and sets its lifetime from every variant it
 * holds.
 * <p>
 * Eviction is driven by three layered mechanisms:
 * <ul>
 *   <li><b>Per-bucket lifetime</b> - {@link ResponseCacheExpiry} ends each bucket's lifetime,
 *       counted from the write that created or last replaced it, once none of its variants can
 *       answer a request any longer: a variant is held for its
 *       {@link Response.CachedImpl#freshnessLifetime() freshness lifetime} plus the longer of
 *       the {@code stale-if-error} window it may be served under and, when it carries an
 *       {@code ETag} or {@code Last-Modified} validator, the constructor-supplied stale
 *       retention, so a stale variant is still there for a conditional request to revalidate.
 *       The lifetime is clamped to the constructor-supplied safety fallback</li>
 *   <li><b>Weight-based eviction</b> - {@link ResponseCacheWeigher} sums raw-body bytes,
 *       header bytes, and an object-graph overhead per variant, with a total cap of
 *       the constructor-supplied max cache bytes</li>
 *   <li><b>Explicit invalidation</b> - unsafe HTTP methods invalidate the target URL
 *       (and any {@code Location} / {@code Content-Location} redirects) per
 *       <a href="https://datatracker.ietf.org/doc/html/rfc7234#section-4.4">RFC 7234 §4.4</a>,
 *       and {@link #invalidateAll()} drops every entry</li>
 * </ul>
 * <p>
 * An invalidation also holds against answers still in flight: {@link #store} keeps a response,
 * and {@link #updateOn304} applies a {@code 304 Not Modified}, only when its request was sent
 * after {@link #invalidateAll()} last dropped every entry and after {@link #invalidate(String)}
 * last invalidated its URL, so an answer to a request sent before either cannot put the
 * invalidated state back. A {@code GET} in flight across the {@code PUT} or {@code POST} that
 * invalidated its URL therefore does not store what it read before the mutation.
 * <p>
 * In addition to the cache itself, this facade owns the client's single-slot
 * "last response" observability reference, exposing it via {@link #getLastResponse()}.
 * Because {@link ApiException ApiException} implements
 * {@link Response}, the same {@link AtomicReference} carries both success and error
 * snapshots.
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

    /**
     * HTTP status codes that are cacheable by default per RFC 7231 §6.1.
     */
    private static final @NotNull Set<Integer> DEFAULT_CACHEABLE_STATUSES = Set.of(
        200, 203, 204, 300, 301, 404, 405, 410, 414, 501
    );

    /**
     * Hop-by-hop headers that must not be stored with a cached response per RFC 7230 §6.1.
     */
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
     * observe cache hits via {@link Response#isFromCache()} and lets
     * {@link #store} skip re-storing entries that originated from the
     * cache itself.
     */
    public static final @NotNull String CACHE_HIT_HEADER = "X-Cache-Hit";

    /**
     * Marker response header set by {@link CachingFeignClient} on stale-if-error replays.
     * <p>
     * Not prefixed with {@code X-Internal-} so that callers can distinguish a stale replay
     * from a fresh or revalidated cache hit.
     */
    public static final @NotNull String CACHE_STALE_HEADER = "X-Cache-Served-Stale";

    /**
     * Internal marker response header set by {@link CachingFeignClient} on the replay answering a
     * {@code 304 Not Modified} revalidation, its values naming the headers the 304 carried.
     * <p>
     * The replay carries the stored headers overlaid with the 304's, so each named header holds
     * the value the server sent with the 304 and every other header is replayed from the cache.
     * Named with {@link NetworkDetails#INTERNAL_HEADER_PREFIX} as an internal header.
     */
    public static final @NotNull String REVALIDATED_HEADER = NetworkDetails.INTERNAL_HEADER_PREFIX + "Revalidated";

    /**
     * How long, in milliseconds, an entry carrying an {@code ETag} or {@code Last-Modified}
     * validator is kept past its freshness when no retention is given - one hour.
     * <p>
     * A stale entry held this long answers a request made within the hour through a conditional
     * request, whose {@code 304 Not Modified} carries no body, rather than a full one; an hour
     * covers a client polling an origin every few minutes with room to spare, while an entry no
     * request has revalidated for that long gives up its weight to entries in use. The
     * {@linkplain #ResponseCache(long, long) safety fallback} caps it.
     */
    public static final long DEFAULT_STALE_RETENTION_MILLIS = Duration.ofHours(1).toMillis();

    /**
     * The most URLs {@link #invalidatedAt} records before {@link #invalidate(String)} folds them
     * into {@link #refusedThrough}.
     * <p>
     * A fold keeps the guard every record gave, and more: an answer to any request sent before
     * it is refused, whatever its URL, so the requests in flight at the moment of a fold are
     * answered but not stored. A fold falls once in this many invalidations of distinct URLs,
     * which bounds the records a client that mutates many URLs holds.
     */
    static final int INVALIDATION_RECORD_LIMIT = 1024;

    /**
     * Orders the variants of one URL from the least to the most recent: by the
     * {@linkplain Response.CachedImpl#date() date} each response was generated, then by the
     * instant each was received, then by the headers and values of the fingerprint each is held
     * under, which no two variants of one bucket share.
     */
    private static final @NotNull Comparator<Map.Entry<CacheKey.VaryFingerprint, CacheEntry<?>>> RECENCY = Comparator
        .<Map.Entry<CacheKey.VaryFingerprint, CacheEntry<?>>, Instant>comparing(variant -> variant.getValue().response().date())
        .thenComparing(variant -> variant.getValue().response().getDetails().getRoundTrip().completedAt())
        .thenComparing(variant -> ordering(variant.getKey()));

    /**
     * The Caffeine-backed two-level cache of URL bucket -> Vary variants.
     */
    private final @NotNull Cache<CacheKey.UrlKey, java.util.concurrent.ConcurrentMap<CacheKey.VaryFingerprint, CacheEntry<?>>> cache;

    /**
     * The single-slot observability reference returned from {@link #getLastResponse()}.
     */
    private final @NotNull AtomicReference<Response<?>> lastResponse = new AtomicReference<>();

    /**
     * Orders {@link #invalidateAll()} and {@link #invalidate(String)} against {@link #store} and
     * {@link #updateOn304}: an invalidation holds the write lock while it records when it ran in
     * {@link #refusedThrough} or {@link #invalidatedAt}, and a store or refresh holds the read
     * lock while it compares its request's start with both and writes the bucket.
     */
    private final @NotNull ReadWriteLock dropLock = new ReentrantReadWriteLock();

    /**
     * The instant at or before which an answer to any request is refused - when
     * {@link #invalidateAll()} last emptied the cache, or {@link #invalidate(String)} last folded
     * its records into it - or {@link Instant#EPOCH} before either has; read and written only
     * under {@link #dropLock}.
     */
    private @NotNull Instant refusedThrough = Instant.EPOCH;

    /**
     * The instant {@link #invalidate(String)} last invalidated each URL, keyed by canonical URL,
     * holding at most {@link #INVALIDATION_RECORD_LIMIT} URLs; read and written only under
     * {@link #dropLock}.
     */
    private final @NotNull Map<String, Instant> invalidatedAt = new HashMap<>();

    /**
     * Constructs a new response cache with the given byte cap and safety fallback, keeping an
     * entry carrying a validator for {@link #DEFAULT_STALE_RETENTION_MILLIS} past its freshness.
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
        this(maxCacheBytes, cacheSafetyFallbackMillis, DEFAULT_STALE_RETENTION_MILLIS);
    }

    /**
     * Constructs a new response cache with the given byte cap, safety fallback and stale
     * retention.
     * <p>
     * Configured as {@link #ResponseCache(long, long)} is, with a {@link ResponseCacheExpiry}
     * that keeps an entry carrying an {@code ETag} or {@code Last-Modified} validator for
     * {@code staleRetentionMillis} past its freshness, so a conditional request can revalidate
     * it.
     *
     * @param maxCacheBytes the maximum total weight of all cached variants in bytes
     * @param cacheSafetyFallbackMillis the absolute upper bound on any entry's lifetime, in
     *                                  milliseconds, regardless of response-advertised freshness
     * @param staleRetentionMillis how long, in milliseconds, an entry carrying a validator is
     *                             kept past its freshness; zero keeps none past its freshness and
     *                             {@code stale-if-error} window
     */
    public ResponseCache(long maxCacheBytes, long cacheSafetyFallbackMillis, long staleRetentionMillis) {
        this(maxCacheBytes, cacheSafetyFallbackMillis, staleRetentionMillis, Ticker.systemTicker());
    }

    /**
     * Constructs a new response cache whose bucket lifetimes are measured on the given ticker,
     * keeping an entry carrying a validator for {@link #DEFAULT_STALE_RETENTION_MILLIS} past its
     * freshness.
     *
     * @param maxCacheBytes the maximum total weight of all cached variants in bytes
     * @param cacheSafetyFallbackMillis the absolute upper bound on any entry's lifetime, in
     *                                  milliseconds
     * @param ticker the time source Caffeine measures bucket lifetimes against
     */
    ResponseCache(long maxCacheBytes, long cacheSafetyFallbackMillis, @NotNull Ticker ticker) {
        this(maxCacheBytes, cacheSafetyFallbackMillis, DEFAULT_STALE_RETENTION_MILLIS, ticker);
    }

    /**
     * Constructs a new response cache whose bucket lifetimes are measured on the given ticker.
     *
     * @param maxCacheBytes the maximum total weight of all cached variants in bytes
     * @param cacheSafetyFallbackMillis the absolute upper bound on any entry's lifetime, in
     *                                  milliseconds
     * @param staleRetentionMillis how long, in milliseconds, an entry carrying a validator is
     *                             kept past its freshness
     * @param ticker the time source Caffeine measures bucket lifetimes against
     */
    ResponseCache(long maxCacheBytes, long cacheSafetyFallbackMillis, long staleRetentionMillis, @NotNull Ticker ticker) {
        this.cache = Caffeine.newBuilder()
            .maximumWeight(maxCacheBytes)
            .weigher(new ResponseCacheWeigher())
            .expireAfter(new ResponseCacheExpiry(
                Duration.ofMillis(cacheSafetyFallbackMillis),
                Duration.ofMillis(staleRetentionMillis)
            ))
            .ticker(ticker)
            .recordStats()
            .build();
    }

    // ===== Observability =====

    /**
     * Returns the most recently observed response, whether successful or erroneous.
     * <p>
     * Updated by {@link #recordLastResponse(Response)} from the decoder and error-decoder
     * pipelines on every completed exchange, including fresh cache hits replayed through
     * the decoder. Because {@link ApiException} implements
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
     * Called from {@link InternalResponseDecoder} for
     * successful and decode-failure outcomes and from
     * {@link InternalErrorDecoder} for HTTP error outcomes.
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
     * Looks up the cached variant a request may be answered with.
     * <p>
     * Resolves the outer {@link CacheKey.UrlKey} bucket, then returns a variant whose
     * fingerprint - the values, in the request that produced it, of the headers its response's
     * {@link Response.CachedImpl#varyHeaderNames() Vary} names - equals the values of the same
     * headers in {@code requestHeaders}, per
     * <a href="https://datatracker.ietf.org/doc/html/rfc7234#section-4.1">RFC 7234 §4.1</a>. A
     * variant whose response has no {@code Vary} matches every request. When several variants
     * match - variants stored under different {@code Vary} header sets, after an origin changed
     * the headers it varies a URL on - the most recent answers: the one whose response's
     * {@linkplain Response.CachedImpl#date() date} is the latest, then the one received last,
     * then the one whose fingerprint orders last by header name and value, so the answer never
     * depends on the order the bucket holds its variants in. Returns {@link Optional#empty()} if
     * no bucket or variant matches. Freshness and revalidation decisions are the caller's
     * responsibility (typically {@link CachingFeignClient}).
     *
     * @param method the HTTP method of the lookup request
     * @param url the raw URL of the lookup request (will be canonicalized internally)
     * @param requestHeaders the lookup request's headers as it leaves the client, compared with
     *                       the headers {@link #store} was given for each variant's request
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

        return variants.entrySet()
            .stream()
            .filter(variant -> CacheKey.VaryFingerprint.of(variant.getValue().response().varyHeaderNames(), requestHeaders).equals(variant.getKey()))
            .max(RECENCY)
            .map(Map.Entry::getValue);
    }

    /**
     * Stores a decoded response and its captured body bytes in the cache if it passes the
     * RFC 7234 §3 storage predicate and its request was sent after the last
     * {@link #invalidateAll()} and the last {@link #invalidate(String)} of its URL. No-ops when
     * any rule below rejects the response.
     * <p>
     * Storage rules:
     * <ul>
     *   <li>{@code Cache-Control: no-store} -> skip</li>
     *   <li>method is not {@link HttpMethod#isCacheable() cacheable} -> skip</li>
     *   <li>{@code Vary} names {@code *}, or a header whose value the origin received
     *       {@code requestHeaders} do not fix ({@code Cookie} when the request carries none, which
     *       the transport adds from a cookie store that can change between requests, or an
     *       {@linkplain NetworkDetails#isInternalHeader(String) internal header}, which carries
     *       per-request values) -> skip, as no later request could be shown to match. Every other
     *       header the transport sets - {@code Accept-Encoding} and {@code User-Agent} when the
     *       request carries none, a Feign transport's {@code Accept} default, {@code Host} - takes a
     *       value fixed by the request's own headers and URL, so requests that match on their own
     *       headers match on the wire</li>
     *   <li>status not in the default cacheable set {@code {200, 203, 204, 300, 301, 404,
     *       405, 410, 414, 501}} unless explicit freshness is present -> skip</li>
     *   <li>response carries the {@link #CACHE_HIT_HEADER} marker (replay from this cache)
     *       -> skip</li>
     *   <li>the start of the response's {@linkplain NetworkDetails#getRoundTrip() round trip}
     *       is not after the instant {@link #invalidateAll()} last emptied the cache, or the
     *       instant {@link #invalidate(String)} last invalidated the response's URL -> skip, so
     *       an answer to a request in flight across an invalidation cannot re-enter the
     *       cache</li>
     *   <li>the response carries no request start, which {@link NetworkDetails} reads as
     *       {@link Instant#EPOCH} -> skip: nothing shows its request was sent after the last
     *       drop, and {@link Response.CachedImpl#currentAge(Instant)} measures an entry's age
     *       from that round trip, so the entry could never be judged fresh</li>
     * </ul>
     * <p>
     * The entry is held under the {@link CacheKey.VaryFingerprint} of {@code requestHeaders} -
     * their values of the headers the response's {@code Vary} names - which {@link #lookup}
     * compares a later request's values with. It joins its URL's bucket through
     * {@code compute}, which writes a new bucket holding the current bucket's variants and this
     * one, replacing any variant with the same fingerprint. Caffeine weighs the new bucket and
     * sets its lifetime from every variant it holds (see {@link ResponseCacheExpiry}). A
     * response carrying a validator is held past its freshness, even one with no explicit
     * freshness, so a later request revalidates it. A bucket none of whose variants could answer
     * a request - a response with no validator and neither explicit freshness nor a
     * {@code stale-if-error} window it may be served under, or one carrying {@code no-cache} and
     * no validator - is expired as it is written and is never answered by {@link #lookup}.
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
     * @param requestHeaders the headers of the request that produced {@code decoded}, as it left
     *                       the client
     */
    public void store(
        @NotNull Response<?> decoded,
        byte @NotNull [] body,
        @NotNull Map<String, ? extends Collection<String>> requestHeaders
    ) {
        if (!shouldStore(decoded))
            return;

        Instant sent = decoded.getDetails().getRoundTrip().startedAt();
        CacheEntry<?> entry = buildEntry(decoded, body);
        Response.CachedImpl<?> cached = entry.response();
        CacheKey.VaryFingerprint fingerprint = CacheKey.VaryFingerprint.of(cached.varyHeaderNames(), requestHeaders);

        if (!isMatchable(fingerprint))
            return;

        CacheKey.UrlKey key = CacheKey.UrlKey.of(cached.getRequest().getMethod(), cached.getRequest().getUrl());
        Lock lock = this.dropLock.readLock();
        lock.lock();

        try {
            if (this.sentAfterInvalidation(key.url(), sent))
                this.cache.asMap().compute(key, (k, variants) -> withVariant(variants, fingerprint, entry));
        } finally {
            lock.unlock();
        }
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
    private static <T> @NotNull CacheEntry<T> buildEntry(@NotNull Response<T> decoded, byte @NotNull [] body) {
        ConcurrentMap<String, ConcurrentList<String>> sanitized = stripTransportHeaders(decoded);
        return new CacheEntry<>(Response.CachedImpl.from(decoded).withHeaders(sanitized), body);
    }

    /**
     * Invalidates every variant stored under the given URL, for every HTTP method, and records
     * the instant it did so.
     * <p>
     * Called by {@link CachingFeignClient} after unsafe-method successes for the target
     * URL plus any {@code Location} and {@code Content-Location} redirects, per RFC 7234 §4.4.
     * <p>
     * {@link #store} refuses a response for the URL, and {@link #updateOn304} a
     * {@code 304 Not Modified}, whose request was sent at or before that instant, so an answer to
     * a request in flight across the invalidation - a {@code GET} sent before the mutation that
     * invalidated its URL completed - cannot put the state from before the mutation back; a
     * request whose start falls in the same clock tick as the invalidation counts as sent before
     * it. The instant is recorded before any variant is removed, so a store or refresh racing
     * the invalidation either writes before the record, and its entry is removed, or compares
     * its request's start with the record, and is refused when that request was sent before.
     * <p>
     * The instants are recorded per canonical URL, up to {@link #INVALIDATION_RECORD_LIMIT}
     * URLs. An invalidation of a URL beyond that folds every record into one instant, at or
     * after each of them, that refuses an answer to any request sent before it; the records
     * are then dropped.
     *
     * @param url the URL whose cached entries should be removed; may be {@code null} to
     *            make propagation from optional response headers painless at call sites
     */
    public void invalidate(@Nullable String url) {
        if (url == null || url.isEmpty())
            return;

        String canonical = CacheKey.UrlKey.canonicalizeUrl(url);
        Lock lock = this.dropLock.writeLock();
        lock.lock();

        try {
            Instant now = Instant.now();

            if (this.invalidatedAt.size() >= INVALIDATION_RECORD_LIMIT && !this.invalidatedAt.containsKey(canonical))
                this.foldInvalidations(now);
            else
                this.invalidatedAt.put(canonical, now);
        } finally {
            lock.unlock();
        }

        this.cache.asMap().keySet().removeIf(key -> key.url().equals(canonical));
    }

    /**
     * Drops every cached entry and records the instant it did so.
     * <p>
     * {@link #store} refuses a response, and {@link #updateOn304} a
     * {@code 304 Not Modified}, whose request was sent at or before that instant, so an answer to
     * a request in flight across the drop cannot put the dropped state back; a request whose
     * start falls in the same clock tick as the drop counts as sent before it. A store or refresh
     * racing the drop either writes before it, and its entry is dropped, or compares its
     * request's start after it, and is refused when that request was sent before. The instants
     * {@link #invalidate(String)} recorded per URL are folded into the drop's.
     */
    public void invalidateAll() {
        Lock lock = this.dropLock.writeLock();
        lock.lock();

        try {
            this.foldInvalidations(Instant.now());
            this.cache.invalidateAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Refreshes a cached variant after a successful {@code 304 Not Modified} revalidation
     * per <a href="https://datatracker.ietf.org/doc/html/rfc7234#section-4.3.4">RFC 7234
     * §4.3.4</a>.
     * <p>
     * Builds a new {@link CacheEntry} whose headers are the cached entry's headers overlaid
     * with the end-to-end headers from the 304 response, and whose age is measured from the 304
     * exchange (see {@link #mergeHeaders(CacheEntry, Map, NetworkDetails)}). The raw body,
     * status, and request are carried forward unchanged from the cached entry. The refreshed
     * entry replaces the old one through {@code compute}, in a new bucket holding the bucket's
     * other variants, which triggers {@link ResponseCacheExpiry#expireAfterUpdate} and restarts
     * the bucket's lifetime from the refreshed {@code Cache-Control} directives.
     * <p>
     * The variant is addressed as {@link #lookup} matched it: by the
     * {@link CacheKey.VaryFingerprint} of the revalidated request's headers over the headers the
     * cached response's {@code Vary} names, which equals the fingerprint the variant is held under.
     * <p>
     * The method is a no-op when no bucket or no variant is found at the given key/fingerprint -
     * the bucket expired, was evicted by weight pressure, or was invalidated or dropped between
     * the lookup and the revalidation - and when the conditional request was sent at or before
     * the instant {@link #invalidateAll()} last emptied the cache or {@link #invalidate(String)}
     * last invalidated the URL, or carries no request start, so a revalidation in flight across
     * an invalidation cannot refresh an entry stored after it.
     *
     * @param key the URL bucket of the cached variant
     * @param fingerprint the fingerprint of the revalidated request's headers over the headers the
     *                    cached response's {@code Vary} names
     * @param new304Headers the headers returned on the {@code 304} revalidation response
     * @param revalidation the network details of the {@code 304} exchange
     * @return the refreshed entry now in the cache, or {@link Optional#empty()} if nothing was
     *         refreshed
     */
    public @NotNull Optional<CacheEntry<?>> updateOn304(
        @NotNull CacheKey.UrlKey key,
        @NotNull CacheKey.VaryFingerprint fingerprint,
        @NotNull Map<String, ? extends Collection<String>> new304Headers,
        @NotNull NetworkDetails revalidation
    ) {
        java.util.concurrent.ConcurrentMap<CacheKey.VaryFingerprint, CacheEntry<?>> current = this.cache.getIfPresent(key);

        if (current == null || !current.containsKey(fingerprint))
            return Optional.empty();

        Instant sent = revalidation.getRoundTrip().startedAt();
        AtomicReference<CacheEntry<?>> refreshed = new AtomicReference<>();
        Lock lock = this.dropLock.readLock();
        lock.lock();

        try {
            if (this.sentAfterInvalidation(key.url(), sent)) {
                this.cache.asMap().computeIfPresent(key, (k, variants) -> {
                    CacheEntry<?> existing = variants.get(fingerprint);

                    if (existing == null)
                        return variants;

                    refreshed.set(mergeHeaders(existing, new304Headers, revalidation));
                    return withVariant(variants, fingerprint, refreshed.get());
                });
            }
        } finally {
            lock.unlock();
        }

        return Optional.ofNullable(refreshed.get());
    }

    // ===== Internals =====

    /**
     * Tests whether an answer to a request for the given URL, sent at the given instant, may be
     * written to the cache: the request was sent after {@link #refusedThrough} and after the
     * URL's record in {@link #invalidatedAt}, if it has one.
     * <p>
     * Called under the read lock of {@link #dropLock}.
     *
     * @param url the canonical URL the answer is written under
     * @param sent the instant the request was sent
     * @return {@code true} if the request was sent after every invalidation covering {@code url}
     */
    private boolean sentAfterInvalidation(@NotNull String url, @NotNull Instant sent) {
        Instant invalidated = this.invalidatedAt.get(url);
        return sent.isAfter(this.refusedThrough) && (invalidated == null || sent.isAfter(invalidated));
    }

    /**
     * Folds every per-URL invalidation record into {@link #refusedThrough}, which becomes the
     * latest of itself, {@code now} and each record, and drops the records.
     * <p>
     * Called under the write lock of {@link #dropLock}.
     *
     * @param now the instant of the invalidation that folds the records
     */
    private void foldInvalidations(@NotNull Instant now) {
        Instant latest = now.isAfter(this.refusedThrough) ? now : this.refusedThrough;

        for (Instant invalidated : this.invalidatedAt.values()) {
            if (invalidated.isAfter(latest))
                latest = invalidated;
        }

        this.refusedThrough = latest;
        this.invalidatedAt.clear();
    }

    /**
     * Builds the bucket a write puts in the cache: a new map holding the given bucket's variants,
     * with {@code entry} under {@code fingerprint}.
     * <p>
     * The bucket in the cache is never modified, so Caffeine sees every change as a write and
     * recomputes the bucket's weight and lifetime from what it holds.
     *
     * @param variants the bucket currently in the cache, or {@code null} if there is none
     * @param fingerprint the Vary fingerprint to put {@code entry} under
     * @param entry the variant to add or replace
     * @return a new bucket holding {@code variants} and {@code entry}
     */
    private static @NotNull java.util.concurrent.ConcurrentMap<CacheKey.VaryFingerprint, CacheEntry<?>> withVariant(
        @Nullable java.util.concurrent.ConcurrentMap<CacheKey.VaryFingerprint, CacheEntry<?>> variants,
        @NotNull CacheKey.VaryFingerprint fingerprint,
        @NotNull CacheEntry<?> entry
    ) {
        java.util.concurrent.ConcurrentMap<CacheKey.VaryFingerprint, CacheEntry<?>> bucket = variants == null
            ? new ConcurrentHashMap<>()
            : new ConcurrentHashMap<>(variants);

        bucket.put(fingerprint, entry);
        return bucket;
    }

    /**
     * Renders a fingerprint as the key {@link #RECENCY} orders variants by when neither their
     * dates nor their receipts tell them apart: each header name the fingerprint holds, in
     * order, with the request's value of it, every name and value closed by a {@code NUL}, which
     * no header name or value carries.
     *
     * @param fingerprint the fingerprint a variant is held under
     * @return the fingerprint's headers and values as one string
     */
    private static @NotNull String ordering(@NotNull CacheKey.VaryFingerprint fingerprint) {
        StringBuilder ordering = new StringBuilder();
        fingerprint.values().forEach((name, value) -> ordering.append(name).append('\0').append(value).append('\0'));
        return ordering.toString();
    }

    /**
     * Applies the RFC 7234 §3 storage predicate to a decoded response.
     *
     * @param decoded the decoded response to evaluate
     * @return {@code true} if the response is eligible for caching
     */
    private static boolean shouldStore(@NotNull Response<?> decoded) {
        HttpMethod method = decoded.getRequest().getMethod();

        if (!method.isCacheable())
            return false;

        if (hasHeader(decoded.getHeaders(), CACHE_HIT_HEADER))
            return false;

        CacheControl cc = CacheControl.parseFromHeaders(decoded.getHeaders());

        if (cc.noStore())
            return false;

        int status = decoded.getStatus().getCode();

        if (DEFAULT_CACHEABLE_STATUSES.contains(status))
            return true;

        // Non-default-cacheable statuses are only stored if explicit freshness was advertised;
        // s-maxage binds only shared caches, so it does not count for this private one.
        return cc.maxAge().isPresent() || hasHeader(decoded.getHeaders(), "Expires");
    }

    /**
     * Tests whether a later request can be matched against the fingerprint of the request that
     * produced a response.
     * <p>
     * A fingerprint cannot be matched when it names {@code *}, which matches no request, or a
     * header whose value the origin received is not fixed by the request's own headers:
     * <ul>
     *   <li>{@code Cookie} with no value - the transport adds a {@code Cookie} from its cookie
     *       store to a request carrying none, and the store can change between two requests</li>
     *   <li>an {@linkplain NetworkDetails#isInternalHeader(String) internal header}, which
     *       carries each request's own sequence number and timings</li>
     * </ul>
     *
     * @param fingerprint the fingerprint of the request that produced a response
     * @return {@code true} if a later request can be matched against {@code fingerprint}
     */
    private static boolean isMatchable(@NotNull CacheKey.VaryFingerprint fingerprint) {
        for (Map.Entry<String, String> selecting : fingerprint.values().entrySet()) {
            String name = selecting.getKey();

            if ("*".equals(name) || NetworkDetails.isInternalHeader(name))
                return false;

            if ("cookie".equals(name) && selecting.getValue().isEmpty())
                return false;
        }

        return true;
    }

    /**
     * Returns a sanitized copy of the given response's headers with hop-by-hop and
     * transport-framing headers removed.
     *
     * @param decoded the decoded response whose headers should be sanitized
     * @return an unmodifiable case-insensitive header map with hop-by-hop and
     *         {@code Content-Encoding} / {@code Content-Length} entries removed
     */
    private static @NotNull ConcurrentMap<String, ConcurrentList<String>> stripTransportHeaders(@NotNull Response<?> decoded) {
        TreeMap<String, ConcurrentList<String>> sanitized = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        decoded.getHeaders().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);

            if (HOP_BY_HOP_HEADERS.contains(lower) || TRANSPORT_HEADERS.contains(lower))
                return;

            sanitized.put(name, values);
        });

        return Concurrent.newUnmodifiableTreeMap(String.CASE_INSENSITIVE_ORDER, sanitized);
    }

    /**
     * Tests whether a header of a {@code 304 Not Modified} response replaces the stored header
     * of the same name - every header does except hop-by-hop, transport-framing and internal
     * ones, and one without a value.
     *
     * @param name the header name
     * @param values the header's values
     * @return {@code true} if the header replaces the stored one
     */
    static boolean refreshesStoredHeader(@NotNull String name, @Nullable Collection<String> values) {
        String lower = name.toLowerCase(Locale.ROOT);

        return !HOP_BY_HOP_HEADERS.contains(lower)
            && !TRANSPORT_HEADERS.contains(lower)
            && !NetworkDetails.isInternalHeader(name)
            && values != null
            && !values.isEmpty();
    }

    /**
     * Merges the headers from a 304 response into an existing cached entry, producing a
     * fresh {@link CacheEntry} whose cached view exposes the merged headers while the
     * body bytes, status, and request are inherited from the existing entry.
     * <p>
     * The 304 is the new response for age purposes per
     * <a href="https://datatracker.ietf.org/doc/html/rfc7234#section-4.3.4">RFC 7234
     * §4.3.4</a>: the merged view reports the 304 exchange's network details, and the stored
     * {@code Age} is kept only when the 304 carries its own, so the refreshed entry's
     * {@linkplain Response.CachedImpl#currentAge(Instant) age} is counted from the revalidation.
     * <p>
     * {@link CachingFeignClient} answers the revalidation from the same merge, so the replay
     * matches the refreshed entry.
     *
     * @param existing the cached entry to refresh
     * @param new304Headers the headers from the 304 response
     * @param revalidation the network details of the {@code 304} exchange
     * @param <T> the decoded body type
     * @return a new {@code CacheEntry} with merged headers, the 304's network details and the
     *         existing body bytes
     * @see #refreshesStoredHeader(String, Collection)
     */
    static <T> @NotNull CacheEntry<T> mergeHeaders(
        @NotNull CacheEntry<T> existing,
        @NotNull Map<String, ? extends Collection<String>> new304Headers,
        @NotNull NetworkDetails revalidation
    ) {
        Response.CachedImpl<T> existingResponse = existing.response();
        TreeMap<String, ConcurrentList<String>> merged = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        merged.putAll(existingResponse.getHeaders());
        merged.remove("Age");

        for (Map.Entry<String, ? extends Collection<String>> entry : new304Headers.entrySet()) {
            Collection<String> values = entry.getValue();

            if (refreshesStoredHeader(entry.getKey(), values))
                merged.put(entry.getKey(), Concurrent.newUnmodifiableList(values));
        }

        ConcurrentMap<String, ConcurrentList<String>> mergedView = Concurrent.newUnmodifiableTreeMap(
            String.CASE_INSENSITIVE_ORDER,
            merged
        );

        return new CacheEntry<>(existingResponse.withHeaders(mergedView, revalidation), existing.body());
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

}
