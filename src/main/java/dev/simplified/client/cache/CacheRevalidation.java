package dev.simplified.client.cache;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.client.fetch.UrlFetcher;
import dev.simplified.client.response.ETag;
import dev.simplified.client.response.Response;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Transport-neutral helpers for RFC 7234 conditional revalidation.
 * <p>
 * Both {@link CachingFeignClient} (Feign transport) and
 * {@link UrlFetcher UrlFetcher} (Apache transport) share the
 * same logic for deciding whether a caller has already attached conditional headers and
 * for deriving {@code If-None-Match} / {@code If-Modified-Since} from a cached entry's
 * validators. This class is the single source of truth for that derivation; the
 * transport-specific code reuses these helpers and only the wire-format wrapping
 * (rebuilding a {@code feign.Request} vs attaching headers to an Apache {@code HttpGet})
 * stays per-transport.
 *
 * @see CachingFeignClient
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7234#section-4.3">RFC 7234 Section 4.3</a>
 */
@UtilityClass
public final class CacheRevalidation {

    /**
     * Returns whether the supplied request headers already carry an {@code If-None-Match} or
     * {@code If-Modified-Since} entry.
     * <p>
     * When the caller has already set a conditional header, the cache layer stays out of the
     * way and lets the caller drive the conditional exchange. The check is case-insensitive.
     *
     * @param headers the request headers to inspect
     * @return {@code true} if a conditional header is already present
     */
    public static boolean hasConditionalHeaders(@NotNull Map<String, Collection<String>> headers) {
        for (String name : headers.keySet()) {
            if (ETag.IF_NONE_MATCH_HEADER.equalsIgnoreCase(name))
                return true;
            if (ETag.IF_MODIFIED_SINCE_HEADER.equalsIgnoreCase(name))
                return true;
        }

        return false;
    }

    /**
     * Builds a mutable header map seeded from {@code source} with the cached entry's
     * validators attached as {@code If-None-Match} and/or {@code If-Modified-Since}.
     * <p>
     * If {@code cached} carries an {@link ETag}, an {@code If-None-Match} entry is added
     * with the tag rendered via {@link ETag#toHeaderValue()}. If {@code cached} carries a
     * {@code Last-Modified} response header, its value is copied verbatim into an
     * {@code If-Modified-Since} entry. The returned map is a fresh {@link HashMap} so the
     * caller may freely mutate it before sending.
     *
     * @param source the request headers to seed the result with
     * @param cached the cached entry whose validators drive the conditional derivation
     * @return a fresh header map with conditional validators attached when available
     */
    public static @NotNull Map<String, Collection<String>> buildConditionalHeaders(
        @NotNull Map<String, Collection<String>> source,
        @NotNull Response.CachedImpl<?> cached
    ) {
        Map<String, Collection<String>> headers = new HashMap<>(source);

        cached.getETag()
            .ifPresent(etag -> headers.put(ETag.IF_NONE_MATCH_HEADER, List.of(etag.toHeaderValue())));

        cached.getHeaders()
            .getOptional("Last-Modified")
            .filter(list -> !list.isEmpty())
            .map(List::getFirst)
            .ifPresent(lastMod -> headers.put(ETag.IF_MODIFIED_SINCE_HEADER, List.of(lastMod)));

        return headers;
    }

}
