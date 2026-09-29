package dev.simplified.client.cache;

import com.github.benmanes.caffeine.cache.Expiry;
import dev.simplified.client.response.Response;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.ConcurrentMap;

/**
 * Caffeine {@link Expiry} strategy for {@link ResponseCache} that computes per-entry TTL
 * from how long each cached variant can still answer a request, capped by a safety fallback
 * duration so that no entry can survive eternally.
 * <p>
 * For each {@link CacheKey.UrlKey} bucket, the expiry is set to the longest "living
 * duration" of any variant in the bucket. A variant lives while it can answer a request
 * without the origin, and then while it can still answer one through the origin:
 * <ul>
 *   <li><b>Reusable</b> - its {@link Response.CachedImpl#freshnessLifetime() freshness
 *       lifetime}, or nothing when it carries {@code no-cache}, which never lets a response be
 *       replayed without revalidation</li>
 *   <li><b>Stale</b> - the longer of its {@code stale-if-error} window, counted only when no
 *       directive {@linkplain Response.CachedImpl#mustRevalidate() requires it be revalidated},
 *       and the stale retention, counted only when it
 *       {@linkplain Response.CachedImpl#canRevalidate() carries a validator} a conditional
 *       request can revalidate it with</li>
 * </ul>
 * The sum is clamped to the safety fallback from {@code Timings#cacheSafetyFallback},
 * guaranteeing that even a response carrying {@code Cache-Control: immutable, max-age=99999999}
 * will eventually be evicted.
 * <p>
 * The stale retention keeps a variant carrying an {@code ETag} or {@code Last-Modified} past its
 * freshness, so the request after it went stale sends {@code If-None-Match} or
 * {@code If-Modified-Since} and a {@code 304 Not Modified} refreshes it rather than the body
 * being fetched again; a response with a validator but no freshness lifetime is kept for the
 * retention alone. A variant carrying no validator expires once its freshness lifetime and any
 * {@code stale-if-error} window it may be served under have passed, so one with neither expires
 * as it is written. The retention is {@code Timings#cacheStaleRetention}, and
 * {@link ResponseCache#DEFAULT_STALE_RETENTION_MILLIS} when none is given.
 * <p>
 * The lifetime is counted from the write that created or last replaced the bucket.
 * {@link ResponseCache} writes a bucket only as a whole, holding every variant, so the
 * lifetime is always computed over the populated bucket. A variant that arrived with an age
 * is held for that age past its staleness as well, since its freshness is counted from before
 * the write.
 * <p>
 * The safety cap is baked into {@link #expireAfterCreate(CacheKey.UrlKey, ConcurrentMap, long)}
 * rather than layered on top via {@code Caffeine#expireAfterWrite(...)} because Caffeine
 * rejects combining a custom {@code Expiry} with a fixed write/access duration at build
 * time. Reads do not extend the lifetime of a cached entry.
 *
 * @see ResponseCache
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7234#section-4.2">RFC 7234 §4.2 - Freshness</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7234#section-4.3">RFC 7234 §4.3 - Validation</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc5861">RFC 5861 - HTTP Cache-Control Extensions for Stale Content</a>
 */
public final class ResponseCacheExpiry implements Expiry<CacheKey.UrlKey, ConcurrentMap<CacheKey.VaryFingerprint, CacheEntry<?>>> {

    /**
     * The absolute upper bound on any cache entry's lifetime, regardless of response-advertised freshness.
     */
    private final @NotNull Duration safetyFallback;

    /**
     * How long a variant carrying a validator is kept past its freshness for a conditional
     * request to revalidate it.
     */
    private final @NotNull Duration staleRetention;

    /**
     * Constructs a new {@code ResponseCacheExpiry} with the given safety fallback, keeping a
     * variant carrying a validator for {@link ResponseCache#DEFAULT_STALE_RETENTION_MILLIS} past
     * its freshness.
     *
     * @param safetyFallback the absolute upper bound on any entry's lifetime
     */
    public ResponseCacheExpiry(@NotNull Duration safetyFallback) {
        this(safetyFallback, Duration.ofMillis(ResponseCache.DEFAULT_STALE_RETENTION_MILLIS));
    }

