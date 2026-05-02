package dev.simplified.client.cache;

import dev.simplified.client.response.Response;
import org.jetbrains.annotations.NotNull;

/**
 * Storage tuple pairing a {@link Response.CachedImpl} envelope with the captured body bytes
 * that were used to decode it.
 * <p>
 * The bytes live alongside the response in the {@link ResponseCache}'s inner Vary map so
 * that {@link CachingFeignClient} can synthesize a replay {@link feign.Response} on cache
 * hits without round-tripping through {@code Response} accessors. The response's lazy body
 * supplier already closes over its own copy of these bytes, so replay reads are independent
 * of the supplier's memoization state.
 *
 * @param response the cached response envelope (status, headers, decoded body, RFC 7234
 *                 freshness/revalidation accessors)
 * @param body the captured raw body bytes used at decode time
 * @param <T> the decoded body type of {@code response}
 */
public record CacheEntry<T>(@NotNull Response.CachedImpl<T> response, byte @NotNull [] body) { }
