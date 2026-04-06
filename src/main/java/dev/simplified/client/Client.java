package dev.simplified.client;

import com.google.gson.Gson;
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
import dev.simplified.client.request.Endpoint;
import dev.simplified.client.request.ReactiveEndpoint;
import dev.simplified.client.request.Timings;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import dev.simplified.client.route.RouteDiscovery;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.reflection.Reflection;
import dev.simplified.util.time.Stopwatch;
import feign.Feign;
import feign.FeignException;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.gson.GsonDecoder;
import feign.gson.GsonEncoder;
import feign.httpclient.ApacheHttpClient;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponseInterceptor;
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
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.Inet6Address;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Abstract base class for all Feign-backed HTTP clients, providing connection pooling,
 * rate limiting, route discovery, request/response interception, error decoding, and
 * network timing instrumentation out of the box.
 * <p>
 * A {@code Client} is parameterized by an {@link Endpoint} interface whose methods declare
 * the remote HTTP operations (using Feign's {@code @RequestLine} annotations). During
 * construction the client:
 * <ol>
 *   <li>Resolves the concrete {@code E} type via {@link Reflection#getSuperClass(Object)}.</li>
 *   <li>Discovers routes from {@link dev.simplified.client.route.Route @Route} or
 *       {@link dev.simplified.client.route.DynamicRoute @DynamicRoute} annotations on the
 *       endpoint interface through {@link RouteDiscovery}.</li>
 *   <li>Builds a pooling Apache {@link ApacheHttpClient} with {@link TimedPlainConnectionSocketFactory} and
 *       {@link TimedSecureConnectionSocketFactory} for DNS, TCP, and TLS timing instrumentation.</li>
 *   <li>Assembles a Feign proxy that wires together Gson encoding/decoding, an
 *       {@link InternalRequestInterceptor} (route resolution + pre-request rate-limit
 *       enforcement), an {@link InternalResponseInterceptor} (server-side rate-limit header
 *       parsing), an {@link InternalErrorDecoder} (retry and rate-limit exception handling),
 *       and an {@link InternalResponseDecoder} (response caching + {@link Response} wrapping).</li>
 *   <li>Wraps the resulting Feign proxy in a JDK dynamic proxy that unwraps
 *       {@link RetryableApiException} so callers see the original typed
 *       {@link ApiException} rather than Feign's internal retry wrapper.</li>
 * </ol>
 * <p>
 * Subclasses customize behavior by overriding one or more {@code configure*} template methods:
 * <ul>
 *   <li>{@link #configureHeaders()} - static request headers (e.g. content type)</li>
 *   <li>{@link #configureDynamicHeaders()} - lazily-evaluated request headers (e.g. API keys)</li>
 *   <li>{@link #configureQueries()} - default query parameters appended to every request</li>
 *   <li>{@link #configureErrorDecoder()} - maps HTTP error responses to typed {@link ApiException} subclasses</li>
 *   <li>{@link #configureTimings()} - connection pool sizes, timeouts, keep-alive, and cache duration</li>
 * </ul>
 * <p>
 * Instances are typically registered as singletons in a service manager and retrieved via
 * a centralized client accessor.
 *
 * @param <E> the Feign endpoint interface type that declares the remote HTTP operations;
 *            must extend {@link Endpoint}
 * @see Endpoint
 * @see ReactiveEndpoint
 * @see RouteDiscovery
 * @see RateLimitManager
 * @see Timings
 * @see Response
 * @see ClientErrorDecoder
 */
@Getter
public abstract class Client<E extends Endpoint> implements ReactiveEndpoint<E> {

    /** The resolved {@link Class} object for the endpoint interface type {@code E}. */
    private final @NotNull Class<E> target;

    /** The optional IPv6 address used as the local address for outbound connections. */
    private final @NotNull Optional<Inet6Address> inet6Address;

    /** The underlying Apache HTTP client wrapped by Feign for connection pooling and transport. */
    @Getter(AccessLevel.NONE)
    private final @NotNull ApacheHttpClient internalClient;

    /** The route discovery instance that maps endpoint methods to target URLs and rate-limit configurations. */
    private final @NotNull RouteDiscovery routeDiscovery;

    /** The rate-limit manager that tracks per-route request budgets and enforces throttling. */
    private final @NotNull RateLimitManager rateLimitManager;

    /** The Feign-generated proxy implementing the endpoint interface, wrapped to unwrap internal exceptions. */
    private final @NotNull E endpoint;

    /** The timing configuration governing connection pool sizes, timeouts, keep-alive, and cache duration. */
    private final @NotNull Timings timings;

    /** The static query parameters appended to every outbound HTTP request. */
    private final @NotNull ConcurrentMap<String, String> queries;

    /** The static headers appended to every outbound HTTP request. */
    private final @NotNull ConcurrentMap<String, String> headers;

    /** The lazily-evaluated dynamic headers appended to every outbound HTTP request when present. */
    private final @NotNull ConcurrentMap<String, Supplier<Optional<String>>> dynamicHeaders;

    /** The bounded list of recent {@link Response} objects, automatically pruned by cache duration. */
    private final @NotNull ConcurrentList<Response<?>> recentResponses = Concurrent.newList();

    /** The error decoder that transforms HTTP error responses into typed {@link ApiException} instances. */
    private final @NotNull ClientErrorDecoder errorDecoder;

    /**
     * Returns the {@link Gson} instance used for Feign request encoding and response decoding.
     *
     * <p>Subclasses must implement this method to provide the application's configured Gson instance.
     * The method is called lazily during {@link #build()}, so the returned instance can reflect
     * the latest configuration.</p>
     *
     * @return the Gson instance to use for serialization
     */
    protected abstract @NotNull Gson getGson();

    /**
     * Constructs a new client with no IPv6 local address binding.
     * <p>
     * Equivalent to calling {@link #Client(Optional)} with {@link Optional#empty()}.
     */
    protected Client() {
        this(Optional.empty());
    }

    /**
     * Constructs a new client optionally bound to the specified IPv6 local address.
     * <p>
     * If {@code inet6Address} is non-null, all outbound connections from this client
     * will originate from that address. This is useful for IPv6 rotation strategies
     * to avoid per-IP rate limits.
     *
     * @param inet6Address the IPv6 address to bind outbound connections to, or {@code null}
     *                     for the system default
     */
    protected Client(@Nullable Inet6Address inet6Address) {
        this(Optional.ofNullable(inet6Address));
    }

    /**
     * Primary constructor that fully initializes the client.
     * <p>
     * Resolves the endpoint type {@code E} via reflection, discovers routes, initializes
     * the rate-limit manager, invokes all {@code configure*} template methods, builds
     * the pooling Apache HTTP client and Feign proxy, and wraps the proxy in an
     * exception-unwrapping dynamic proxy.
     *
     * @param inet6Address an {@link Optional} containing the IPv6 address to bind outbound
     *                     connections to, or {@link Optional#empty()} for the system default
     */
    protected Client(@NotNull Optional<Inet6Address> inet6Address) {
        this.target = Reflection.getSuperClass(this);
        this.inet6Address = inet6Address;
        this.routeDiscovery = new RouteDiscovery(this.getTarget());
        this.rateLimitManager = new RateLimitManager();
        this.timings = this.configureTimings();
        this.errorDecoder = this.configureErrorDecoder();
        this.queries = this.configureQueries();
        this.headers = this.configureHeaders();
        this.dynamicHeaders = this.configureDynamicHeaders();
        this.internalClient = this.configureInternalClient();
        this.endpoint = this.configureUnwrappingProxy(this.build());
    }

    /**
     * Builds and configures the pooling Apache HTTP client used as Feign's transport layer.
     * <p>
     * The resulting {@link ApacheHttpClient} is configured with:
     * <ul>
     *   <li>A {@link PoolingHttpClientConnectionManager} using {@link TimedPlainConnectionSocketFactory}
     *       for HTTP and {@link TimedSecureConnectionSocketFactory} for HTTPS, both instrumented
     *       to capture DNS resolution, TCP connect, and TLS handshake timings into the
     *       {@link HttpContext} as {@link NetworkDetails} attributes.</li>
     *   <li>A request interceptor that records the request start timestamp and propagates
     *       all timing attributes as internal headers, then appends the configured
     *       {@linkplain #getQueries() query parameters}, {@linkplain #getHeaders() static headers},
     *       and {@linkplain #getDynamicHeaders() dynamic headers} to every request.</li>
     *   <li>A response interceptor that records the response-received timestamp and prunes
     *       expired entries from {@link #getRecentResponses()} based on the
     *       {@linkplain Timings#getCacheDuration() cache duration}.</li>
     *   <li>Connection pool limits from {@link Timings#getMaxConnections()} and
     *       {@link Timings#getMaxConnectionsPerRoute()}.</li>
     *   <li>Idle connection eviction, time-to-live, and keep-alive strategies derived from
     *       {@link Timings}.</li>
     *   <li>An optional local IPv6 address binding from {@link #getInet6Address()}.</li>
     * </ul>
     *
     * @return a fully configured {@link ApacheHttpClient} ready for use by Feign
     */
    protected final @NotNull ApacheHttpClient configureInternalClient() {
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
                this.getQueries().forEach((key, value) -> request.getParams().setParameter(key, value));
                this.getHeaders().forEach((key, value) -> request.addHeader(key, value));
                this.getDynamicHeaders().forEach((key, supplier) -> supplier.get()
                    .ifPresent(value -> request.addHeader(key, value))
                );
            })
            .addInterceptorLast((HttpResponseInterceptor) (response, context) -> {
                Instant responseReceived = Instant.now();
                context.setAttribute(NetworkDetails.RESPONSE_RECEIVED, responseReceived);
                response.addHeader(NetworkDetails.RESPONSE_RECEIVED, responseReceived.toString());

                if (this.recentResponses.size() > this.getTimings().getMaxCacheSize()) {
                    long cutoff = System.currentTimeMillis() - this.getTimings().getCacheDuration();
                    this.recentResponses.removeIf(r -> r.getDetails().getRoundTrip().getCompletedAt().toEpochMilli() < cutoff);
                }
            })
            .setMaxConnTotal(this.getTimings().getMaxConnections())
            .setMaxConnPerRoute(this.getTimings().getMaxConnectionsPerRoute())
            .evictIdleConnections(this.getTimings().getConnectionIdleTimeout(), TimeUnit.MILLISECONDS)
            .setConnectionTimeToLive(this.getTimings().getConnectionTimeToLive(), TimeUnit.MILLISECONDS)
            .setKeepAliveStrategy((response, context) -> {
                long keepAlive = DefaultConnectionKeepAliveStrategy.INSTANCE.getKeepAliveDuration(response, context);
                return (keepAlive == -1) ? this.getTimings().getConnectionKeepAlive() : Math.min(keepAlive, 60_000);
            });

        // Custom Local Address
        this.getInet6Address().ifPresent(inet6Address -> httpClient.setDefaultRequestConfig(
            RequestConfig.copy(RequestConfig.DEFAULT)
                .setLocalAddress(inet6Address)
                .build()
        ));

        return new ApacheHttpClient(httpClient.build());
    }

    /**
     * Builds a Feign proxy implementing the endpoint interface {@code E}.
     * <p>
     * The proxy is configured with:
     * <ul>
     *   <li>The {@linkplain #configureInternalClient() internal Apache HTTP client} as the transport.</li>
     *   <li>The {@link Encoder} returned by {@link #configureEncoder()} - {@link GsonEncoder} by default.</li>
     *   <li>{@link feign.Feign.Builder#doNotCloseAfterDecode()} to allow the {@link InternalResponseDecoder}
     *       to manage response body lifecycle - required for {@link java.io.InputStream} return types where
     *       the body must remain open for the caller to stream.</li>
     *   <li>An {@link InternalResponseDecoder} that routes decoding by return type: {@link java.io.InputStream}
     *       returns the raw body stream (caller-managed lifecycle), {@code byte[]} delegates to Feign's
     *       default binary decoder, and all other types delegate to the {@link Decoder} returned by
     *       {@link #configureDecoder()} ({@link GsonDecoder} by default). Non-streaming responses are
     *       wrapped into {@link Response} objects and added to {@link #getRecentResponses()}.</li>
     *   <li>An {@link InternalErrorDecoder} that delegates to the configured {@link #getErrorDecoder()},
     *       handles rate-limit (HTTP 429) responses, tracks retry attempts, and wraps retryable errors
     *       in {@link RetryableApiException} for Feign's retry mechanism.</li>
     *   <li>An {@link InternalRequestInterceptor} that enforces pre-request rate limits via
     *       {@link #getRateLimitManager()} and resolves target URLs via {@link #getRouteDiscovery()}.</li>
     *   <li>An {@link InternalResponseInterceptor} that parses rate-limit headers from server responses
     *       and updates the {@link #getRateLimitManager()} accordingly.</li>
     *   <li>Connect and socket timeouts from {@link #getTimings()}.</li>
     * </ul>
     * <p>
     * The returned proxy is subsequently wrapped by {@code configureUnwrappingProxy} to strip
     * internal exception wrappers before they reach callers.
     *
     * @return a Feign-generated proxy instance of type {@code E}
     */
    public final @NotNull E build() {
        return Feign.builder()
            .client(this.internalClient)
            .encoder(this.configureEncoder())
            .doNotCloseAfterDecode()
            .decoder(new InternalResponseDecoder(
                this.configureDecoder(),
                this.getRecentResponses()
            ))
            .errorDecoder(new InternalErrorDecoder(
                this.getErrorDecoder(),
                this.getRouteDiscovery(),
                this.getRecentResponses()
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
                this.getTimings().getConnectTimeout(),
                TimeUnit.MILLISECONDS,
                this.getTimings().getSocketTimeout(),
                TimeUnit.MILLISECONDS,
                true
            ))
            .target(this.getTarget(), "https://placeholder");
    }

    /**
     * Wraps the given Feign proxy in a JDK dynamic proxy that unwraps internal exception types.
     * <p>
     * Feign's retry mechanism requires exceptions to extend {@link feign.RetryableException},
     * so this client internally wraps typed {@link ApiException} instances in
     * {@link RetryableApiException}. This proxy intercepts all method invocations and, if the
     * underlying call throws a {@link RetryableApiException}, extracts and re-throws the
     * original {@link ApiException} so that callers see the correctly typed exception.
     *
     * @param <T> the endpoint proxy type
     * @param target the Feign-generated endpoint proxy to wrap
     * @return a dynamic proxy that transparently unwraps {@link RetryableApiException}
     */
    @SuppressWarnings("unchecked")
    private <T extends E> @NotNull T configureUnwrappingProxy(@NotNull T target) {
        return (T) Proxy.newProxyInstance(
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
     * Returns the static query parameters to append to every outbound HTTP request.
     * <p>
     * The default implementation returns an empty unmodifiable map. Subclasses may override
     * this method to supply default query parameters that should be present on all requests
     * made through this client.
     *
     * @return a {@link ConcurrentMap} of query parameter names to values
     */
    protected @NotNull ConcurrentMap<String, String> configureQueries() {
        return Concurrent.newUnmodifiableMap();
    }

    /**
     * Returns the static headers to append to every outbound HTTP request.
     * <p>
     * The default implementation returns an empty unmodifiable map. Subclasses may override
     * this method to supply default headers (e.g. content type, user agent) that should be
     * present on all requests made through this client.
     *
     * @return a {@link ConcurrentMap} of header names to values
     */
    protected @NotNull ConcurrentMap<String, String> configureHeaders() {
        return Concurrent.newUnmodifiableMap();
    }

    /**
     * Returns the dynamic headers to evaluate and append to every outbound HTTP request.
     * <p>
     * Unlike {@link #configureHeaders()}, each entry's value is a {@link Supplier} that is
     * invoked at request time. If the supplier returns {@link Optional#empty()}, the header
     * is omitted for that request. This is useful for headers whose values change over time,
     * such as API keys loaded from a {@link dev.simplified.manager.KeyManager KeyManager}.
     * <p>
     * The default implementation returns an empty unmodifiable map.
     *
     * @return a {@link ConcurrentMap} of header names to lazily-evaluated optional value suppliers
     */
    protected @NotNull ConcurrentMap<String, Supplier<Optional<String>>> configureDynamicHeaders() {
        return Concurrent.newUnmodifiableMap();
    }

    /**
     * Returns the error decoder used to transform HTTP error responses into typed exceptions.
     * <p>
     * The default implementation creates an {@link ApiException} from the Feign error status.
     * Subclasses should override this method to return a {@link ClientErrorDecoder} that
     * produces API-specific exception types.
     *
     * @return a {@link ClientErrorDecoder} that maps error responses to {@link ApiException} instances
     * @see ClientErrorDecoder
     * @see InternalErrorDecoder
     */
    protected @NotNull ClientErrorDecoder configureErrorDecoder() {
        return (methodKey, response) -> new ApiException(
            FeignException.errorStatus(
                methodKey,
                response,
                null,
                null
            ),
            response,
            "Client"
        );
    }

    /**
     * Returns the timing configuration for this client's connection pool and request behavior.
     * <p>
     * The default implementation returns {@link Timings#createDefault()}, which provides
     * sensible defaults for connection pool sizes, timeouts, keep-alive durations, and
     * response cache duration. Subclasses may override this method to tune these parameters
     * for specific API characteristics.
     *
     * @return a {@link Timings} instance defining connection and request timing parameters
     * @see Timings#createDefault()
     */
    protected @NotNull Timings configureTimings() {
        return Timings.createDefault();
    }

    /**
     * Returns the Feign {@link Encoder} used to serialize outbound request bodies.
     * <p>
     * The default implementation returns a {@link GsonEncoder} wrapping the {@link Gson}
     * instance from {@link #getGson()}, which is appropriate for JSON-based APIs.
     * Subclasses may override this method to substitute an alternative encoder - for
     * example, an XML encoder for RSS/Atom endpoints or a form-URL-encoded encoder for
     * legacy APIs.
     * <p>
     * The returned encoder is consumed once by {@link #build()} during Feign proxy
     * construction, so overrides may rely on a freshly constructed instance per client.
     *
     * @return the {@link Encoder} to use for request body serialization
     * @see GsonEncoder
     */
    protected @NotNull Encoder configureEncoder() {
        return new GsonEncoder(this.getGson());
    }

    /**
     * Returns the Feign {@link Decoder} used to deserialize inbound response bodies.
     * <p>
     * The returned decoder is wrapped by {@link InternalResponseDecoder}, which routes
     * {@link java.io.InputStream} and {@code byte[]} return types to dedicated handlers
     * and delegates all other return types to this decoder. The default implementation
     * returns a {@link GsonDecoder} wrapping the {@link Gson} instance from
     * {@link #getGson()}, which is appropriate for JSON-based APIs.
     * <p>
     * Subclasses may override this method to substitute an alternative decoder - for
     * example, an XML decoder that converts responses into a Gson {@link com.google.gson.JsonElement}
     * tree before handing off to the shared {@link Gson} instance, preserving all
     * registered {@code TypeAdapter}s and {@code TypeAdapterFactory}s.
     * <p>
     * The returned decoder is consumed once by {@link #build()} during Feign proxy
     * construction, so overrides may rely on a freshly constructed instance per client.
     *
     * @return the {@link Decoder} to use for response body deserialization
     * @see GsonDecoder
     * @see InternalResponseDecoder
     */
    protected @NotNull Decoder configureDecoder() {
        return new GsonDecoder(this.getGson());
    }

    /**
     * Retrieves the most recent response from the list of cached responses.
     * <p>
     * The most recent response is determined by comparing the
     * {@linkplain NetworkDetails#getRoundTrip() round-trip} completion timestamp of each
     * cached entry. The response cache is automatically pruned based on the
     * {@linkplain Timings#getCacheDuration() cache duration} configured in {@link #getTimings()}.
     *
     * @return an {@link Optional} containing the most recent {@link Response} if the cache
     *         is non-empty, or {@link Optional#empty()} if no responses have been recorded
     */
    public final @NotNull Optional<Response<?>> getLastResponse() {
        return this.getRecentResponses().findLast();
    }

    /**
     * Calculates the round-trip latency of the most recent HTTP request in milliseconds.
     * <p>
     * The latency is derived from the {@linkplain NetworkDetails#getRoundTrip() round-trip}
     * duration recorded in the most recent response's {@link NetworkDetails}. This includes DNS
     * resolution, TCP connect, TLS handshake, request transfer, server processing, and
     * response transfer.
     *
     * @return the total round-trip latency in milliseconds, or {@code -1} if no response
     *         has been recorded
     */
    public final long getLatency() {
        return this.getLastResponse()
            .map(Response::getDetails)
            .map(NetworkDetails::getRoundTrip)
            .map(Stopwatch::getDurationMillis)
            .orElse(-1L);
    }

    /**
     * Returns the number of remaining requests allowed for the specified rate-limit bucket
     * before the current window expires.
     * <p>
     * If no bucket exists for the given identifier (i.e. no requests have been made to that
     * route yet), returns {@link dev.simplified.client.ratelimit.RateLimit#UNLIMITED the unlimited
     * limit value}.
     *
     * @param bucketId the route identifier used as the rate-limit bucket key, typically the
     *                 route string from a {@link dev.simplified.client.route.Route @Route} annotation
     * @return the number of remaining allowed requests, or {@code Long.MAX_VALUE} if the
     *         bucket does not exist
     * @see RateLimitManager#getRemaining(String)
     */
    public final long getRemainingRequests(@NotNull String bucketId) {
        return this.rateLimitManager.getRemaining(bucketId);
    }

    /**
     * Checks whether the specified rate-limit bucket is currently exhausted.
     * <p>
     * A bucket is considered rate-limited when its request count has reached the configured
     * limit and the current time window has not yet expired. If no bucket exists for the
     * given identifier, this method returns {@code false}.
     *
     * @param bucketId the route identifier used as the rate-limit bucket key
     * @return {@code true} if the bucket exists and its request quota is exhausted;
     *         {@code false} otherwise
     * @see RateLimitManager#isRateLimited(String)
     */
    public final boolean isRateLimited(@NotNull String bucketId) {
        return this.rateLimitManager.isRateLimited(bucketId);
    }

    /**
     * Copies a named attribute from the {@link HttpContext} into the {@link HttpRequest}
     * as a header, if the attribute is present.
     * <p>
     * This is used internally by the request interceptor to propagate network timing
     * attributes (DNS time, TCP connect time, TLS handshake time, etc.) from the
     * connection layer into request headers so they can be captured by
     * {@link NetworkDetails}.
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
