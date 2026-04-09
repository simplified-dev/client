package dev.simplified.client;

import dev.simplified.client.cache.CachingFeignClient;
import dev.simplified.client.cache.ResponseCache;
import dev.simplified.client.decoder.ClientErrorDecoder;
import dev.simplified.client.decoder.InternalErrorDecoder;
import dev.simplified.client.decoder.InternalResponseDecoder;
import dev.simplified.client.exception.ApiDecodeException;
import dev.simplified.client.exception.ApiException;
import dev.simplified.client.exception.RetryableApiException;
import dev.simplified.client.factory.TimedPlainConnectionSocketFactory;
import dev.simplified.client.factory.TimedSecureConnectionSocketFactory;
import dev.simplified.client.interceptor.InternalRequestInterceptor;
import dev.simplified.client.interceptor.InternalResponseInterceptor;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.request.AsyncAccess;
import dev.simplified.client.request.Contract;
import dev.simplified.client.request.Timings;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import dev.simplified.client.route.DynamicRouteProvider;
import dev.simplified.client.route.Route;
import dev.simplified.client.route.RouteDiscovery;
import dev.simplified.util.time.Stopwatch;
import feign.Feign;
import feign.httpclient.ApacheHttpClient;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.conn.SystemDefaultDnsResolver;
import org.apache.http.protocol.HttpContext;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Feign-backed HTTP client providing connection pooling, rate limiting, route discovery,
 * request/response interception, error decoding, and network timing instrumentation.
 * <p>
 * A {@code Client} is parameterized by a {@link Contract} interface whose methods declare the
 * remote HTTP operations (using Feign's {@code @RequestLine} annotations) and is constructed via
 * {@link #create(ClientOptions)} from an immutable {@link ClientOptions} bundle. All customization
 * - headers, queries, dynamic headers, timings, error decoder, encoder/decoder factories, IPv6
 * binding - lives on the options; the client itself owns only the runtime state needed to execute
 * requests.
 * <p>
 * During construction the client discovers routes from {@link Route @Route} or
 * {@link dev.simplified.client.route.DynamicRoute @DynamicRoute} annotations on the contract
 * interface through {@link RouteDiscovery}, instantiates a {@link ResponseCache} for both
 * conditional revalidation and {@code getLastResponse()} observability, builds a pooling
 * Apache {@link ApacheHttpClient} with {@link TimedPlainConnectionSocketFactory} and
 * {@link TimedSecureConnectionSocketFactory} for DNS, TCP, and TLS timing instrumentation,
 * wraps the Apache client in a {@link CachingFeignClient} that serves RFC 7234 cache hits
 * transparently, assembles a Feign proxy that wires together encoding, decoding, request
 * and response interceptors, and the configured error decoder, and finally wraps the
 * resulting Feign proxy in a JDK dynamic proxy that unwraps {@link RetryableApiException}
 * so callers see the original typed {@link ApiException} rather than Feign's internal
 * retry wrapper.
 * <p>
 * To produce a derived client that shares most of an existing client's configuration, call
 * {@link #mutate()} to obtain a {@link ClientOptions.Builder} seeded from the current options,
 * adjust the differing fields, and pass the result to {@link #create(ClientOptions)}.
 *
 * @param <C> the Feign contract interface type that declares the remote HTTP operations;
 *            must extend {@link Contract}
 * @see ClientOptions
 * @see Contract
 * @see AsyncAccess
 * @see RouteDiscovery
 * @see RateLimitManager
 * @see ResponseCache
 * @see CachingFeignClient
 * @see Response
 * @see ClientErrorDecoder
 */
@Getter
public final class Client<C extends Contract> implements AsyncAccess<C> {

    /** The immutable configuration bundle used to construct this client. */
    private final @NotNull ClientOptions<C> options;

    /** The underlying Apache HTTP client wrapped by Feign for connection pooling and transport. */
    @Getter(AccessLevel.NONE)
    private final @NotNull ApacheHttpClient internalClient;

    /** The route discovery instance that maps endpoint methods to target URLs and rate-limit configurations. */
    private final @NotNull RouteDiscovery routeDiscovery;

    /** The rate-limit manager that tracks per-route request budgets and enforces throttling. */
    private final @NotNull RateLimitManager rateLimitManager;

    /** The Feign-generated proxy implementing the contract interface, wrapped to unwrap internal exceptions. */
    private final @NotNull C contract;

    /**
     * The RFC 7234 response cache and merged observability facade, replacing the legacy
     * {@code recentResponses} list. Holds both the Caffeine cache used for conditional
     * revalidation and fresh-hit short-circuiting and the single-slot "last response"
     * reference exposed via {@link #getLastResponse()}.
     */
    private final @NotNull ResponseCache responseCache;

    /**
     * Constructs a new client from the given configuration bundle.
     * <p>
     * Discovers routes for the target contract interface, initializes the rate-limit manager,
     * instantiates the response cache from {@link Timings#maxCacheBytes()} and
     * {@link Timings#cacheSafetyFallback()}, builds the pooling Apache HTTP client, wraps it
     * in a {@link CachingFeignClient} that serves RFC 7234 cache hits transparently, and
     * assembles the Feign proxy through an exception-unwrapping dynamic proxy.
     *
     * @param options the immutable configuration bundle
     */
    private Client(@NotNull ClientOptions<C> options) {
        this.options = options;
        this.routeDiscovery = new RouteDiscovery(options.getTarget());
        this.rateLimitManager = new RateLimitManager();
        this.responseCache = new ResponseCache(
            options.getTimings().maxCacheBytes(),
            options.getTimings().cacheSafetyFallback()
        );
        this.internalClient = this.buildInternalClient();
        this.contract = this.wrapContractProxy(this.build());
    }

    /**
     * Creates a new {@code Client} from the given configuration bundle.
     *
     * @param <C> the contract interface type
     * @param options the immutable configuration bundle
     * @return a fully initialized client ready to issue requests
     */
    public static <C extends Contract> @NotNull Client<C> create(@NotNull ClientOptions<C> options) {
        return new Client<>(options);
    }

    // ===== Configuration access =====

    /**
     * Returns a {@link ClientOptions.Builder} pre-populated with this client's current options
     * for further modification.
     * <p>
     * Equivalent to {@code this.getOptions().mutate()}; provided as an instance method to support
     * the {@code client.mutate().withFoo(...).build()} idiom for deriving a configuration variant
     * before passing the result to {@link #create(ClientOptions)}.
     *
     * @return a builder pre-populated from this client's options
     */
    public @NotNull ClientOptions.Builder<C> mutate() {
        return this.options.mutate();
    }

    // ===== Runtime state =====

    /**
     * Retrieves the most recently observed response, whether successful or erroneous.
     * <p>
     * Delegates to {@link ResponseCache#getLastResponse()}, which returns the single-slot
     * reference updated by the decoder and error-decoder pipelines on every completed
     * exchange (including fresh cache hits replayed through the decoder). Because
     * {@link ApiException} implements {@link Response}, the same reference carries both
     * successful decodes and typed exceptions.
     *
     * @return an {@link Optional} containing the most recent {@link Response} if one has
     *         been observed, or {@link Optional#empty()} if the client has not yet issued
     *         a request
     */
    public @NotNull Optional<Response<?>> getLastResponse() {
        return this.responseCache.getLastResponse();
    }

    /**
     * Calculates the round-trip latency of the most recent HTTP request in milliseconds.
     * <p>
     * The latency is derived from the {@linkplain NetworkDetails#getRoundTrip() round-trip}
     * duration recorded in the most recent response's {@link NetworkDetails}. This includes DNS
     * resolution, TCP connect, TLS handshake, request transfer, server processing, and response
     * transfer.
     *
     * @return the total round-trip latency in milliseconds, or {@code -1} if no response has
     *         been recorded
     */
    public long getLatency() {
        return this.getLastResponse()
            .map(Response::getDetails)
            .map(NetworkDetails::getRoundTrip)
            .map(Stopwatch::durationMillis)
            .orElse(-1L);
    }

    // ===== Rate limit access =====

    /**
     * Checks whether the type-level default rate-limit bucket is currently exhausted.
     * <p>
     * Resolves the bucket key from the {@link Route @Route} declared on the endpoint interface
     * via {@link RouteDiscovery#getDefaultRoute()}. Convenient for single-domain endpoints where
     * every request shares one bucket.
     *
     * @return {@code true} if the default bucket exists and its request quota is exhausted;
     *         {@code false} otherwise
     */
    public boolean isRateLimited() {
        return this.isRateLimited(this.routeDiscovery.getDefaultRoute().getRoute());
    }

    /**
     * Checks whether the rate-limit bucket identified by the given key is currently exhausted.
     * <p>
     * If no bucket exists for the given identifier (i.e. no requests have been made to that
     * route yet), returns {@code false}.
     *
     * @param bucketId the route identifier used as the rate-limit bucket key, typically the
     *                 route string from a {@link Route @Route} annotation
     * @return {@code true} if the bucket exists and its request quota is exhausted;
     *         {@code false} otherwise
     * @see RateLimitManager#isRateLimited(String)
     */
    public boolean isRateLimited(@NotNull String bucketId) {
        return this.rateLimitManager.isRateLimited(bucketId);
    }

    /**
     * Checks whether the rate-limit bucket identified by the given route provider is currently
     * exhausted.
     * <p>
     * Equivalent to {@link #isRateLimited(String) isRateLimited(provider.getBucketId())}.
     * Convenient when querying a multi-domain endpoint where the bucket is identified by an
     * enum implementing {@link DynamicRouteProvider}.
     *
     * @param provider the dynamic route provider supplying the bucket identifier
     * @return {@code true} if the bucket exists and its request quota is exhausted;
     *         {@code false} otherwise
     */
    public boolean isRateLimited(@NotNull DynamicRouteProvider provider) {
        return this.isRateLimited(provider.getBucketId());
    }

    /**
     * Returns the number of remaining requests allowed for the type-level default rate-limit
     * bucket before the current window expires.
     *
     * @return the number of remaining allowed requests, or the unlimited sentinel value if no
     *         bucket exists for the type-level default route
     * @see RateLimitManager#getRemaining(String)
     */
    public long getRemainingRequests() {
        return this.getRemainingRequests(this.routeDiscovery.getDefaultRoute().getRoute());
    }

    /**
     * Returns the number of remaining requests allowed for the bucket identified by the given
     * key before the current window expires.
     *
     * @param bucketId the route identifier used as the rate-limit bucket key
     * @return the number of remaining allowed requests, or the unlimited sentinel value if the
     *         bucket does not exist
     * @see RateLimitManager#getRemaining(String)
     */
    public long getRemainingRequests(@NotNull String bucketId) {
        return this.rateLimitManager.getRemaining(bucketId);
    }

    /**
     * Returns the number of remaining requests allowed for the bucket identified by the given
     * route provider before the current window expires.
     *
     * @param provider the dynamic route provider supplying the bucket identifier
     * @return the number of remaining allowed requests, or the unlimited sentinel value if the
     *         bucket does not exist
     */
    public long getRemainingRequests(@NotNull DynamicRouteProvider provider) {
        return this.getRemainingRequests(provider.getBucketId());
    }

    // ===== Internal build helpers =====

    /**
     * Builds the pooling Apache HTTP client used as Feign's transport layer.
     * <p>
     * The resulting {@link ApacheHttpClient} is configured with a
     * {@link PoolingHttpClientConnectionManager} that uses {@link TimedPlainConnectionSocketFactory}
     * and {@link TimedSecureConnectionSocketFactory} to capture DNS, TCP, and TLS timings into the
     * {@link HttpContext} as {@link NetworkDetails} attributes; a request interceptor that records
     * the request start timestamp, propagates timing attributes as headers, and appends the
     * configured queries, headers, and dynamic headers from {@link ClientOptions}; pool limits and
     * timeouts derived from {@link Timings}; and an optional local IPv6 address binding from
     * {@link ClientOptions#getInet6Address()}.
     * <p>
     * Cache semantics (freshness, revalidation, stale-if-error replay, invalidation on unsafe
     * methods) live in {@link CachingFeignClient}, which wraps the returned Apache client before
     * it reaches Feign. The post-response pruning interceptor that used to live here has been
     * retired - entries in {@link ResponseCache} are evicted by per-entry TTL and weight,
     * bounded by {@link Timings#cacheSafetyFallback()} and {@link Timings#maxCacheBytes()}.
     *
     * @return a fully configured {@link ApacheHttpClient} ready for use by Feign
     */
    private @NotNull ApacheHttpClient buildInternalClient() {
        Timings timings = this.options.getTimings();

        @SuppressWarnings("deprecation")
        HttpClientBuilder httpClient = HttpClientBuilder.create()
            .setConnectionManager(new PoolingHttpClientConnectionManager(
                RegistryBuilder.<ConnectionSocketFactory>create()
                    .register("http", new TimedPlainConnectionSocketFactory(
                        PlainConnectionSocketFactory.getSocketFactory(),
                        SystemDefaultDnsResolver.INSTANCE
                    ))
                    .register("https", new TimedSecureConnectionSocketFactory(
                        SSLConnectionSocketFactory.getSocketFactory(),
                        SystemDefaultDnsResolver.INSTANCE
                    ))
                    .build()
            ))
            .addInterceptorFirst((HttpRequestInterceptor) (request, context) -> {
                context.setAttribute(NetworkDetails.REQUEST_START, Instant.now());

                // Store all available connection details in headers
                addHeader(request, context, NetworkDetails.REQUEST_START);
                addHeader(request, context, NetworkDetails.DNS_START);
                addHeader(request, context, NetworkDetails.DNS_END);
                addHeader(request, context, NetworkDetails.TCP_CONNECT_START);
                addHeader(request, context, NetworkDetails.TCP_CONNECT_END);
                addHeader(request, context, NetworkDetails.TLS_HANDSHAKE_START);
                addHeader(request, context, NetworkDetails.TLS_HANDSHAKE_END);
                addHeader(request, context, NetworkDetails.TLS_PROTOCOL);
                addHeader(request, context, NetworkDetails.TLS_CIPHER);

                // Append Custom Queries and Headers
                this.options.getQueries().forEach((key, value) -> request.getParams().setParameter(key, value));
                this.options.getHeaders().forEach((key, value) -> request.addHeader(key, value));
                this.options.getDynamicHeaders().forEach((key, supplier) -> supplier.get()
                    .ifPresent(value -> request.addHeader(key, value))
                );
            })
            .setMaxConnTotal(timings.maxConnections())
            .setMaxConnPerRoute(timings.maxConnectionsPerRoute())
            .evictIdleConnections(timings.connectionIdleTimeout(), TimeUnit.MILLISECONDS)
            .setConnectionTimeToLive(timings.connectionTimeToLive(), TimeUnit.MILLISECONDS)
            .setKeepAliveStrategy((response, context) -> {
                long keepAlive = DefaultConnectionKeepAliveStrategy.INSTANCE.getKeepAliveDuration(response, context);
                return (keepAlive == -1) ? timings.connectionKeepAlive() : Math.min(keepAlive, 60_000);
            });

        // Custom Local Address
        this.options.getInet6Address().ifPresent(inet6Address -> httpClient.setDefaultRequestConfig(
            RequestConfig.copy(RequestConfig.DEFAULT)
                .setLocalAddress(inet6Address)
                .build()
        ));

        return new ApacheHttpClient(httpClient.build());
    }

    /**
     * Builds a Feign proxy implementing the contract interface {@code C}.
     * <p>
     * The proxy is configured with the internal Apache HTTP client wrapped in a
     * {@link CachingFeignClient} so that RFC 7234 fresh-hit short-circuiting, conditional
     * revalidation, and unsafe-method invalidation happen transparently below Feign. The
     * {@linkplain ClientOptions#getEncoderFactory() encoder factory} and
     * {@linkplain ClientOptions#getDecoderFactory() decoder factory} from the options are
     * each invoked once with the configured {@link com.google.gson.Gson Gson}.
     * {@link feign.Feign.Builder#doNotCloseAfterDecode()} is set so that
     * {@link InternalResponseDecoder} can manage response body lifecycle for
     * {@link java.io.InputStream} return types.
     * <p>
     * The returned proxy is subsequently wrapped by {@link #wrapContractProxy(Contract)} to
     * strip internal exception wrappers before they reach callers.
     *
     * @return a Feign-generated proxy instance of type {@code C}
     */
    private @NotNull C build() {
        feign.Client cachingClient = new CachingFeignClient(this.internalClient, this.responseCache);

        return Feign.builder()
            .client(cachingClient)
            .encoder(this.options.getEncoderFactory().apply(this.options.getGson()))
            .decoder(new InternalResponseDecoder(
                this.options.getDecoderFactory().apply(this.options.getGson()),
                this.responseCache
            ))
            .errorDecoder(new InternalErrorDecoder(
                this.options.getErrorDecoder(),
                this.getRouteDiscovery(),
                this.responseCache
            ))
            .requestInterceptor(new InternalRequestInterceptor(
                this.getRateLimitManager(),
                this.getRouteDiscovery()
            ))
            .responseInterceptor(new InternalResponseInterceptor(
                this.getRateLimitManager(),
                this.getRouteDiscovery()
            ))
            .options(new feign.Request.Options(
                this.options.getTimings().connectTimeout(),
                TimeUnit.MILLISECONDS,
                this.options.getTimings().socketTimeout(),
                TimeUnit.MILLISECONDS,
                true
            ))
            .doNotCloseAfterDecode()
            .target(this.options.getTarget(), "https://placeholder");
    }

    /**
     * Wraps the given Feign proxy in a JDK dynamic proxy that unwraps internal exception types.
     * <p>
     * Feign's retry mechanism requires exceptions to extend {@link feign.RetryableException}, so
     * this client internally wraps typed {@link ApiException} instances in
     * {@link RetryableApiException}. This proxy intercepts all method invocations and, if the
     * underlying call throws a {@link RetryableApiException}, extracts and re-throws the original
     * {@link ApiException} so that callers see the correctly typed exception.
     *
     * @param <T> the contract proxy type
     * @param target the Feign-generated contract proxy to wrap
     * @return a dynamic proxy that transparently unwraps {@link RetryableApiException}
     */
    @SuppressWarnings("unchecked")
    private <T extends C> @NotNull T wrapContractProxy(@NotNull T target) {
        return (T) java.lang.reflect.Proxy.newProxyInstance(
            target.getClass().getClassLoader(),
            target.getClass().getInterfaces(),
            (proxy, method, args) -> {
                try {
                    return method.invoke(target, args);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();

                    // Unwrap our internal wrapper to expose the original typed exception
                    if (cause instanceof RetryableApiException retryable)
                        throw retryable.getWrappedException();

                    // Unwrap decode failures wrapped by InvocationContext
                    if (cause.getCause() instanceof ApiDecodeException decodeEx)
                        throw decodeEx;

                    throw cause;
                }
            }
        );
    }

    /**
     * Copies a named attribute from the {@link HttpContext} into the {@link HttpRequest} as a
     * header, if the attribute is present.
     * <p>
     * Used internally by the request interceptor to propagate network timing attributes from the
     * connection layer into request headers so they can be captured by {@link NetworkDetails}.
     *
     * @param request the outbound HTTP request to add the header to
     * @param context the HTTP context containing connection-layer attributes
     * @param id the attribute name and header name to propagate
     */
    private static void addHeader(@NotNull HttpRequest request, @NotNull HttpContext context, @NotNull String id) {
        Object value = context.getAttribute(id);

        if (value != null)
            request.addHeader(id, String.valueOf(value));
    }

}
