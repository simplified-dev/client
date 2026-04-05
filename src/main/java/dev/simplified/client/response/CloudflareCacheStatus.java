package dev.simplified.client.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Enumeration of Cloudflare CDN cache statuses, representing the caching disposition
 * of a resource as reported by the {@code CF-Cache-Status} response header.
 * <p>
 * Each constant carries a human-readable description (via {@code getDescription()}) explaining
 * the caching behavior. The {@link #of(String)} factory method performs a case-insensitive
 * lookup, falling back to {@link #UNKNOWN} when the header value is absent or unrecognized.
 * <p>
 * Typical usage is through {@link Response#getCloudflareCacheStatus()}, which extracts
 * the header value and resolves it to a constant automatically.
 *
 * @see <a href="https://developers.cloudflare.com/cache/concepts/cache-responses/">Cloudflare Cache Responses</a>
 * @see Response#getCloudflareCacheStatus()
 */
@Getter
@RequiredArgsConstructor
public enum CloudflareCacheStatus {

    /** The Cloudflare caching status of this resource could not be determined from the response headers. */
    UNKNOWN("The Cloudflare caching status of this resource could not be found."),

    /** Cloudflare generated a response indicating that the asset is not eligible for caching. */
    NONE("Cloudflare generated a response that denotes the asset is not eligible for caching."),

    /** The origin server instructed Cloudflare to bypass its cache via a {@code Cache-Control} header set to {@code no-cache}, {@code private}, or {@code max-age=0}. */
    BYPASS("The origin server instructed Cloudflare to bypass cache via a Cache-Control header set to no-cache,private, or max-age=0 even though Cloudflare originally preferred to cache the asset."),

    /** Cloudflare does not consider the asset eligible for caching, and no Cloudflare settings override this; the asset was fetched directly from the origin. */
    DYNAMIC("Cloudflare does not consider the asset eligible to cache and your Cloudflare settings do not explicitly instruct Cloudflare to cache the asset. Instead, the asset was requested from the origin web server."),

    /** The resource was found in Cloudflare's cache but had expired, so it was re-fetched from the origin. */
    EXPIRED("The resource was found in Cloudflare's cache but was expired and served from the origin web server."),

    /** The resource was served directly from Cloudflare's cache (cache hit). */
    HIT("The resource was found in Cloudflare's cache."),

    /** The resource was not found in Cloudflare's cache and was served from the origin (cache miss). */
    MISS("The resource was not found in Cloudflare's cache and was served from the origin web server."),

    /** The cached resource was stale but was successfully revalidated with the origin using conditional headers ({@code If-Modified-Since} or {@code If-None-Match}). */
    REVALIDATED("The resource is served from Cloudflare's cache but is stale. The resource was revalidated by either an If-Modified-Since header or an If-None-Match header."),

    /** The cached resource was expired and Cloudflare could not contact the origin, so the stale version was served. */
    STALE("The resource was served from Cloudflare's cache but was expired. Cloudflare could not contact the origin to retrieve an updated resource."),

    /** The cached resource was expired but is being served while the origin updates it in the background. */
    UPDATING("The resource was served from Cloudflare's cache and was expired, but the origin web server is updating the resource.");

    /** The HTTP response header key used by Cloudflare to communicate cache status. */
    public static final @NotNull String HEADER_KEY = "CF-Cache-Status";

    /** A human-readable description of the caching behavior represented by this constant. */
    private final @NotNull String description;

    /**
     * Resolves a Cloudflare cache status from the given header value string.
     * <p>
     * Performs a case-insensitive comparison against all defined constants and
     * returns the matching one. If no match is found, {@link #UNKNOWN} is returned
     * rather than throwing an exception, making this method safe for use with
     * arbitrary or missing header values.
     *
     * @param name the {@code CF-Cache-Status} header value to look up
     * @return the matching {@link CloudflareCacheStatus}, or {@link #UNKNOWN} if
     *         no constant matches the provided name
     */
    public static @NotNull CloudflareCacheStatus of(@NotNull String name) {
        return Arrays.stream(values())
            .filter(value -> value.name().equalsIgnoreCase(name))
            .findFirst()
            .orElse(UNKNOWN);
    }

}
