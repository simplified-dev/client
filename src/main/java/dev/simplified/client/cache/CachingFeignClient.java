package dev.simplified.client.cache;

import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.ETag;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import feign.Client;
import feign.Request;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Feign {@link Client} wrapper that transparently serves RFC 7234 cache hits, attaches
 * conditional validators on stale revalidation, and invalidates cached entries after
 * successful unsafe-method exchanges.
 * <p>
 * Sits between Feign's decoder pipeline and the underlying
 * {@link feign.httpclient.ApacheHttpClient}, so that:
 * <ul>
 *   <li>On a fresh cache hit, a synthesized {@link feign.Response} is returned
 *       immediately without touching the network. Feign's decoder pipeline then runs on
 *       the synthesized response, re-decoding the cached raw bytes into a fresh
 *       {@link Response.Impl} and updating
 *       {@link ResponseCache#recordLastResponse(Response)} naturally.</li>
 *   <li>On a stale cache hit with a validator, {@code If-None-Match} and/or
 *       {@code If-Modified-Since} are attached to a copy of the original request before
 *       dispatching to the delegate. If the server replies with {@code 304 Not Modified},
 *       the cached entry is refreshed in place per
 *       <a href="https://datatracker.ietf.org/doc/html/rfc7234#section-4.3.4">RFC 7234
 *       §4.3.4</a> and a synthesized replay of the cached bytes is returned.</li>
 *   <li>On a stale cache hit where the origin returns {@code 5xx} within the entry's
 *       {@code stale-if-error} window, the cached bytes are served in place of the error
 *       response per <a href="https://datatracker.ietf.org/doc/html/rfc5861#section-4">RFC
 *       5861 §4</a>.</li>
 *   <li>On a successful unsafe method ({@code POST}, {@code PUT}, {@code PATCH},
 *       {@code DELETE}), the cache is invalidated for the target URL plus any
 *       {@code Location} and {@code Content-Location} redirects.</li>
 * </ul>
 * <p>
 * Storage of fresh responses is handled by
 * {@link dev.simplified.client.decoder.InternalResponseDecoder} after decoding, not here,
 * so that only responses that successfully passed the decoder are ever stored and so that
 * the stored {@link Response.Impl} carries its decoded body alongside the raw bytes for
 * direct observability.
 * <p>
 * Synthesized cache-hit responses carry two non-internal marker headers:
 * {@link ResponseCache#CACHE_HIT_HEADER} for fresh and 304 replays, and
 * {@link ResponseCache#CACHE_STALE_HEADER} for stale-if-error replays. They are
 * preserved by {@link Response#getHeaders(Map)} and visible to application code, letting
 * observability consumers distinguish replayed responses from live exchanges.
 *
 * @see ResponseCache
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7234">RFC 7234 - HTTP/1.1 Caching</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc5861">RFC 5861 - HTTP Cache-Control Extensions for Stale Content</a>
 */
@RequiredArgsConstructor
public final class CachingFeignClient implements Client {

    /** The underlying Feign client to which non-short-circuited requests are dispatched. */
    private final @NotNull Client delegate;

    /** The shared response cache used for lookups, stores via decoder, invalidation, and 304 merging. */
    private final @NotNull ResponseCache responseCache;

    @Override
    public feign.Response execute(@NotNull Request request, @NotNull Request.Options options) throws IOException {
        HttpMethod method = HttpMethod.of(request.httpMethod().name());

        if (method.isCacheable() && !hasConditionalHeaders(request.headers())) {
            Optional<CacheEntry<?>> lookup = this.responseCache.lookup(method, request.url(), request.headers());

            if (lookup.isPresent()) {
                feign.Response shortCircuit = this.serveFromCache(request, options, method, lookup.get());

                if (shortCircuit != null)
                    return shortCircuit;
            }
        }

        feign.Response response = this.delegate.execute(request, options);

        if (!method.isSafe() && isSuccessOrRedirect(response.status()))
            this.invalidateAfterMutation(request, response);

        return response;
    }

    // ===== Cache-hit paths =====

    /**
     * Attempts to serve the request from the given cached entry, returning a synthesized
     * {@link feign.Response} on success or {@code null} if the caller should fall through
     * to the delegate.
     *
     * @param request the original request
     * @param options the Feign request options (used when dispatching a revalidation)
     * @param method the HTTP method of the request
     * @param entry the cached entry selected by {@link ResponseCache#lookup}
     * @return the synthesized response for a fresh / 304 / stale-if-error path, or
     *         {@code null} if the caller should proceed to the delegate
     * @throws IOException if the delegate fails during a stale revalidation
     */
    private @Nullable feign.Response serveFromCache(
        @NotNull Request request,
        @NotNull Request.Options options,
        @NotNull HttpMethod method,
        @NotNull CacheEntry<?> entry
    ) throws IOException {
        Instant now = Instant.now();
        Response.CachedImpl<?> cached = entry.response();

        if (cached.isFresh(now))
            return this.synthesizeFreshHit(request, entry, now);

        if (!cached.canRevalidate())
            return null;

        Request conditional = this.withConditionalHeaders(request, cached);
        feign.Response response = this.delegate.execute(conditional, options);

        if (response.status() == 304) {
            CacheKey.UrlKey key = CacheKey.UrlKey.of(method, request.url());
            CacheKey.VaryFingerprint fingerprint = CacheKey.VaryFingerprint.of(cached.varyHeaderNames(), request.headers());
            this.responseCache.updateOn304(key, fingerprint, response.headers());

            feign.Util.ensureClosed(response.body());

            return this.synthesizeFreshHit(request, entry, now);
        }

        if (isServerError(response.status()) && cached.canServeStaleOnError(now)) {
            feign.Util.ensureClosed(response.body());
            return this.synthesizeStaleHit(request, entry, now);
        }

        return response;
    }

    /**
     * Builds a synthetic {@link feign.Response} that replays the given cached entry's
     * body and headers without consulting the network.
     * <p>
     * The synthetic request carries the original request URL, method, and caller headers
     * plus an {@code X-Internal-Request-Start} timestamp set to {@code now}; zero-length
     * DNS, TCP, and TLS stopwatches are stamped alongside so that
     * {@link NetworkDetails#NetworkDetails(feign.Response)} reports a zero round-trip
     * duration instead of reading {@link Instant#EPOCH} defaults. The synthetic response
     * carries the cached headers, {@code Age}, {@link ResponseCache#CACHE_HIT_HEADER},
     * and an {@code X-Internal-Response-Received} timestamp matching the request start.
     *
     * @param originalRequest the original request being short-circuited
     * @param entry the cached entry whose bytes will be served
     * @param now the synthesized start/end timestamp
     * @return the synthesized response
     */
    private @NotNull feign.Response synthesizeFreshHit(
        @NotNull Request originalRequest,
        @NotNull CacheEntry<?> entry,
        @NotNull Instant now
    ) {
        long ageSeconds = Math.max(0L, entry.response().currentAge(now).getSeconds());
        return this.synthesize(originalRequest, entry, now, ageSeconds, false);
    }

    /**
     * Builds a synthetic stale-if-error replay for the given cached entry.
     * <p>
     * Identical to {@link #synthesizeFreshHit(Request, CacheEntry, Instant)} except
     * that {@link ResponseCache#CACHE_STALE_HEADER} is added to the response headers so
     * observability callers can distinguish a stale replay from a fresh hit.
     *
     * @param originalRequest the original request
     * @param entry the cached entry to replay
     * @param now the synthesized timestamp
     * @return the synthesized stale replay response
     */
    private @NotNull feign.Response synthesizeStaleHit(
        @NotNull Request originalRequest,
        @NotNull CacheEntry<?> entry,
        @NotNull Instant now
    ) {
        long ageSeconds = Math.max(0L, entry.response().currentAge(now).getSeconds());
        return this.synthesize(originalRequest, entry, now, ageSeconds, true);
    }

    /**
     * Core synthesis helper shared by fresh-hit and stale-hit paths.
     *
     * @param originalRequest the original request that was short-circuited
     * @param entry the cached entry whose bytes and headers will be served
     * @param now the timestamp for request-start and response-received headers
     * @param ageSeconds the computed {@code Age} value to advertise
     * @param servedStale whether this is a stale-if-error replay
     * @return the synthesized feign response
     */
    private @NotNull feign.Response synthesize(
        @NotNull Request originalRequest,
        @NotNull CacheEntry<?> entry,
        @NotNull Instant now,
        long ageSeconds,
        boolean servedStale
    ) {
        Response.CachedImpl<?> cached = entry.response();
        Map<String, Collection<String>> responseHeaders = buildResponseHeaders(cached, now, ageSeconds, servedStale);
        Request syntheticRequest = buildSyntheticRequest(originalRequest, now);

        return feign.Response.builder()
            .request(syntheticRequest)
            .status(cached.getStatus().getCode())
            .reason(cached.getStatus().getMessage())
            .headers(responseHeaders)
            .body(entry.body())
            .build();
    }

    // ===== Invalidation =====

    /**
     * Invalidates cached entries for the request URL plus any {@code Location} and
     * {@code Content-Location} redirects advertised by the response, per RFC 7234 §4.4.
     *
     * @param request the mutating request
     * @param response the mutating response
     */
    private void invalidateAfterMutation(@NotNull Request request, @NotNull feign.Response response) {
        this.responseCache.invalidate(request.url());

        extractFirstHeader(response.headers(), "Location").ifPresent(this.responseCache::invalidate);
        extractFirstHeader(response.headers(), "Content-Location").ifPresent(this.responseCache::invalidate);
    }

    // ===== Header helpers =====

    /**
     * Returns {@code true} if the caller has already set {@code If-None-Match} or
     * {@code If-Modified-Since}, in which case the cache should stay out of the way and
     * let the caller drive the conditional exchange.
     *
     * @param headers the request headers
     * @return {@code true} if a conditional header is already present
     */
    private static boolean hasConditionalHeaders(@NotNull Map<String, Collection<String>> headers) {
        for (String name : headers.keySet()) {
            if (ETag.IF_NONE_MATCH_HEADER.equalsIgnoreCase(name))
                return true;
            if ("If-Modified-Since".equalsIgnoreCase(name))
                return true;
        }

        return false;
    }

    /**
     * Builds a copy of the given request with {@code If-None-Match} and/or
     * {@code If-Modified-Since} attached from the cached variant's validators.
     *
     * @param request the original request
     * @param cached the cached variant carrying the validators
     * @return a copy of the request with conditional headers attached
     */
    private @NotNull Request withConditionalHeaders(@NotNull Request request, @NotNull Response.CachedImpl<?> cached) {
        Map<String, Collection<String>> headers = new HashMap<>(request.headers());

        cached.getETag().ifPresent(etag -> headers.put(ETag.IF_NONE_MATCH_HEADER, List.of(etag.toHeaderValue())));

        cached.getHeaders()
            .getOptional("Last-Modified")
            .filter(list -> !list.isEmpty())
            .map(List::getFirst)
            .ifPresent(lastMod -> headers.put("If-Modified-Since", List.of(lastMod)));

        return Request.create(
            request.httpMethod(),
            request.url(),
            headers,
            request.body(),
            request.charset(),
            request.requestTemplate()
        );
    }

    /**
     * Builds a synthetic {@link feign.Request} carrying zero-duration timing headers so
     * that {@link NetworkDetails#NetworkDetails(feign.Response)} reports a zero round-trip
     * on cache hits rather than reading {@link Instant#EPOCH} defaults.
     *
     * @param original the original request to copy headers and URL from
     * @param now the timestamp to stamp on all timing headers
     * @return a new feign.Request with timing headers stamped
     */
    private static @NotNull Request buildSyntheticRequest(@NotNull Request original, @NotNull Instant now) {
        List<String> nowList = List.of(now.toString());
        Map<String, Collection<String>> headers = new HashMap<>(original.headers());

        headers.put(NetworkDetails.REQUEST_START, nowList);
        headers.put(NetworkDetails.DNS_START, nowList);
        headers.put(NetworkDetails.DNS_END, nowList);
        headers.put(NetworkDetails.TCP_CONNECT_START, nowList);
        headers.put(NetworkDetails.TCP_CONNECT_END, nowList);
        headers.put(NetworkDetails.TLS_HANDSHAKE_START, nowList);
        headers.put(NetworkDetails.TLS_HANDSHAKE_END, nowList);

        return Request.create(
            original.httpMethod(),
            original.url(),
            headers,
            original.body(),
            original.charset(),
            original.requestTemplate()
        );
    }

    /**
     * Builds the response headers for a synthesized cache-hit response by copying the
     * cached variant's stored headers and adding the cache marker, {@code Age}, and
     * {@code X-Internal-Response-Received} timestamp.
     *
     * @param cached the cached variant whose headers will be replayed
     * @param now the synthesized response-received timestamp
     * @param ageSeconds the computed cache age in seconds
     * @param servedStale whether to include the stale-served marker
     * @return the response headers for the synthetic response
     */
    private static @NotNull Map<String, Collection<String>> buildResponseHeaders(
        @NotNull Response.CachedImpl<?> cached,
        @NotNull Instant now,
        long ageSeconds,
        boolean servedStale
    ) {
        TreeMap<String, Collection<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        // The cached header lists are already unmodifiable ConcurrentLists; the caller
        // (Feign's response builder) consumes them read-only, so reuse the references
        // directly instead of allocating a fresh ArrayList per header.
        headers.putAll(cached.getHeaders());

        headers.put("Age", List.of(Long.toString(ageSeconds)));
        headers.put(ResponseCache.CACHE_HIT_HEADER, List.of("true"));

        if (servedStale)
            headers.put(ResponseCache.CACHE_STALE_HEADER, List.of("true"));

        headers.put(NetworkDetails.RESPONSE_RECEIVED, List.of(now.toString()));

        return headers;
    }

    // ===== Small utilities =====

    /**
     * Case-insensitively finds the first value of a named header.
     *
     * @param headers the header map
     * @param name the header name
     * @return the first value, or {@link Optional#empty()} if absent
     */
    private static @NotNull Optional<String> extractFirstHeader(@NotNull Map<String, Collection<String>> headers, @NotNull String name) {
        for (Map.Entry<String, Collection<String>> entry : headers.entrySet()) {
            if (!name.equalsIgnoreCase(entry.getKey()))
                continue;

            Collection<String> values = entry.getValue();

            if (values == null || values.isEmpty())
                return Optional.empty();

            return Optional.ofNullable(values.iterator().next());
        }

        return Optional.empty();
    }

    /**
     * Returns {@code true} for 5xx responses (including vendor-specific 494-599 ranges).
     *
     * @param status the HTTP status code
     * @return {@code true} if the status represents a server-side error
     */
    private static boolean isServerError(int status) {
        return status >= 500 && status < 600;
    }

    /**
     * Returns {@code true} if the status is in the 2xx or 3xx range, which per
     * RFC 7234 §4.4 triggers cache invalidation on unsafe methods.
     *
     * @param status the HTTP status code
     * @return {@code true} if the response is a success or redirect
     */
    private static boolean isSuccessOrRedirect(int status) {
        return status >= 200 && status < 400;
    }

}