    /**
     * Constructs a new {@code ResponseCacheExpiry} with the given safety fallback and stale
     * retention.
     *
     * @param safetyFallback the absolute upper bound on any entry's lifetime
     * @param staleRetention how long a variant carrying a validator is kept past its freshness
     *                       for revalidation; zero or negative keeps none
     */
    public ResponseCacheExpiry(@NotNull Duration safetyFallback, @NotNull Duration staleRetention) {
        this.safetyFallback = safetyFallback;
        this.staleRetention = staleRetention.isNegative() ? Duration.ZERO : staleRetention;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Returns the longest living duration across all variants in the bucket, clamped to
     * the safety fallback.
     */
    @Override
    public long expireAfterCreate(
        @NotNull CacheKey.UrlKey key,
        @NotNull ConcurrentMap<CacheKey.VaryFingerprint, CacheEntry<?>> variants,
        long currentTime
    ) {
        Duration longest = variants.values().stream()
            .map(this::livingDuration)
            .max(Comparator.naturalOrder())
            .orElse(Duration.ZERO);

        Duration clamped = longest.compareTo(this.safetyFallback) < 0 ? longest : this.safetyFallback;
        long nanos = clamped.toNanos();

        // Caffeine requires a non-negative return; Duration.toNanos can saturate to
        // Long.MAX_VALUE for very large values, which Caffeine treats as "never expire".
        return Math.max(0L, nanos);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Recomputes the living duration on update, matching the behaviour of
     * {@link #expireAfterCreate(CacheKey.UrlKey, ConcurrentMap, long)}, so the bucket's
     * lifetime restarts from the replacement. An update is a write of a new bucket over a
     * live one: {@link ResponseCache#store} adding or replacing a Vary variant, or
     * {@link ResponseCache#updateOn304} refreshing one after a successful revalidation.
     */
    @Override
    public long expireAfterUpdate(
        @NotNull CacheKey.UrlKey key,
        @NotNull ConcurrentMap<CacheKey.VaryFingerprint, CacheEntry<?>> variants,
        long currentTime,
        long currentDuration
    ) {
        return this.expireAfterCreate(key, variants, currentTime);
    }

    /**
     * {@inheritDoc}
     * <p>
     * Reads do not extend the lifetime of a cached entry, so the current duration is
     * returned unchanged.
     */
    @Override
    public long expireAfterRead(
        @NotNull CacheKey.UrlKey key,
        @NotNull ConcurrentMap<CacheKey.VaryFingerprint, CacheEntry<?>> variants,
        long currentTime,
        long currentDuration
    ) {
        return currentDuration;
    }

    /**
     * Computes the living duration of a single variant: how long it can be replayed without
     * revalidation, plus the longer of the {@code stale-if-error} window it may be served under
     * and the stale retention it is kept for when it carries a validator.
     * <p>
     * A sum reaching past the safety fallback is answered as the safety fallback, which
     * {@link #expireAfterCreate(CacheKey.UrlKey, ConcurrentMap, long)} clamps to anyway.
     *
     * @param entry the cached entry whose response is inspected
     * @return the living duration of the variant
     */
    private @NotNull Duration livingDuration(@NotNull CacheEntry<?> entry) {
        Response.CachedImpl<?> cached = entry.response();
        Duration reusable = cached.cacheControl().noCache() ? Duration.ZERO : cached.freshnessLifetime();
        Duration staleOnError = cached.mustRevalidate() ? Duration.ZERO : Duration.ofSeconds(cached.staleIfError().orElse(0L));
        Duration revalidation = cached.canRevalidate() ? this.staleRetention : Duration.ZERO;
        Duration stale = staleOnError.compareTo(revalidation) < 0 ? revalidation : staleOnError;

        if (reusable.compareTo(this.safetyFallback) >= 0 || stale.compareTo(this.safetyFallback) >= 0)
            return this.safetyFallback;

        return reusable.plus(stale);
    }

}
