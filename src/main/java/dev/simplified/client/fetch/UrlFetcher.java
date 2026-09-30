package dev.simplified.client.fetch;

import com.google.gson.Gson;
import dev.simplified.annotations.Getter;
import dev.simplified.client.Client;
import dev.simplified.client.cache.CacheEntry;
import dev.simplified.client.cache.CacheKey;
import dev.simplified.client.cache.CacheRevalidation;
import dev.simplified.client.cache.ResponseCache;
import dev.simplified.client.exception.ErrorContext;
import dev.simplified.client.exception.UrlFetchException;
import dev.simplified.client.factory.ApacheClientFactory;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.request.Request;
import dev.simplified.client.response.HttpState;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import dev.simplified.util.time.Stopwatch;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Standalone HTTP fetcher for ad-hoc URLs that reuses the {@link Client} infrastructure
 * (Apache HTTP pool, timed sockets, {@link ResponseCache}, {@link RateLimitManager}) without
 * the contract-based Feign discipline that {@code Client} requires.
 * <p>
 * Built from a {@link UrlFetcherConfig} via {@link #create(UrlFetcherConfig)}, the fetcher
 * exposes a small typed API ({@link #get(URI)}, {@link #get(URI, Class)}, {@link #bytes(URI)},
 * each with an overload naming its own body cap) that returns the same {@link Response Response&lt;T&gt;} envelopes used elsewhere in the
 * library, enabling observability code to treat contract-call responses and ad-hoc fetches
 * uniformly.
 * <p>
 * Per-request flow:
 * <ol>
 *   <li>Build the request's headers: the configured static headers, then the present value of
 *       each dynamic header, read once. The request is sent with them, and the cache looks it up,
 *       stores its response and refreshes that response's variant by them, so a response's
 *       {@code Vary} is matched against the values the origin received.</li>
 *   <li>Resolve a rate-limit bucket id via {@link UrlFetcherConfig#bucketResolver}.</li>
 *   <li>Look up the URL, ended with the {@linkplain CacheKey#queryFingerprints(Map) stand-in} for
 *       the static query parameters, and the request's headers in the {@link ResponseCache},
 *       which stores the response under the same URL, unless the request's headers carry
 *       {@code If-None-Match} or {@code If-Modified-Since}: such a request leaves the conditional
 *       exchange to its caller, so it is sent as it stands and a {@code 304 Not Modified}
 *       answering it raises. On a hit that
 *       {@linkplain Response.CachedImpl#canServeWithoutRevalidation(Instant) may be served
 *       without revalidation} - fresh, and carrying no {@code no-cache} - serve a synthesized
 *       {@link Response.DirectImpl} immediately. On any other hit with an
 *       {@code ETag} or {@code Last-Modified} validator, attach {@code If-None-Match} /
 *       {@code If-Modified-Since} and dispatch a conditional request; on
 *       {@code 304 Not Modified}, refresh the cached entry and replay the cached body under the
 *       stored headers {@linkplain ResponseCache#mergeHeaders merged} with the 304's, as a Feign
 *       contract client replays it, whether or not {@link ResponseCache#updateOn304} found the
 *       entry to refresh. A hit without a validator is requested as it stands. On a
 *       {@code 5xx} answering the request for any hit, with a validator or without one, within
 *       the entry's {@code stale-if-error} window at the instant the fetch began, abort the
 *       exchange without reading the {@code 5xx}'s body and replay the cached body stamped
 *       {@link ResponseCache#CACHE_STALE_HEADER}, unless the entry carries
 *       {@code must-revalidate}, {@code proxy-revalidate} or {@code no-cache}, which
 *       {@linkplain Response.CachedImpl#canServeStaleOnError(Instant) refuse a stale replay}, so
 *       the {@code 5xx} raises.</li>
 *   <li>Admit the request against its rate-limit bucket and count it in one atomic step; raise
 *       {@link UrlFetchException.RateLimited} if the bucket is exhausted, so fetches sent
 *       together are admitted no further than its limit.</li>
 *   <li>Dispatch through the shared Apache transport, which appends the static query
 *       parameters themselves, recording the instant the response arrived on the request's
 *       context so the response's {@link NetworkDetails} carry the whole round trip.</li>
 *   <li>Read the response body capped at the fetch's cap. Once the body passes the cap the
 *       fetch stops reading and aborts the exchange, closing its connection rather than
 *       draining the rest of the body for reuse. It then raises
 *       {@link UrlFetchException.BodyCapExceeded} under a {@code 2xx} status, and under any
 *       other cuts the body at the cap, so that the status is what the fetch raises.</li>
 *   <li>Raise for a status outside the {@code 2xx} class, as
 *       {@link UrlFetchException#ofStatus UrlFetchException.ofStatus} builds it, or
 *       {@link UrlFetchException#ofUnknownStatus UrlFetchException.ofUnknownStatus} for a code
 *       {@link HttpStatus} has no constant for: {@link UrlFetchException.Redirection} for a
 *       {@code 3xx}, {@link UrlFetchException.ClientError} for a {@code 4xx} outside the Nginx
 *       range {@code 494-499}, a {@link UrlFetchException} for any other, each carrying the code
 *       the origin sent as its {@link UrlFetchException#getStatusCode() status code}. The
 *       exception is recorded on the cache as the last response, as a Feign contract client
 *       records the exception it raises for such a status, and nothing is stored for it.</li>
 *   <li>Build a {@link Response.DirectImpl} for a {@code 2xx} status, record it on the cache for
 *       observability, offer it to the cache for storage and return it. A {@code 2xx} code
 *       {@link HttpStatus} has no constant for is read as {@link HttpStatus#OK}, as
 *       <a href="https://www.rfc-editor.org/rfc/rfc9110#section-15">RFC 9110 §15</a> asks of a
 *       recipient that does not recognise a status code and as a Feign contract client reads
 *       one: its response reports {@code 200}, is recorded and returned as the response of a
 *       {@code 200} is, and is not offered to the cache, since a replay would answer with a code
 *       the origin did not send.</li>
 * </ol>
 * <p>
 * A {@code 4xx} answering a conditional request raises as well: only a {@code 5xx} is replaced
 * by a {@code stale-if-error} replay. A cache replay whose status is outside the {@code 2xx}
 * class raises the exception the live answer would have, recorded as the last response; this
 * fetcher never stores one, so such an entry comes only from another writer to a
 * {@linkplain UrlFetcherConfig#sharedCache shared cache}.
 * <p>
 * A fetch's cap is {@link UrlFetcherConfig#maxBodyBytes} unless the call names its own, as
 * {@link #get(URI, long)}, {@link #get(URI, Class, long)} and {@link #bytes(URI, long)} do. The
 * cap binds every body the fetch answers with: one read off the wire, and one the cache replays
 * on a fresh hit, a {@code 304 Not Modified} or a {@code stale-if-error} replacement. A read off
 * the wire stops at the cap and discards the connection, so a fetch downloads no more of a
 * larger body, or of one that never ends, than the cap and what the connection had already
 * buffered. A cached body larger than the cap raises {@link UrlFetchException.BodyCapExceeded}
 * with the replay's headers, and a fresh hit raises it without a request being sent. The entry
 * stays cached, so a fetch with a larger cap is still answered from it. A status outside the
 * {@code 2xx} class raises its own exception whatever the size of its body, which that
 * exception carries cut at the cap, so a {@code 404} page larger than the cap still raises
 * {@link UrlFetchException.ClientError}.
 *
 * @see UrlFetcherConfig
 * @see UrlFetchException
 * @see Client
 */
public final class UrlFetcher {

    /**
     * The immutable configuration bundle used to construct this fetcher.
     */
    @Getter
    private final @NotNull UrlFetcherConfig options;

    /**
     * The pooling Apache HTTP client used as the underlying transport.
     */
    private final @NotNull CloseableHttpClient http;

    /**
     * The response cache used for fresh-hit short-circuiting and observability.
     */
    @Getter
    private final @NotNull ResponseCache responseCache;

    /**
     * The rate-limit manager enforcing the per-bucket request budget.
     */
    @Getter
    private final @NotNull RateLimitManager rateLimitManager;

    /**
     * The stand-in for the static query parameters that ends the URL each request is keyed by in
     * the cache, or an empty string for a fetcher without any.
     */
    private final @NotNull String queryFingerprints;

    private UrlFetcher(@NotNull UrlFetcherConfig options) {
        this.options = options;
        this.responseCache = options.getSharedCache().orElseGet(() -> new ResponseCache(
            options.getTimings().maxCacheBytes(),
            options.getTimings().cacheSafetyFallback(),
            options.getTimings().cacheStaleRetention()
        ));
        this.rateLimitManager = options.getSharedRateLimits().orElseGet(RateLimitManager::new);
        this.queryFingerprints = CacheKey.queryFingerprints(options.getQueries());
        this.http = ApacheClientFactory.configure(
            options.getTimings(),
            options.getQueries(),
            options.getInet6Address()
        ).build();
    }

    /**
     * Creates a new {@code UrlFetcher} from the given configuration bundle.
     *
     * @param options the immutable configuration bundle
     * @return a fully initialized fetcher ready to issue requests
     */
    public static @NotNull UrlFetcher create(@NotNull UrlFetcherConfig options) {
        return new UrlFetcher(options);
    }

    // ===== Public API =====

    /**
     * Fetches the given URL and returns its body decoded as a {@link String} using the
     * charset advertised by the {@code Content-Type} header (or UTF-8 if absent), holding the
     * body to the {@linkplain UrlFetcherConfig#maxBodyBytes configured cap}.
     *
     * @param url the URL to fetch
     * @return the typed response envelope
     */
    public @NotNull Response<String> get(@NotNull URI url) {
        return this.decodeString(this.fetch(url, this.options.getMaxBodyBytes()));
    }

    /**
     * Fetches the given URL and returns its body decoded as {@link #get(URI)} decodes it, holding
     * the body to {@code maxBodyBytes} in place of the configured cap.
     * <p>
     * The cap binds a body the cache replays as it binds one read off the wire, so a cached body
     * larger than {@code maxBodyBytes} raises {@link UrlFetchException.BodyCapExceeded} without a
     * request being sent.
     *
     * @param url the URL to fetch
     * @param maxBodyBytes the largest body, in bytes, this fetch accepts
     * @return the typed response envelope
     * @throws IllegalArgumentException if {@code maxBodyBytes} is negative
     */
    public @NotNull Response<String> get(@NotNull URI url, long maxBodyBytes) {
        return this.decodeString(this.fetch(url, requireCap(maxBodyBytes)));
    }

    /**
     * Fetches the given URL and deserializes the body into {@code type} via the configured
     * {@link Gson Gson} instance, holding the body to the
     * {@linkplain UrlFetcherConfig#maxBodyBytes configured cap}. Body bytes are first
     * decoded to a string using the charset advertised by the {@code Content-Type} header (or
     * UTF-8 if absent).
     *
     * @param url the URL to fetch
     * @param type the target type
     * @param <T> the target type parameter
     * @return the typed response envelope
     */
    public <T> @NotNull Response<T> get(@NotNull URI url, @NotNull Class<T> type) {
        return this.decodeJson(this.fetch(url, this.options.getMaxBodyBytes()), type);
    }

    /**
     * Fetches the given URL and deserializes the body into {@code type} as
     * {@link #get(URI, Class)} does, holding the body to {@code maxBodyBytes} in place of the
     * configured cap, on a live read and a cache replay alike.
     *
     * @param url the URL to fetch
     * @param type the target type
     * @param maxBodyBytes the largest body, in bytes, this fetch accepts
     * @param <T> the target type parameter
     * @return the typed response envelope
     * @throws IllegalArgumentException if {@code maxBodyBytes} is negative
     */
    public <T> @NotNull Response<T> get(@NotNull URI url, @NotNull Class<T> type, long maxBodyBytes) {
        return this.decodeJson(this.fetch(url, requireCap(maxBodyBytes)), type);
    }

    /**
     * Fetches the given URL and returns its body bytes verbatim, holding the body to the
     * {@linkplain UrlFetcherConfig#maxBodyBytes configured cap}.
     *
     * @param url the URL to fetch
     * @return the typed response envelope
     */
    public @NotNull Response<byte[]> bytes(@NotNull URI url) {
        return this.fetch(url, this.options.getMaxBodyBytes());
    }

    /**
     * Fetches the given URL and returns its body bytes verbatim, holding the body to
     * {@code maxBodyBytes} in place of the configured cap, on a live read and a cache replay
     * alike.
     *
     * @param url the URL to fetch
     * @param maxBodyBytes the largest body, in bytes, this fetch accepts
     * @return the typed response envelope
     * @throws IllegalArgumentException if {@code maxBodyBytes} is negative
     */
    public @NotNull Response<byte[]> bytes(@NotNull URI url, long maxBodyBytes) {
        return this.fetch(url, requireCap(maxBodyBytes));
    }

    // ===== Observability =====

    /**
     * Retrieves the most recently observed response for this fetcher's cache.
     * <p>
     * Note: when the fetcher shares its cache with another client, this reflects the most
     * recent response observed across all sharers, not just this fetcher.
     *
     * @return the most recent response, or {@link Optional#empty()} if none has been observed
     */
    public @NotNull Optional<Response<?>> getLastResponse() {
        return this.responseCache.getLastResponse();
    }

    /**
     * Computes the round-trip latency of the most recent response in milliseconds.
     *
     * @return the round-trip latency, or {@code -1} if no response has been observed
     */
    public long getLatency() {
        return this.getLastResponse()
            .map(Response::getDetails)
            .map(NetworkDetails::getRoundTrip)
            .map(Stopwatch::durationMillis)
            .orElse(-1L);
    }

    /**
     * Checks whether the rate-limit bucket associated with the given URL is currently exhausted.
     *
     * @param url the URL whose bucket to inspect
     * @return {@code true} if the bucket exists and its request quota is exhausted
     */
    public boolean isRateLimited(@NotNull URI url) {
        return this.rateLimitManager.isRateLimited(this.options.getBucketResolver().apply(url));
    }

    /**
     * Returns the number of remaining requests allowed for the bucket associated with the
     * given URL before the current window expires.
     *
     * @param url the URL whose bucket to inspect
     * @return the remaining requests, or the unlimited sentinel if the bucket does not exist
     */
    public long getRemainingRequests(@NotNull URI url) {
        return this.rateLimitManager.getRemaining(this.options.getBucketResolver().apply(url));
    }

    /**
     * Returns a {@link UrlFetcherConfig.Builder} pre-populated with this fetcher's current
     * options for further modification.
     *
     * @return a builder pre-populated from this fetcher's options
     */
    public @NotNull UrlFetcherConfig.Builder mutate() {
        return this.options.mutate();
    }

    // ===== Core fetch path =====

    private @NotNull Response.DirectImpl<byte[]> fetch(@NotNull URI url, long maxBodyBytes) {
        Request request = new Request.Impl(HttpMethod.GET, CacheKey.withQuery(url.toString(), this.queryFingerprints));
        Map<String, Collection<String>> requestHeaders = this.requestHeaders();
        String bucketId = this.options.getBucketResolver().apply(url);
        RateLimit policy = this.options.getDefaultRateLimit();
        Instant now = Instant.now();

        Optional<CacheEntry<?>> hit = CacheRevalidation.hasConditionalHeaders(requestHeaders)
            ? Optional.empty()
            : this.responseCache.lookup(HttpMethod.GET, request.getUrl(), requestHeaders);

        if (hit.isPresent() && hit.get().response().canServeWithoutRevalidation(now))
            return this.serveFromCache(url, request, hit.get(), false, maxBodyBytes);

        if (!this.rateLimitManager.tryAcquire(bucketId, policy, now.toEpochMilli()))
            throw new UrlFetchException.RateLimited(url, bucketId, policy);

        return this.executeAndStore(url, request, requestHeaders, hit.orElse(null), now, maxBodyBytes);
    }

    /**
     * Builds the headers a request carries: each configured static header, then the present
     * value of each dynamic header, whose supplier is read once for the request.
     * <p>
     * The same headers are looked up in the cache, sent, stored with the response and used to
     * address the variant a {@code 304 Not Modified} refreshes, so the cache matches a
     * response's {@code Vary} against the values the origin received.
     *
     * @return the request's headers, keyed case-insensitively
     */
    private @NotNull Map<String, Collection<String>> requestHeaders() {
        Map<String, Collection<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        this.options.getHeaders().forEach((name, value) -> headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value));
        this.options.getDynamicHeaders().forEach((name, supplier) -> supplier.get()
            .ifPresent(value -> headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value))
        );
        return headers;
    }

    /**
     * Sends a request the cache could not answer and reads its response, storing a success.
     *
     * @param url the URL fetched
     * @param request the request the response answers
     * @param requestHeaders the request's headers
     * @param held the cached entry the lookup found, which the request revalidates when it
     *             carries a validator and which replaces a {@code 5xx} within its
     *             {@code stale-if-error} window, or {@code null} if the lookup found none
     * @param started the instant the fetch began, at which {@code held}'s
     *                {@code stale-if-error} window is judged
     * @param maxBodyBytes the largest body, in bytes, the fetch accepts
     * @return the response, live or replayed from {@code held}
     * @throws UrlFetchException if the status is outside the {@code 2xx} class and no replay of
     *                           {@code held} answers it
     */
    private @NotNull Response.DirectImpl<byte[]> executeAndStore(
        @NotNull URI url,
        @NotNull Request request,
        @NotNull Map<String, Collection<String>> requestHeaders,
        @Nullable CacheEntry<?> held,
        @NotNull Instant started,
        long maxBodyBytes
    ) {
        HttpGet get = new HttpGet(url);
        requestHeaders.forEach((name, values) -> values.forEach(value -> get.addHeader(name, value)));
        CacheEntry<?> revalidating = held != null && held.response().canRevalidate() ? held : null;

        if (revalidating != null)
            CacheRevalidation.buildConditionalHeaders(Collections.emptyMap(), revalidating.response())
                .forEach((name, values) -> values.forEach(value -> get.addHeader(name, value)));

        HttpClientContext context = HttpClientContext.create();

        try (CloseableHttpResponse apacheResponse = this.http.execute(get, context)) {
            context.setAttribute(NetworkDetails.RESPONSE_RECEIVED, Instant.now());
            int statusCode = apacheResponse.getCode();

            if (statusCode == HttpStatus.NOT_MODIFIED.getCode() && revalidating != null)
                return this.serveOn304(url, request, requestHeaders, apacheResponse, context, revalidating, maxBodyBytes);

            if (HttpState.SERVER_ERROR.containsCode(statusCode) && held != null
                && held.response().canServeStaleOnError(started)) {
                abort(get, apacheResponse);
                return this.serveFromCache(url, request, held, true, maxBodyBytes);
            }

            boolean success = HttpState.SUCCESS.containsCode(statusCode);
            Optional<HttpStatus> known = HttpStatus.findByCode(statusCode);
            byte[] body = readBody(get, apacheResponse, url, context, maxBodyBytes, !success);
            Map<String, Collection<String>> headers = headersFromApache(apacheResponse);

            if (!success) {
                NetworkDetails details = new NetworkDetails(context);
                UrlFetchException failure = known.isPresent()
                    ? statusFailure(request, known.get(), headers, body, details)
                    : UrlFetchException.ofUnknownStatus(statusCode, url, headers, body, details);
                this.responseCache.recordLastResponse(failure);
                throw failure;
            }

            // RFC 9110 §15: a 2xx code HttpStatus has no constant for is read as 200
            Response.DirectImpl<byte[]> response = new Response.DirectImpl<>(
                known.orElse(HttpStatus.OK),
                request,
                () -> new NetworkDetails(context),
                headers,
                () -> body
            );

            this.responseCache.recordLastResponse(response);

            if (known.isPresent())
                this.responseCache.store(response, body, requestHeaders);

            return response;
        } catch (UrlFetchException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new UrlFetchException.Transport(ex, url, new NetworkDetails(context));
        }
    }

    /**
     * Answers a fetch whose revalidation the origin answered with {@code 304 Not Modified}:
     * refreshes the revalidated entry in the cache and replays it under its stored headers merged
     * with the 304's.
     * <p>
     * The replay is {@linkplain ResponseCache#mergeHeaders(CacheEntry, Map, NetworkDetails) the
     * merge} the cache refreshes the entry with, taken of the entry the fetch revalidated, so it
     * carries the 304's headers and is aged from the 304 exchange whether or not
     * {@link ResponseCache#updateOn304} found the entry to refresh.
     *
     * @param url the URL fetched
     * @param request the request the entry answers
     * @param requestHeaders the request's headers, without the conditional headers the
     *                       revalidation added
     * @param apacheResponse the {@code 304} response
     * @param context the request's context, carrying the 304 exchange's network details
     * @param revalidating the cached entry the request revalidated
     * @param maxBodyBytes the largest body, in bytes, the fetch accepts
     * @return the replayed response
     */
    private @NotNull Response.DirectImpl<byte[]> serveOn304(
        @NotNull URI url,
        @NotNull Request request,
        @NotNull Map<String, Collection<String>> requestHeaders,
        @NotNull CloseableHttpResponse apacheResponse,
        @NotNull HttpClientContext context,
        @NotNull CacheEntry<?> revalidating,
        long maxBodyBytes
    ) {
        CacheKey.UrlKey key = CacheKey.UrlKey.of(HttpMethod.GET, request.getUrl());
        CacheKey.VaryFingerprint fingerprint = CacheKey.VaryFingerprint.of(
            revalidating.response().varyHeaderNames(),
            requestHeaders
        );
        Map<String, Collection<String>> notModifiedHeaders = headersFromApache(apacheResponse);
        NetworkDetails revalidation = new NetworkDetails(context);
        this.responseCache.updateOn304(key, fingerprint, requestHeaders, notModifiedHeaders, revalidation);
        EntityUtils.consumeQuietly(apacheResponse.getEntity());

        CacheEntry<?> refreshed = ResponseCache.mergeHeaders(revalidating, notModifiedHeaders, revalidation);
        return this.serveFromCache(url, request, refreshed, false, maxBodyBytes);
    }

    /**
     * Answers a fetch with a cached entry, holding its body to the fetch's cap as a live read
     * holds the body it reads.
     * <p>
     * The replay is recorded as the last response, or, for a cached status outside the
     * {@code 2xx} class, the exception it raises is.
     *
     * @param url the URL fetched
     * @param request the request the entry answers
     * @param entry the cached entry to replay
     * @param servedStale whether the entry replaces a {@code 5xx} within its
     *                    {@code stale-if-error} window
     * @param maxBodyBytes the largest body, in bytes, the fetch accepts
     * @return the replayed response
     * @throws UrlFetchException.BodyCapExceeded if the cached status is in the {@code 2xx} class
     *                                           and the cached body is larger than
     *                                           {@code maxBodyBytes}
     * @throws UrlFetchException if the cached status is outside the {@code 2xx} class, carrying
     *                           the body cut at {@code maxBodyBytes}
     */
    private @NotNull Response.DirectImpl<byte[]> serveFromCache(
        @NotNull URI url,
        @NotNull Request request,
        @NotNull CacheEntry<?> entry,
        boolean servedStale,
        long maxBodyBytes
    ) {
        Response.CachedImpl<?> cached = entry.response();
        Instant now = Instant.now();
        long ageSeconds = Math.max(0L, cached.currentAge(now).getSeconds());

        Map<String, Collection<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        cached.getHeaders().forEach((name, values) -> headers.put(name, new ArrayList<>(values)));
        headers.put("Age", List.of(Long.toString(ageSeconds)));
        headers.put(ResponseCache.CACHE_HIT_HEADER, List.of("true"));

        if (servedStale)
            headers.put(ResponseCache.CACHE_STALE_HEADER, List.of("true"));

        if (!HttpState.SUCCESS.containsCode(cached.getStatus().getCode())) {
            UrlFetchException failure = statusFailure(request, cached.getStatus(), headers, cutAt(entry.body(), maxBodyBytes), NetworkDetails.EMPTY);
            this.responseCache.recordLastResponse(failure);
            throw failure;
        }

        if (entry.body().length > maxBodyBytes)
            throw new UrlFetchException.BodyCapExceeded(url, NetworkDetails.EMPTY, headers, maxBodyBytes);

        Response.DirectImpl<byte[]> response = new Response.DirectImpl<>(
            cached.getStatus(),
            request,
            () -> NetworkDetails.EMPTY,
            headers,
            entry::body
        );
        this.responseCache.recordLastResponse(response);
        return response;
    }

    // ===== Helpers =====

    /**
     * Retypes a fetched envelope's body to a {@link String}, decoded with the charset its
     * {@code Content-Type} advertises, or UTF-8.
     *
     * @param raw the fetched envelope
     * @return the envelope with a string body
     */
    private @NotNull Response<String> decodeString(@NotNull Response.DirectImpl<byte[]> raw) {
        Charset charset = charsetFromContentType(raw.getContentType().orElse(null), StandardCharsets.UTF_8);
        return raw.withBody(() -> new String(raw.getBody(), charset));
    }

    /**
     * Retypes a fetched envelope's body to {@code type}, deserialized by the configured
     * {@link Gson} from the string {@link #decodeString} would give.
     *
     * @param raw the fetched envelope
     * @param type the target type
     * @param <T> the target type parameter
     * @return the envelope with a typed body
     */
    private <T> @NotNull Response<T> decodeJson(@NotNull Response.DirectImpl<byte[]> raw, @NotNull Class<T> type) {
        Charset charset = charsetFromContentType(raw.getContentType().orElse(null), StandardCharsets.UTF_8);
        return raw.withBody(() -> this.options.getGson().fromJson(new String(raw.getBody(), charset), type));
    }

    /**
     * Checks a body cap a caller names for one fetch.
     *
     * @param maxBodyBytes the cap in bytes
     * @return {@code maxBodyBytes}
     * @throws IllegalArgumentException if {@code maxBodyBytes} is negative
     */
    private static long requireCap(long maxBodyBytes) {
        if (maxBodyBytes < 0)
            throw new IllegalArgumentException(String.format("Body cap must not be negative, got '%s'", maxBodyBytes));

        return maxBodyBytes;
    }

    /**
     * Builds the exception a fetch raises for a status outside the {@code 2xx} class, whether
     * the origin answered it or the cache replayed it.
     *
     * @param request the request the status answers
     * @param status the status
     * @param headers the response headers
     * @param body the response body
     * @param details the network timing snapshot of the exchange
     * @return a {@link UrlFetchException.Redirection} for a {@code 3xx} status, a
     *         {@link UrlFetchException.ClientError} for a client error status, otherwise a
     *         {@link UrlFetchException}
     */
    private static @NotNull UrlFetchException statusFailure(
        @NotNull Request request,
        @NotNull HttpStatus status,
        @NotNull Map<String, Collection<String>> headers,
        byte @NotNull [] body,
        @NotNull NetworkDetails details
    ) {
        return UrlFetchException.ofStatus(
            new ErrorContext(status, request.getMethod(), request.getUrl(), headers, Collections.emptyMap(), body),
            details
        );
    }

    /**
     * Cuts a body at a cap.
     *
     * @param body the body
     * @param maxBytes the cap in bytes
     * @return {@code body} when it fits the cap, otherwise its first {@code maxBytes} bytes
     */
    private static byte @NotNull [] cutAt(byte @NotNull [] body, long maxBytes) {
        return body.length <= maxBytes ? body : Arrays.copyOf(body, (int) maxBytes);
    }

    /**
     * Aborts an exchange whose body the fetch does not read to its end.
     * <p>
     * The request is cancelled, which closes its connection at once rather than handing it back
     * to the pool, and the entity is then released against the closed connection. Releasing an
     * entity whose connection is still open reads the rest of its body so that the connection
     * can be reused; against a closed one it reads at most what the connection had already
     * buffered, so none of the body still to come is downloaded.
     *
     * @param get the request whose exchange is aborted
     * @param apacheResponse the response whose body is abandoned
     */
    private static void abort(@NotNull HttpGet get, @NotNull CloseableHttpResponse apacheResponse) {
        get.cancel();
        EntityUtils.consumeQuietly(apacheResponse.getEntity());
    }

    /**
     * Reads a response's body, holding it to a cap.
     * <p>
     * A body that passes the cap is not read further: the exchange is
     * {@linkplain #abort(HttpGet, CloseableHttpResponse) aborted} before the body is cut or
     * refused, so the rest of it is never downloaded.
     *
     * @param get the request the response answers, cancelled when the body passes the cap
     * @param apacheResponse the response whose body is read
     * @param url the URL fetched
     * @param context the request's context, for the network details of a refusal
     * @param maxBytes the largest body, in bytes, the fetch accepts
     * @param cutAtCap whether a body past the cap is cut at it rather than refused, as the body
     *                 of a status outside the {@code 2xx} class is
     * @return the body, empty when the response carries none
     * @throws UrlFetchException.BodyCapExceeded if the body is larger than {@code maxBytes} and
     *                                           {@code cutAtCap} is not set
     * @throws IOException if reading the body fails
     */
    private static byte @NotNull [] readBody(
        @NotNull HttpGet get,
        @NotNull CloseableHttpResponse apacheResponse,
        @NotNull URI url,
        @NotNull HttpClientContext context,
        long maxBytes,
        boolean cutAtCap
    ) throws IOException {
        HttpEntity entity = apacheResponse.getEntity();
        if (entity == null)
            return new byte[0];

        try (InputStream in = entity.getContent()) {
            if (in == null)
                return new byte[0];

            byte[] buffer = new byte[8192];
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    abort(get, apacheResponse);

                    if (cutAtCap) {
                        out.write(buffer, 0, (int) (read - (total - maxBytes)));
                        break;
                    }

                    Map<String, Collection<String>> headers = headersFromApache(apacheResponse);
                    NetworkDetails details = new NetworkDetails(context);
                    throw new UrlFetchException.BodyCapExceeded(url, details, headers, maxBytes);
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            EntityUtils.consumeQuietly(entity);
        }
    }

    private static @NotNull Map<String, Collection<String>> headersFromApache(@NotNull CloseableHttpResponse apacheResponse) {
        Map<String, Collection<String>> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Header header : apacheResponse.getHeaders())
            result.computeIfAbsent(header.getName(), k -> new ArrayList<>()).add(header.getValue());
        return result;
    }

    private static @NotNull Charset charsetFromContentType(String contentType, @NotNull Charset fallback) {
        if (contentType == null) return fallback;

        int idx = contentType.toLowerCase().indexOf("charset=");
        if (idx < 0) return fallback;

        String value = contentType.substring(idx + "charset=".length()).trim();
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) value = value.substring(0, semicolon).trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2)
            value = value.substring(1, value.length() - 1);

        try {
            return Charset.forName(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

}
