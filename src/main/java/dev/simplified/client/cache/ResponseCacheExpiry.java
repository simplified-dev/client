package dev.simplified.client.cache;

import com.github.benmanes.caffeine.cache.Expiry;
import dev.simplified.client.response.Response;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.ConcurrentMap;

/**
 * Caffeine {@link Expiry} strategy for {@link ResponseCache} that computes per-entry TTL
 * from the RFC 7234 freshness lifetime and stale-if-error window of each cached variant,
 * capped by a safety fallback duration so that no entry can survive eternally.
 * <p>
 * For each {@link CacheKey.UrlKey} bucket, the expiry is set to the longest "living
 * duration" of any variant in the bucket - that is, the freshness lifetime plus the
 * stale-if-error window. This is then clamped to the safety fallback from
 * {@code Timings#cacheSafetyFallback}, guaranteeing that even a response carrying
 * {@code Cache-Control: immutable, max-age=99999999} will eventually be evicted.
 * <p>
 * The safety cap is baked into {@link #expireAfterCreate(CacheKey.UrlKey, ConcurrentMap, long)}
 * rather than layered on top via {@code Caffeine#expireAfterWrite(...)} because Caffeine
 * rejects combining a custom {@code Expiry} with a fixed write/access duration at build
 * time. Reads do not extend the lifetime of a cached entry.
 *
 * @see ResponseCache
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7234#section-4.2">RFC 7234 §4.2 - Freshness</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc5861">RFC 5861 - HTTP Cache-Control Extensions for Stale Content</a>
 */
@RequiredArgsConstructor
public final class ResponseCacheExpiry implements Expiry<CacheKey.UrlKey, ConcurrentMap<CacheKey.VaryFingerprint, CacheEntry<?>>> {

    /** The absolute upper bound on any cache entry's lifetime, regardless of response-advertised freshness. */
    private final @NotNull Duration safetyFallback;

    /**
     * {@inheritDoc}
     * <p>
     * Returns the longest living duration across all variants in the bucket, clamped to
     * the safety fallback. The living duration of a variant is
     * {@code freshnessLifetime + stale-if-error}.
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
     * {@link #expireAfterCreate(CacheKey.UrlKey, ConcurrentMap, long)}. Updates occur
     * when a new Vary variant is added to an existing bucket or when {@code updateOn304}
     * refreshes the entry after a successful revalidation.
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
     * Computes the living duration of a single variant as
     * {@code freshnessLifetime + staleIfError}. Variants without a stale-if-error window
     * expire at the end of their freshness lifetime.
     *
     * @param entry the cached entry whose response is inspected
     * @return the living duration of the variant
     */
    private @NotNull Duration livingDuration(@NotNull CacheEntry<?> entry) {
        Response.CachedImpl<?> cached = entry.response();
        return cached.freshnessLifetime().plusSeconds(cached.staleIfError().orElse(0L));
    }

}
