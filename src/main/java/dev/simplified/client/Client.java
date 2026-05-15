package dev.simplified.client;

import dev.simplified.client.cache.CachingFeignClient;
import dev.simplified.client.cache.ResponseCache;
import dev.simplified.client.decoder.ClientErrorDecoder;
import dev.simplified.client.decoder.InternalErrorDecoder;
import dev.simplified.client.decoder.InternalResponseDecoder;
import dev.simplified.client.exception.ApiDecodeException;
import dev.simplified.client.exception.ApiException;
import dev.simplified.client.exception.RetryableApiException;
import dev.simplified.client.factory.TimedConnectionOperator;
import dev.simplified.client.factory.TimedTlsSocketStrategy;
import dev.simplified.client.interceptor.InternalRequestInterceptor;
import dev.simplified.client.interceptor.InternalResponseInterceptor;
import dev.simplified.client.factory.ApacheClientFactory;
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
import feign.hc5.ApacheHttp5Client;
import lombok.AccessLevel;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Feign-backed HTTP client providing connection pooling, rate limiting, route discovery,
 * request/response interception, error decoding, and network timing instrumentation.
 * <p>
 * A {@code Client} is parameterized by a {@link Contract} interface whose methods declare the
 * remote HTTP operations (using Feign's {@code @RequestLine} annotations) and is constructed via
 * {@link #create(ClientConfig)} from an immutable {@link ClientConfig} bundle. All customization
 * - headers, queries, dynamic headers, timings, error decoder, encoder/decoder factories, IPv6
 * binding - lives on the options; the client itself owns only the runtime state needed to execute
 * requests.
 * <p>
 * During construction the client discovers routes from {@link Route @Route} or
 * {@link dev.simplified.client.route.DynamicRoute @DynamicRoute} annotations on the contract
 * interface through {@link RouteDiscovery}, instantiates a {@link ResponseCache} for both
 * conditional revalidation and {@code getLastResponse()} observability, builds a pooling
 * Apache {@link ApacheHttp5Client} with {@link TimedConnectionOperator} and
 * {@link TimedTlsSocketStrategy} for DNS, TCP, and TLS timing instrumentation,
 * wraps the Apache client in a {@link CachingFeignClient} that serves RFC 7234 cache hits
 * transparently, assembles a Feign proxy that wires together encoding, decoding, request
 * and response interceptors, and the configured error decoder, and finally wraps the
 * resulting Feign proxy in a JDK dynamic proxy that unwraps {@link RetryableApiException}
 * so callers see the original typed {@link ApiException} rather than Feign's internal
 * retry wrapper.
 * <p>
 * To produce a derived client that shares most of an existing client's configuration, call
 * {@link #mutate()} to obtain a {@link ClientConfig.Builder} seeded from the current options,
 * adjust the differing fields, and pass the result to {@link #create(ClientConfig)}.
 *
 * @param <C> the Feign contract interface type that declares the remote HTTP operations;
 *            must extend {@link Contract}
 * @see ClientConfig
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
    private final @NotNull ClientConfig<C> options;

    /** The pooling Apache HTTP/5 transport used for request execution. */
    @Getter(AccessLevel.NONE)
    private final @NotNull feign.Client internalClient;

    /** The route discovery instance that maps endpoint methods to target URLs and rate-limit configurations. */
    private final @NotNull RouteDiscovery routeDiscovery;

    /** The rate-limit manager that tracks per-route request budgets and enforces throttling. */
    private final @NotNull RateLimitManager rateLimitManager;

    /** The Feign-generated proxy implementing the contract interface, wrapped to unwrap internal exceptions. */
    private final @NotNull C contract;

    /**
     * The Gson instance built once from {@link ClientConfig#getGsonSettings()} with the
     * contract's return types injected as prewarm targets. Cached here so the encoder,
     * decoder, and custom error-decoder factories receive a single warm Gson instead of
     * each independently invoking {@link dev.simplified.gson.GsonSettings#create()}.
     */
    @Getter(AccessLevel.NONE)
    private final @NotNull com.google.gson.Gson gson;

    /**
     * The RFC 7234 response cache and merged observability facade, replacing the legacy
     * {@code recentResponses} list. Holds both the Caffeine cache used for conditional
     * revalidation and fresh-hit short-circuiting and the single-slot "last response"
     * reference exposed via {@link #getLastResponse()}.
     */
    private final @NotNull ResponseCache responseCache;

    /**
     * Constructs a new client from the given configuration bundle and pre-built Feign transport.
     * <p>
     * Discovers routes for the target contract interface, initializes the rate-limit manager,
     * instantiates the response cache from {@link Timings#maxCacheBytes()} and
     * {@link Timings#cacheSafetyFallback()}, wraps the supplied transport in a
     * {@link CachingFeignClient}, and assembles the Feign proxy through an exception-unwrapping
     * dynamic proxy. The constructor fires DNS and HEAD-probe prewarms on virtual threads so
     * the first real request finds a warm pool; prewarm failures never propagate.
     *
     * <p>Package-private so that an in-package test fixture can substitute a non-production
     * transport (canned client, custom TLS) without exposing an override on
     * {@link ClientConfig}'s public surface. Production callers use
     * {@link #create(ClientConfig)}, which always builds an {@link ApacheHttp5Client}.</p>
     *
     * @param options the immutable configuration bundle
     * @param internalClient the Feign transport used for request execution
     */
    Client(@NotNull ClientConfig<C> options, @NotNull feign.Client internalClient) {
        this.options = options;
        this.internalClient = internalClient;
        this.routeDiscovery = new RouteDiscovery(options.getTarget());
        this.rateLimitManager = new RateLimitManager();
        this.responseCache = new ResponseCache(
            options.getTimings().maxCacheBytes(),
            options.getTimings().cacheSafetyFallback()
        );
        // Derive a contract-aware GsonSettings: every response type the contract advertises
        // is added as a prewarm target so GsonSettings.create() generates the matching
        // TypeAdapter eagerly. First-request cost drops by 1-5 ms per type.
        this.gson = options.getGsonSettings()
            .mutate()
            .withPrewarmTypes(walkContractTypes(options.getTarget()))
            .build()
            .create();
        this.contract = this.wrapContractProxy(this.build());

        java.util.Set<String> hosts = this.routeDiscovery.collectAdvertisedHosts();
        if (!hosts.isEmpty()) {
            ApacheClientFactory.prewarmDns(hosts);
            ApacheClientFactory.prewarm(hosts, this.internalClient);
        }
    }

    /**
     * Creates a new {@code Client} from the given configuration bundle, backed by a fresh
     * pooling {@link ApacheHttp5Client}.
     *
     * @param <C> the contract interface type
     * @param options the immutable configuration bundle
     * @return a fully initialized client ready to issue requests
     */
    public static <C extends Contract> @NotNull Client<C> create(@NotNull ClientConfig<C> options) {
        return new Client<>(options, buildProductionTransport(options));
    }

    private static @NotNull feign.Client buildProductionTransport(@NotNull ClientConfig<?> options) {
        return new ApacheHttp5Client(ApacheClientFactory.configure(
            options.getTimings(),
            options.getQueries(),
            options.getHeaders(),
            options.getDynamicHeaders(),
            options.getInet6Address()
        ).build());
    }

    // ===== Configuration access =====

    /**
     * Returns a {@link ClientConfig.Builder} pre-populated with this client's current options
     * for further modification.
     * <p>
     * Equivalent to {@code this.getOptions().mutate()}; provided as an instance method to support
     * the {@code client.mutate().withFoo(...).build()} idiom for deriving a configuration variant
     * before passing the result to {@link #create(ClientConfig)}.
     *
     * @return a builder pre-populated from this client's options
     */
    public @NotNull ClientConfig.Builder<C> mutate() {
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
     * Walks the contract interface's declared methods and extracts the deserialized payload
     * type of every {@code Response<T>}-returning method. The resulting list seeds the
     * {@link dev.simplified.gson.GsonSettings#getPrewarmTypes() Gson prewarm list} so adapter
     * generation for known response shapes runs at construction time rather than on first
     * decode.
     *
     * <p>Methods that return raw {@code Response} (no type parameter) or whose return type is
     * not parameterised are skipped. Streaming bodies ({@code Response<InputStream>}) are
     * included but Gson skips them harmlessly at adapter-resolution time.</p>
     *
     * @param target the contract interface class
     * @return the list of payload types to prewarm
     */
    private static @NotNull java.util.List<java.lang.reflect.Type> walkContractTypes(@NotNull Class<? extends Contract> target) {
        java.util.List<java.lang.reflect.Type> types = new java.util.ArrayList<>();
        for (java.lang.reflect.Method method : target.getDeclaredMethods()) {
            java.lang.reflect.Type returnType = method.getGenericReturnType();
            if (!(returnType instanceof java.lang.reflect.ParameterizedType parameterized)) continue;
            if (parameterized.getRawType() != Response.class) continue;
            java.lang.reflect.Type[] args = parameterized.getActualTypeArguments();
            if (args.length > 0) types.add(args[0]);
        }
        return types;
    }

    /**
     * Builds a Feign proxy implementing the contract interface {@code C}.
     * <p>
     * The proxy is configured with the internal Apache HTTP client wrapped in a
     * {@link CachingFeignClient} so that RFC 7234 fresh-hit short-circuiting, conditional
     * revalidation, and unsafe-method invalidation happen transparently below Feign. The
     * {@linkplain ClientConfig#getEncoderFactory() encoder factory} and
     * {@linkplain ClientConfig#getDecoderFactory() decoder factory} from the options are
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
            .encoder(this.options.getEncoderFactory().apply(this.gson))
            .decoder(new InternalResponseDecoder(
                this.options.getDecoderFactory().apply(this.gson),
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

}
