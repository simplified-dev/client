package dev.simplified.client.cache;

import dev.simplified.client.decoder.InternalResponseDecoder;
import dev.simplified.client.factory.ApacheClientFactory;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import feign.Client;
import feign.Request;
import feign.hc5.ApacheHttp5Client;
import org.apache.hc.core5.http.io.EofSensorInputStream;
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
import java.util.function.BiConsumer;
import java.util.function.Supplier;

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
 *       {@link ResponseCacheExpiry} keeps for the cache's stale retention past the entry's
 *       freshness when the entry carries a validator.</li>
 *   <li>On a stale cache hit, with a validator or without one, where the origin returns
 *       {@code 5xx} within the entry's {@code stale-if-error} window, judged at the instant
 *       before the request is sent, the cached bytes are served in place of the error response
 *       per <a href="https://datatracker.ietf.org/doc/html/rfc5861#section-4">RFC 5861 §4</a>,
 *       and the {@code 5xx}'s exchange is aborted without its body being read; a hit without a
 *       validator is requested as it stands, with no conditional header. An entry carrying
 *       {@code must-revalidate}, {@code proxy-revalidate} or {@code no-cache} has its
 *       {@linkplain Response.CachedImpl#canServeStaleOnError(Instant) stale replay refused},
 *       and the error response is returned.</li>
 *   <li>On a successful unsafe method ({@code POST}, {@code PUT}, {@code PATCH},
 *       {@code DELETE}), the cache is invalidated for the target URL plus any
 *       {@code Location} and {@code Content-Location} redirects.</li>
 * </ul>
 * <p>
 * The client's configured static and dynamic headers are added here, to the request Feign hands
 * this class, and never to the request Feign builds, so no value of theirs sits on a
 * {@link Request} that Feign logs or prints or that an error decoder reads. Each dynamic
 * header's supplier is read once for the request. The delegate sends the request's own headers
 * followed by each configured value. The cache keys the request by the same headers with each
 * configured value replaced by its {@linkplain CacheKey#fingerprint(String) fingerprint}:
 * {@link ResponseCache#lookup} and {@link ResponseCache#updateOn304} are given them here, and
 * every response this class returns names the fingerprints in {@link #FINGERPRINT_HEADER}, from
 * which {@link #keyHeaders(feign.Response)} gives {@link InternalResponseDecoder} the same headers
 * to {@linkplain ResponseCache#store store} the answer under. A response's {@code Vary} is so
 * matched against the values the origin received, and the cache holds none of them.
 * <p>
 * The client's static query parameters are appended by the transport, below this class, to the
 * request it sends. The request Feign builds ends its URL with their
 * {@linkplain CacheKey#queryFingerprints(Map) stand-in} instead, so the cache keys each request,
 * lookup and store alike, by the static queries it is sent with, without holding their values.
 * This class removes the stand-in from the request it sends, and invalidates a URL a mutation
 * names both with and without it.
 * <p>
 * Every response the delegate returns carries its round trip: any
 * {@linkplain NetworkDetails#isInternalHeader(String) internal header} in it is dropped but the
 * {@linkplain NetworkDetails#CONNECTION_HEADERS connection markers} the transport recorded, and
 * the instant before the request was handed to the delegate and the instant the delegate returned
 * are added to the response's headers as {@link NetworkDetails#REQUEST_START} and
 * {@link NetworkDetails#RESPONSE_RECEIVED}. {@link NetworkDetails} reads all of them there, so a
 * stored response's {@link Response.CachedImpl#currentAge(Instant) age} and the request start
 * {@link ResponseCache#store} compares with its last drop are measured from the round trip, and
 * a response reports the DNS, TCP and TLS timings and TLS protocol and cipher of the connection
 * it came over.
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
public final class CachingFeignClient implements Client {

    /**
     * Internal response header naming the fingerprint of each configured header value the request
     * a response answers was sent with, one {@code name=fingerprint} value per configured value,
     * in the order the request carried them.
     */
    public static final @NotNull String FINGERPRINT_HEADER = NetworkDetails.INTERNAL_HEADER_PREFIX + "Header-Fingerprint";

    /**
     * The underlying Feign client to which non-short-circuited requests are dispatched.
     */
    private final @NotNull Client delegate;

    /**
     * The shared response cache used for lookups, stores via decoder, invalidation, and 304 merging.
     */
    private final @NotNull ResponseCache responseCache;

    /**
     * The static headers each request is sent with.
     */
    private final @NotNull Map<String, String> headers;

    /**
     * The dynamic headers each request is sent with when their supplier yields a value.
     */
    private final @NotNull Map<String, Supplier<Optional<String>>> dynamicHeaders;

    /**
     * The stand-in for the client's static query parameters that ends the URL of each request
     * Feign builds, {@linkplain CacheKey#queryFingerprints(Map) their fingerprints}, or an empty
     * string for a client without any.
     */
    private final @NotNull String queryFingerprints;

    /**
     * Constructs a new {@code CachingFeignClient} that sends each request with its own headers
     * alone.
     *
     * @param delegate the underlying Feign client requests the cache does not answer are sent
     *                 through
     * @param responseCache the response cache to look up, revalidate and invalidate
     */
    public CachingFeignClient(@NotNull Client delegate, @NotNull ResponseCache responseCache) {
        this(delegate, responseCache, Map.of(), Map.of(), Map.of());
    }

    /**
     * Constructs a new {@code CachingFeignClient} that sends each request with the given static
     * headers and the present value of each dynamic header after its own, keying the cache by
     * their fingerprints, and that removes the stand-in for the given static query parameters
     * from the URL of each request it sends.
     *
     * @param delegate the underlying Feign client requests the cache does not answer are sent
     *                 through
     * @param responseCache the response cache to look up, revalidate and invalidate
     * @param headers the static headers each request is sent with
     * @param dynamicHeaders the dynamic headers each request is sent with when their supplier
     *                       yields a value, each supplier read once per request
     * @param queries the static query parameters the transport appends to each request, whose
     *                {@linkplain CacheKey#queryFingerprints(Map) stand-in} ends the URL of each
     *                request handed to this client
     */
    public CachingFeignClient(
        @NotNull Client delegate,
        @NotNull ResponseCache responseCache,
        @NotNull Map<String, String> headers,
        @NotNull Map<String, Supplier<Optional<String>>> dynamicHeaders,
        @NotNull Map<String, String> queries
    ) {
        this.delegate = delegate;
        this.responseCache = responseCache;
        this.headers = headers;
        this.dynamicHeaders = dynamicHeaders;
        this.queryFingerprints = CacheKey.queryFingerprints(queries);
    }

    @Override
    public feign.Response execute(@NotNull Request request, @NotNull Request.Options options) throws IOException {
        Outgoing outgoing = this.outgoing(request);
        HttpMethod method = HttpMethod.of(request.httpMethod().name());

        if (method.isCacheable() && !CacheRevalidation.hasConditionalHeaders(outgoing.wire().headers())) {
            Optional<CacheEntry<?>> lookup = this.responseCache.lookup(method, request.url(), outgoing.keyHeaders());

            if (lookup.isPresent()) {
                feign.Response shortCircuit = this.serveFromCache(outgoing, options, method, lookup.get());

                if (shortCircuit != null)
                    return shortCircuit;
            }
        }

        feign.Response response = this.exchange(outgoing, outgoing.wire(), options);

        if (!method.isSafe() && isSuccessOrRedirect(response.status()))
            this.invalidateAfterMutation(request, response);

        return response;
    }

    /**
     * Builds the headers the cache keys the request a response answers by: the headers of the
     * response's request, followed by each fingerprint the response names in
     * {@link #FINGERPRINT_HEADER} under the header it stands for.
     * <p>
     * For a response a {@code CachingFeignClient} returned, these are the headers it looked the
     * request up by, so {@link InternalResponseDecoder} stores the answer, or discards an
     * undecodable one, as the variant {@link ResponseCache#lookup} would match to the request. A
     * response naming no fingerprint gives its request's headers.
     *
     * @param response the response
     * @return the headers the cache keys the response's request by
     */
    public static @NotNull Map<String, Collection<String>> keyHeaders(@NotNull feign.Response response) {
        Collection<String> fingerprints = response.headers().get(FINGERPRINT_HEADER);

        if (fingerprints == null || fingerprints.isEmpty())
            return response.request().headers();

        Map<String, Collection<String>> keyed = copyOf(response.request().headers());

        for (String fingerprint : fingerprints) {
            int separator = fingerprint.indexOf('=');

            if (separator > 0)
                keyed.computeIfAbsent(fingerprint.substring(0, separator), name -> new ArrayList<>()).add(fingerprint.substring(separator + 1));
        }

        return keyed;
    }

    /**
     * Resolves the configured headers for one request, reading each dynamic header's supplier
     * once, and removes the stand-in for the static query parameters from the URL it is sent to.
     *
     * @param request the request as Feign built it
     * @return the request as it is sent and as the cache keys it
     */
    private @NotNull Outgoing outgoing(@NotNull Request request) {
        String url = this.withoutQueryFingerprints(request.url());

        if (this.headers.isEmpty() && this.dynamicHeaders.isEmpty() && url.equals(request.url()))
            return new Outgoing(request, request, request.headers(), List.of());

        Map<String, Collection<String>> sent = copyOf(request.headers());
        Map<String, Collection<String>> keyed = copyOf(request.headers());
        List<String> fingerprints = new ArrayList<>();
        BiConsumer<String, String> add = (name, value) -> {
            String fingerprint = CacheKey.fingerprint(value);
            sent.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
            keyed.computeIfAbsent(name, key -> new ArrayList<>()).add(fingerprint);
            fingerprints.add(name + "=" + fingerprint);
        };

        this.headers.forEach(add);
        this.dynamicHeaders.forEach((name, supplier) -> supplier.get().ifPresent(value -> add.accept(name, value)));

        Request wire = Request.create(
            request.httpMethod(),
            url,
            sent,
            request.body(),
            request.charset(),
            request.requestTemplate()
        );

        return new Outgoing(request, wire, keyed, List.copyOf(fingerprints));
    }

    /**
     * Removes the stand-in for the static query parameters from the end of a URL.
     *
     * @param url the URL
     * @return {@code url} without the stand-in, or {@code url} when it does not end with it
     */
    private @NotNull String withoutQueryFingerprints(@NotNull String url) {
        if (this.queryFingerprints.isEmpty() || url.length() <= this.queryFingerprints.length() || !url.endsWith(this.queryFingerprints))
            return url;

        int separator = url.length() - this.queryFingerprints.length() - 1;
        char before = url.charAt(separator);
        return before == '?' || before == '&' ? url.substring(0, separator) : url;
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
        return this.serveFromCache(this.outgoing(request), options, method, entry);
    }

    /**
     * Attempts to serve a resolved request from the given cached entry, as
     * {@link #serveFromCache(Request, Request.Options, HttpMethod, CacheEntry)} does.
     *
     * @param outgoing the request as Feign built it, as it is sent, and as the cache keys it
     * @param options the Feign request options (used when dispatching a revalidation)
     * @param method the HTTP method of the request
     * @param entry the cached entry selected by {@link ResponseCache#lookup}
     * @return the synthesized response for a fresh / 304 / stale-if-error path, or
     *         {@code null} if the caller should proceed to the delegate
     * @throws IOException if the delegate fails during a stale revalidation
     */
    private @Nullable feign.Response serveFromCache(
        @NotNull Outgoing outgoing,
        @NotNull Request.Options options,
        @NotNull HttpMethod method,
        @NotNull CacheEntry<?> entry
    ) throws IOException {
        Instant now = Instant.now();
        Response.CachedImpl<?> cached = entry.response();

        if (cached.canServeWithoutRevalidation(now))
            return this.synthesizeFreshHit(outgoing, entry, now);

        boolean revalidates = cached.canRevalidate();

        if (!revalidates && !cached.canServeStaleOnError(now))
            return null;

        Request sent = revalidates ? this.withConditionalHeaders(outgoing.wire(), cached) : outgoing.wire();
        feign.Response response = this.exchange(outgoing, sent, options);

        if (revalidates && response.status() == 304) {
            NetworkDetails revalidation = new NetworkDetails(response);
            CacheKey.UrlKey key = CacheKey.UrlKey.of(method, outgoing.request().url());
            CacheKey.VaryFingerprint fingerprint = CacheKey.VaryFingerprint.of(cached.varyHeaderNames(), outgoing.keyHeaders());
            this.responseCache.updateOn304(key, fingerprint, outgoing.keyHeaders(), response.headers(), revalidation);

            feign.Util.ensureClosed(response.body());

            return this.synthesizeRevalidatedHit(outgoing, entry, now, response.headers(), revalidation);
        }

        if (isServerError(response.status()) && cached.canServeStaleOnError(now)) {
            abort(response);
            return this.synthesizeStaleHit(outgoing, entry, now);
        }

        return response;
    }

    /**
     * Abandons a response whose body this client does not read, without downloading the rest
     * of it.
     * <p>
     * The Apache transport {@link ApacheClientFactory} configures reads the body of every
     * response through an {@link EofSensorInputStream}, one it decodes for its
     * {@code Content-Encoding} as well as one it does not, and that stream is aborted: its
     * connection is closed at once rather than handed back to the pool, and closing the body
     * then reads at most what the connection had already buffered, so none of the body still to
     * come is downloaded, and an encoded body is not decoded. The body of any other transport is
     * closed, which reads it to its end.
     *
     * @param response the response to abandon
     */
    private static void abort(@NotNull feign.Response response) {
        feign.Response.Body body = response.body();

        if (body == null)
            return;

        try {
            if (body.asInputStream() instanceof EofSensorInputStream exchange)
                exchange.abort();
        } catch (IOException ignored) {
            // The body is closed below whether or not its exchange could be aborted.
        }

        feign.Util.ensureClosed(body);
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
     * @param outgoing the original request being short-circuited
     * @param entry the cached entry whose bytes will be served
     * @param now the synthesized start/end timestamp
     * @return the synthesized response
     */
    private @NotNull feign.Response synthesizeFreshHit(
        @NotNull Outgoing outgoing,
        @NotNull CacheEntry<?> entry,
        @NotNull Instant now
    ) {
        long ageSeconds = Math.max(0L, entry.response().currentAge(now).getSeconds());
        return this.synthesize(outgoing, entry, now, ageSeconds, false, null);
    }

    /**
     * Builds the synthetic replay answering a {@code 304 Not Modified} revalidation of the given
     * cached entry.
     * <p>
     * The replay carries the entry's headers refreshed by the 304's and its age counted from
     * the 304 exchange, the same merge {@link ResponseCache#updateOn304} stores, plus
     * {@link ResponseCache#REVALIDATED_HEADER} naming each header the 304 supplied.
     *
     * @param outgoing the original request
     * @param entry the cached entry the 304 revalidated
     * @param now the synthesized timestamp
     * @param notModifiedHeaders the headers of the {@code 304} response
     * @param revalidation the network details of the {@code 304} exchange
     * @return the synthesized revalidation replay
     */
    private @NotNull feign.Response synthesizeRevalidatedHit(
        @NotNull Outgoing outgoing,
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
        return this.synthesize(outgoing, refreshed, now, ageSeconds, false, revalidated);
    }

    /**
     * Builds a synthetic stale-if-error replay for the given cached entry.
     * <p>
     * Identical to {@link #synthesizeFreshHit(Outgoing, CacheEntry, Instant)} except
     * that {@link ResponseCache#CACHE_STALE_HEADER} is added to the response headers so
     * observability callers can distinguish a stale replay from a fresh hit.
     *
     * @param outgoing the original request
     * @param entry the cached entry to replay
     * @param now the synthesized timestamp
     * @return the synthesized stale replay response
     */
    private @NotNull feign.Response synthesizeStaleHit(
        @NotNull Outgoing outgoing,
        @NotNull CacheEntry<?> entry,
        @NotNull Instant now
    ) {
        long ageSeconds = Math.max(0L, entry.response().currentAge(now).getSeconds());
        return this.synthesize(outgoing, entry, now, ageSeconds, true, null);
    }

    /**
     * Core synthesis helper shared by the fresh-hit, revalidation and stale-hit paths.
     * <p>
     * The replay answers the request as Feign built it and names the fingerprints of its
     * configured values in {@link #FINGERPRINT_HEADER}.
     *
     * @param outgoing the original request that was short-circuited
     * @param entry the cached entry whose bytes and headers will be served
     * @param now the timestamp for request-start and response-received headers
     * @param ageSeconds the computed {@code Age} value to advertise
     * @param servedStale whether this is a stale-if-error replay
     * @param revalidated the names of the headers a {@code 304} supplied, or {@code null} for a
     *                    replay no revalidation answered
     * @return the synthesized feign response
     */
    private @NotNull feign.Response synthesize(
        @NotNull Outgoing outgoing,
        @NotNull CacheEntry<?> entry,
        @NotNull Instant now,
        long ageSeconds,
        boolean servedStale,
        @Nullable List<String> revalidated
    ) {
        Response.CachedImpl<?> cached = entry.response();
        Map<String, Collection<String>> responseHeaders = buildResponseHeaders(cached, now, ageSeconds, servedStale, revalidated);
        outgoing.record(responseHeaders);

        return feign.Response.builder()
            .request(outgoing.request())
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
     * @see #invalidateKeys(String)
     */
    private void invalidateAfterMutation(@NotNull Request request, @NotNull feign.Response response) {
        this.invalidateKeys(request.url());

        extractFirstHeader(response.headers(), "Location").ifPresent(this::invalidateKeys);
        extractFirstHeader(response.headers(), "Content-Location").ifPresent(this::invalidateKeys);
    }

    /**
     * Invalidates the cached entries for a URL under both keys a request for it can have: without
     * the stand-in for the static query parameters, as a request that carries none is keyed, and
     * with it, as this client keys its own requests.
     *
     * @param url the URL to invalidate, with or without the stand-in
     */
    private void invalidateKeys(@NotNull String url) {
        String plain = this.withoutQueryFingerprints(url);
        this.responseCache.invalidate(plain);

        if (!this.queryFingerprints.isEmpty())
            this.responseCache.invalidate(CacheKey.withQuery(plain, this.queryFingerprints));
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
     * delegate's response is dropped but the {@linkplain NetworkDetails#CONNECTION_HEADERS
     * connection markers}, which the client's transport records on the response in place of any
     * the origin sent: an origin or proxy that sends internal headers of its own must not stand in
     * for the round trip. The instant before the delegate is called and the instant it returns
     * are then added to the response's headers as {@link NetworkDetails#REQUEST_START} and
     * {@link NetworkDetails#RESPONSE_RECEIVED}. The request start is recorded on the response
     * rather than the request because Feign rebuilds the response it is handed around the request
     * it built. The response answers the request as Feign built it, not the request sent with the
     * configured values, and names their fingerprints in {@link #FINGERPRINT_HEADER}.
     *
     * @param outgoing the request as Feign built it and as the cache keys it
     * @param request the request to send, carrying the configured values
     * @param options the Feign request options
     * @return the delegate's response, carrying its round trip
     * @throws IOException if the delegate fails
     */
    private @NotNull feign.Response exchange(
        @NotNull Outgoing outgoing,
        @NotNull Request request,
        @NotNull Request.Options options
    ) throws IOException {
        Instant sent = Instant.now();
        feign.Response response = this.delegate.execute(request, options);
        Map<String, Collection<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        response.headers().forEach((name, values) -> {
            if (!NetworkDetails.isInternalHeader(name) || NetworkDetails.isConnectionHeader(name))
                headers.put(name, values);
        });

        headers.put(NetworkDetails.REQUEST_START, List.of(sent.toString()));
        headers.put(NetworkDetails.RESPONSE_RECEIVED, List.of(Instant.now().toString()));
        outgoing.record(headers);

        return response.toBuilder().request(outgoing.request()).headers(headers).build();
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

    /**
     * Copies a header map into a case-insensitive map whose value lists can be appended to.
     *
     * @param headers the headers to copy
     * @return the copy
     */
    private static @NotNull Map<String, Collection<String>> copyOf(@NotNull Map<String, Collection<String>> headers) {
        Map<String, Collection<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach((name, values) -> copy.put(name, new ArrayList<>(values)));
        return copy;
    }

    /**
     * One request with the client's configured headers resolved: as Feign built it, as it is
     * sent, and as the cache keys it.
     *
     * @param request the request as Feign built it
     * @param wire the request as it is sent, its own headers followed by each configured value
     * @param keyHeaders the headers the cache keys the request by, its own headers followed by the
     *                   fingerprint of each configured value
     * @param fingerprints the {@link #FINGERPRINT_HEADER} values naming each configured header and
     *                     the fingerprint of its value, in the order the request carries them
     */
    private record Outgoing(
        @NotNull Request request,
        @NotNull Request wire,
        @NotNull Map<String, Collection<String>> keyHeaders,
        @NotNull List<String> fingerprints
    ) {

        /**
         * Names the fingerprints of the request's configured values in a response's headers,
         * under {@link #FINGERPRINT_HEADER}, when the request carries any.
         *
         * @param responseHeaders the response headers to add them to
         */
        void record(@NotNull Map<String, Collection<String>> responseHeaders) {
            if (!this.fingerprints.isEmpty())
                responseHeaders.put(FINGERPRINT_HEADER, this.fingerprints);
        }

    }

}
