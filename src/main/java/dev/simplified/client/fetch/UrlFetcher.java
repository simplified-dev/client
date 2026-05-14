package dev.simplified.client.fetch;

import dev.simplified.client.Client;
import dev.simplified.client.cache.CacheEntry;
import dev.simplified.client.cache.ResponseCache;
import dev.simplified.client.exception.ErrorContext;
import dev.simplified.client.exception.UrlFetchException;
import dev.simplified.client.factory.ApacheClientFactory;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.request.Request;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import dev.simplified.util.time.Stopwatch;
import lombok.Getter;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Standalone HTTP fetcher for ad-hoc URLs that reuses the {@link Client} infrastructure
 * (Apache HTTP pool, timed sockets, {@link ResponseCache}, {@link RateLimitManager}) without
 * the contract-based Feign discipline that {@code Client} requires.
 * <p>
 * Built from a {@link UrlFetcherConfig} via {@link #create(UrlFetcherConfig)}, the fetcher
 * exposes a small typed API ({@link #get(URI)}, {@link #get(URI, Class)}, {@link #bytes(URI)})
 * that returns the same {@link Response Response&lt;T&gt;} envelopes used elsewhere in the
 * library, enabling observability code to treat contract-call responses and ad-hoc fetches
 * uniformly.
 * <p>
 * Per-request flow:
 * <ol>
 *   <li>Resolve a rate-limit bucket id via {@link UrlFetcherConfig#getBucketResolver()}.</li>
 *   <li>Look up the URL in the {@link ResponseCache}; serve a synthesized
 *       {@link Response.DirectImpl} immediately on a fresh hit. Stale entries fall through
 *       to a fresh fetch (no conditional revalidation in v1).</li>
 *   <li>Check the local rate limit; raise {@link UrlFetchException.RateLimited} if exhausted.</li>
 *   <li>Track the request and dispatch through the shared Apache transport.</li>
 *   <li>Read the response body capped at {@link UrlFetcherConfig#getMaxBodyBytes()};
 *       raise {@link UrlFetchException.BodyCapExceeded} if the cap is hit.</li>
 *   <li>Build a {@link Response.DirectImpl}, record it on the cache for observability, and
 *       offer it to the cache for storage.</li>
 *   <li>Raise {@link UrlFetchException} for non-success statuses; otherwise return.</li>
 * </ol>
 *
 * @see UrlFetcherConfig
 * @see UrlFetchException
 * @see Client
 */
public final class UrlFetcher {

    /** The immutable configuration bundle used to construct this fetcher. */
    @Getter
    private final @NotNull UrlFetcherConfig options;

    /** The pooling Apache HTTP client used as the underlying transport. */
    private final @NotNull CloseableHttpClient http;

    /** The response cache used for fresh-hit short-circuiting and observability. */
    @Getter
    private final @NotNull ResponseCache responseCache;

    /** The rate-limit manager enforcing the per-bucket request budget. */
    @Getter
    private final @NotNull RateLimitManager rateLimitManager;

    private UrlFetcher(@NotNull UrlFetcherConfig options) {
        this.options = options;
        this.responseCache = options.getSharedCache().orElseGet(() -> new ResponseCache(
            options.getTimings().maxCacheBytes(),
            options.getTimings().cacheSafetyFallback()
        ));
        this.rateLimitManager = options.getSharedRateLimits().orElseGet(RateLimitManager::new);
        this.http = ApacheClientFactory.configure(
            options.getTimings(),
            options.getQueries(),
            options.getHeaders(),
            options.getDynamicHeaders(),
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
     * charset advertised by the {@code Content-Type} header (or UTF-8 if absent).
     *
     * @param url the URL to fetch
     * @return the typed response envelope
     */
    public @NotNull Response<String> get(@NotNull URI url) {
        Response<byte[]> raw = this.fetch(url);
        Charset charset = charsetFromContentType(raw.getContentType().orElse(null), StandardCharsets.UTF_8);
        return reTyped(raw, () -> new String(raw.getBody(), charset));
    }

    /**
     * Fetches the given URL and deserializes the body into {@code type} via the configured
     * {@link com.google.gson.Gson Gson} instance. Body bytes are first decoded to a string
     * using the charset advertised by the {@code Content-Type} header (or UTF-8 if absent).
     *
     * @param url the URL to fetch
     * @param type the target type
     * @param <T> the target type parameter
     * @return the typed response envelope
     */
    public <T> @NotNull Response<T> get(@NotNull URI url, @NotNull Class<T> type) {
        Response<byte[]> raw = this.fetch(url);
        Charset charset = charsetFromContentType(raw.getContentType().orElse(null), StandardCharsets.UTF_8);
        return reTyped(raw, () -> this.options.getGson().fromJson(new String(raw.getBody(), charset), type));
    }

    /**
     * Fetches the given URL and returns its body bytes verbatim.
     *
     * @param url the URL to fetch
     * @return the typed response envelope
     */
    public @NotNull Response<byte[]> bytes(@NotNull URI url) {
        return this.fetch(url);
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

    private @NotNull Response<byte[]> fetch(@NotNull URI url) {
        Request request = new Request.Impl(HttpMethod.GET, url.toString());
        String bucketId = this.options.getBucketResolver().apply(url);
        RateLimit policy = this.options.getDefaultRateLimit();
        long now = System.currentTimeMillis();

        Optional<CacheEntry<?>> hit = this.responseCache.lookup(HttpMethod.GET, url.toString(), Collections.emptyMap());
        if (hit.isPresent() && hit.get().response().isFresh(Instant.now()))
            return this.serveFromCache(url, request, hit.get());

        if (this.rateLimitManager.isRateLimited(bucketId, policy, now))
            throw new UrlFetchException.RateLimited(url, bucketId, policy);

        this.rateLimitManager.trackRequest(bucketId, policy, now);

        return this.executeAndStore(url, request);
    }

    private @NotNull Response<byte[]> executeAndStore(@NotNull URI url, @NotNull Request request) {
        HttpGet get = new HttpGet(url);
        HttpClientContext context = HttpClientContext.create();

        try (CloseableHttpResponse apacheResponse = this.http.execute(get, context)) {
            byte[] body = readBody(apacheResponse, url, context, this.options.getMaxBodyBytes());
            HttpStatus status = HttpStatus.of(apacheResponse.getStatusLine().getStatusCode());
            Map<String, Collection<String>> headers = headersFromApache(apacheResponse);
            NetworkDetails details = new NetworkDetails(context);

            Response.DirectImpl<byte[]> response = new Response.DirectImpl<>(
                status,
                request,
                details,
                headers,
                () -> body
            );

            this.responseCache.recordLastResponse(response);
            this.responseCache.store(response, body);

            if (response.isError())
                throw new UrlFetchException(
                    new ErrorContext(status, HttpMethod.GET, url.toString(), headers, Collections.emptyMap(), body),
                    details,
                    "Origin returned %d %s for URL '%s'",
                    status.getCode(),
                    status.getMessage(),
                    url
                );

            return response;
        } catch (UrlFetchException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new UrlFetchException.Transport(ex, url, new NetworkDetails(context));
        }
    }

    private @NotNull Response<byte[]> serveFromCache(@NotNull URI url, @NotNull Request request, @NotNull CacheEntry<?> entry) {
        Response.CachedImpl<?> cached = entry.response();
        byte[] body = entry.body();
        Instant now = Instant.now();
        long ageSeconds = Math.max(0L, cached.currentAge(now).getSeconds());

        Map<String, Collection<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        cached.getHeaders().forEach((name, values) -> headers.put(name, new ArrayList<>(values)));
        headers.put("Age", List.of(Long.toString(ageSeconds)));
        headers.put(ResponseCache.CACHE_HIT_HEADER, List.of("true"));

        Response.DirectImpl<byte[]> response = new Response.DirectImpl<>(
            cached.getStatus(),
            request,
            NetworkDetails.empty(),
            headers,
            () -> body
        );
        this.responseCache.recordLastResponse(response);
        return response;
    }

    // ===== Helpers =====

    private static <I, O> @NotNull Response<O> reTyped(@NotNull Response<I> source, @NotNull Supplier<O> bodyDecoder) {
        return new Response.DirectImpl<>(
            source.getStatus(),
            source.getRequest(),
            source.getDetails(),
            source.getHeaders(),
            bodyDecoder
        );
    }

    private static byte @NotNull [] readBody(
        @NotNull CloseableHttpResponse apacheResponse,
        @NotNull URI url,
        @NotNull HttpClientContext context,
        long maxBytes
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
        for (Header header : apacheResponse.getAllHeaders())
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
