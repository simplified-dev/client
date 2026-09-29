package dev.simplified.client.cache;

import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.client.decoder.InternalResponseDecoder;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import feign.Client;
import feign.Request;
import feign.hc5.ApacheHttp5Client;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Feign {@link Client} wrapper that transparently serves RFC 7234 cache hits, attaches
 * conditional validators on stale revalidation, and invalidates cached entries after
 * successful unsafe-method exchanges.
 * <p>
 * Sits between Feign and the underlying transport, typically an {@link ApacheHttp5Client},
 * so that:
 * <ul>
 *   <li>On a cache hit that {@linkplain Response.CachedImpl#canServeWithoutRevalidation(Instant)
 *       may be served without revalidation} - fresh, and carrying no {@code no-cache} - a
 *       synthesized {@link feign.Response} is returned
 *       immediately without touching the network. Feign's response interceptors and decoder
 *       then run on the synthesized response, re-decoding the cached raw bytes into a fresh
 *       {@link Response.Impl} and updating
 *       {@link ResponseCache#recordLastResponse(Response)} as a live response does.</li>
 *   <li>On any other cache hit - a stale one, or one carrying {@code no-cache} - with a
 *       validator, {@code If-None-Match} and/or
 *       {@code If-Modified-Since} are attached to a copy of the original request before
 *       dispatching to the delegate. If the server replies with {@code 304 Not Modified},
 *       {@link ResponseCache#updateOn304} replaces the cached entry, addressed by the
 *       {@link CacheKey.VaryFingerprint} of the request's headers, with one carrying the
 *       304's headers and aged from the 304 exchange, restarting its bucket's lifetime, per
 *       <a href="https://datatracker.ietf.org/doc/html/rfc7234#section-4.3.4">RFC 7234
 *       §4.3.4</a>, and a synthesized replay of the cached bytes is returned, carrying the
 *       refreshed headers. A stale entry is held only while its bucket lives, which
 *       {@link ResponseCacheExpiry} ends once its freshness lifetime plus
 *       {@code stale-if-error} window has passed since it was stored.</li>
 *   <li>On a stale cache hit where the origin returns {@code 5xx} within the entry's
 *       {@code stale-if-error} window, the cached bytes are served in place of the error
 *       response per <a href="https://datatracker.ietf.org/doc/html/rfc5861#section-4">RFC
 *       5861 §4</a>, unless the entry carries {@code must-revalidate},
 *       {@code proxy-revalidate} or {@code no-cache}, whose
 *       {@linkplain Response.CachedImpl#canServeStaleOnError(Instant) stale replay is refused}
 *       and the error response is returned.</li>
 *   <li>On a successful unsafe method ({@code POST}, {@code PUT}, {@code PATCH},
 *       {@code DELETE}), the cache is invalidated for the target URL plus any
 *       {@code Location} and {@code Content-Location} redirects.</li>
 * </ul>
 * <p>
 * A request is matched to a cached variant by its own headers, as Feign built it: the
 * contract's headers and the configured static and dynamic headers the client's Feign target
 * adds before the request reaches this class. The delegate sends those headers, and
 * {@link InternalResponseDecoder} stores the answer under the same request's headers, so a
 * response's {@code Vary} is matched against the values the origin received (see
 * {@link ResponseCache#lookup}).
 * <p>
 * Every response the delegate returns carries its round trip: any
 * {@linkplain NetworkDetails#isInternalHeader(String) internal header} in it is dropped, and the
 * instant before the request was handed to the delegate and the instant the delegate returned
 * are added to the response's headers as {@link NetworkDetails#REQUEST_START} and
 * {@link NetworkDetails#RESPONSE_RECEIVED}. {@link NetworkDetails} reads both there, so a stored
 * response's {@link Response.CachedImpl#currentAge(Instant) age} and the request start
 * {@link ResponseCache#store} compares with its last drop are measured from them.
 * <p>
 * Storage is not handled here. {@link InternalResponseDecoder} offers each buffered response it
 * decodes to {@link ResponseCache#store}, which keeps the raw body bytes; a replay is decoded
 * from them afresh.
 * <p>
 * Synthesized cache-hit responses carry two non-internal marker headers:
 * {@link ResponseCache#CACHE_HIT_HEADER} for fresh and 304 replays, and
 * {@link ResponseCache#CACHE_STALE_HEADER} for stale-if-error replays. They are
 * preserved by {@link Response#getHeaders(Map)} and visible to application code, letting
 * observability consumers distinguish replayed responses from live exchanges. A 304 replay
 * also carries the internal {@link ResponseCache#REVALIDATED_HEADER}, naming the headers the
 * 304 supplied, so a response interceptor can tell what the server sent from what the cache
 * stored.
 *
 * @see ResponseCache
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7234">RFC 7234 - HTTP/1.1 Caching</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc5861">RFC 5861 - HTTP Cache-Control Extensions for Stale Content</a>
 */
@RequiredArgsConstructor
public final class CachingFeignClient implements Client {

    /**
     * The underlying Feign client to which non-short-circuited requests are dispatched.
     */
    private final @NotNull Client delegate;

    /**
     * The shared response cache used for lookups, stores via decoder, invalidation, and 304 merging.
     */
    private final @NotNull ResponseCache responseCache;

    @Override
    public feign.Response execute(@NotNull Request request, @NotNull Request.Options options) throws IOException {
        HttpMethod method = HttpMethod.of(request.httpMethod().name());

        if (method.isCacheable() && !CacheRevalidation.hasConditionalHeaders(request.headers())) {
            Optional<CacheEntry<?>> lookup = this.responseCache.lookup(method, request.url(), request.headers());

            if (lookup.isPresent()) {
                feign.Response shortCircuit = this.serveFromCache(request, options, method, lookup.get());

                if (shortCircuit != null)
                    return shortCircuit;
            }
        }

        feign.Response response = this.exchange(request, options);

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
    @Nullable feign.Response serveFromCache(
        @NotNull Request request,
        @NotNull Request.Options options,
        @NotNull HttpMethod method,
        @NotNull CacheEntry<?> entry
    ) throws IOException {
        Instant now = Instant.now();
        Response.CachedImpl<?> cached = entry.response();

        if (cached.canServeWithoutRevalidation(now))
            return this.synthesizeFreshHit(request, entry, now);

        if (!cached.canRevalidate())
            return null;

        Request conditional = this.withConditionalHeaders(request, cached);
        feign.Response response = this.exchange(conditional, options);

        if (response.status() == 304) {
            NetworkDetails revalidation = new NetworkDetails(response);
            CacheKey.UrlKey key = CacheKey.UrlKey.of(method, request.url());
            CacheKey.VaryFingerprint fingerprint = CacheKey.VaryFingerprint.of(cached.varyHeaderNames(), request.headers());
            this.responseCache.updateOn304(key, fingerprint, response.headers(), revalidation);

            feign.Util.ensureClosed(response.body());

            return this.synthesizeRevalidatedHit(request, entry, now, response.headers(), revalidation);
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
     * The replay answers the original request and carries the cached headers, {@code Age},
     * {@link ResponseCache#CACHE_HIT_HEADER}, and {@link NetworkDetails#REQUEST_START} and
     * {@link NetworkDetails#RESPONSE_RECEIVED} both set to {@code now}, so
     * {@link NetworkDetails#NetworkDetails(feign.Response)} reports a zero round trip.
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
        return this.synthesize(originalRequest, entry, now, ageSeconds, false, null);
    }

    /**
     * Builds the synthetic replay answering a {@code 304 Not Modified} revalidation of the given
     * cached entry.
     * <p>
     * The replay carries the entry's headers refreshed by the 304's and its age counted from
     * the 304 exchange, the same merge {@link ResponseCache#updateOn304} stores, plus
     * {@link ResponseCache#REVALIDATED_HEADER} naming each header the 304 supplied.
     *
     * @param originalRequest the original request
     * @param entry the cached entry the 304 revalidated
     * @param now the synthesized timestamp
     * @param notModifiedHeaders the headers of the {@code 304} response
     * @param revalidation the network details of the {@code 304} exchange
     * @return the synthesized revalidation replay
     */
    private @NotNull feign.Response synthesizeRevalidatedHit(
        @NotNull Request originalRequest,
        @NotNull CacheEntry<?> entry,
        @NotNull Instant now,
        @NotNull Map<String, Collection<String>> notModifiedHeaders,
        @NotNull NetworkDetails revalidation
    ) {
        CacheEntry<?> refreshed = ResponseCache.mergeHeaders(entry, notModifiedHeaders, revalidation);
        List<String> revalidated = new ArrayList<>(notModifiedHeaders.size());

        notModifiedHeaders.forEach((name, values) -> {
            if (ResponseCache.refreshesStoredHeader(name, values))
                revalidated.add(name);
        });

        long ageSeconds = Math.max(0L, refreshed.response().currentAge(now).getSeconds());
        return this.synthesize(originalRequest, refreshed, now, ageSeconds, false, revalidated);
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
        return this.synthesize(originalRequest, entry, now, ageSeconds, true, null);
    }

    /**
     * Core synthesis helper shared by the fresh-hit, revalidation and stale-hit paths.
     *
     * @param originalRequest the original request that was short-circuited
     * @param entry the cached entry whose bytes and headers will be served
     * @param now the timestamp for request-start and response-received headers
     * @param ageSeconds the computed {@code Age} value to advertise
     * @param servedStale whether this is a stale-if-error replay
     * @param revalidated the names of the headers a {@code 304} supplied, or {@code null} for a
     *                    replay no revalidation answered
     * @return the synthesized feign response
     */
    private @NotNull feign.Response synthesize(
        @NotNull Request originalRequest,
        @NotNull CacheEntry<?> entry,
        @NotNull Instant now,
        long ageSeconds,
        boolean servedStale,
        @Nullable List<String> revalidated
    ) {
        Response.CachedImpl<?> cached = entry.response();
        Map<String, Collection<String>> responseHeaders = buildResponseHeaders(cached, now, ageSeconds, servedStale, revalidated);

        return feign.Response.builder()
            .request(originalRequest)
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
     * Builds a copy of the given request with {@code If-None-Match} and/or
     * {@code If-Modified-Since} attached from the cached variant's validators.
     * <p>
     * Delegates the header derivation to {@link CacheRevalidation#buildConditionalHeaders}
     * and rebuilds a {@link Request} around the resulting map. The Feign transport requires
     * the wire-format wrapping; transport-neutral callers consume the bare map directly.
     *
     * @param request the original request
     * @param cached the cached variant carrying the validators
     * @return a copy of the request with conditional headers attached
     */
    private @NotNull Request withConditionalHeaders(@NotNull Request request, @NotNull Response.CachedImpl<?> cached) {
        Map<String, Collection<String>> headers = CacheRevalidation.buildConditionalHeaders(request.headers(), cached);

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
     * Sends a request through the delegate and records the round trip on its response.
     * <p>
     * Every {@linkplain NetworkDetails#isInternalHeader(String) internal header} in the
     * delegate's response is dropped: only this client sets them, and an origin or proxy that
     * echoes the ones the client sends must not stand in for its round trip. The instant before
     * the delegate is called and the instant it returns are then added to the response's headers
     * as {@link NetworkDetails#REQUEST_START} and {@link NetworkDetails#RESPONSE_RECEIVED}. The
     * request start is recorded on the response rather than the request because Feign rebuilds
     * the response it is handed around the request it built.
     *
     * @param request the request to send
     * @param options the Feign request options
     * @return the delegate's response, carrying its round trip
     * @throws IOException if the delegate fails
     */
    private @NotNull feign.Response exchange(@NotNull Request request, @NotNull Request.Options options) throws IOException {
        Instant sent = Instant.now();
        feign.Response response = this.delegate.execute(request, options);
        Map<String, Collection<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        response.headers().forEach((name, values) -> {
            if (!NetworkDetails.isInternalHeader(name))
                headers.put(name, values);
        });

        headers.put(NetworkDetails.REQUEST_START, List.of(sent.toString()));
        headers.put(NetworkDetails.RESPONSE_RECEIVED, List.of(Instant.now().toString()));

        return response.toBuilder().headers(headers).build();
    }

    /**
     * Builds the response headers for a synthesized cache-hit response by copying the
     * cached variant's stored headers and adding the cache marker, {@code Age}, and the
     * {@code X-Internal-Request-Start} and {@code X-Internal-Response-Received} timestamps.
     *
     * @param cached the cached variant whose headers will be replayed
     * @param now the synthesized request-start and response-received timestamp
     * @param ageSeconds the computed cache age in seconds
     * @param servedStale whether to include the stale-served marker
     * @param revalidated the names {@link ResponseCache#REVALIDATED_HEADER} carries, or
     *                    {@code null} to omit it
     * @return the response headers for the synthetic response
     */
    private static @NotNull Map<String, Collection<String>> buildResponseHeaders(
        @NotNull Response.CachedImpl<?> cached,
        @NotNull Instant now,
        long ageSeconds,
        boolean servedStale,
        @Nullable List<String> revalidated
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

        if (revalidated != null)
            headers.put(ResponseCache.REVALIDATED_HEADER, revalidated);

        headers.put(NetworkDetails.REQUEST_START, List.of(now.toString()));
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
