package dev.simplified.client.factory;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.client.Client;
import dev.simplified.client.cache.CacheKey;
import dev.simplified.client.request.Timings;
import dev.simplified.client.response.NetworkDetails;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.ChainElement;
import org.apache.hc.client5.http.impl.DefaultConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
import org.apache.hc.client5.http.routing.HttpRoutePlanner;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpMessage;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.EofSensorInputStream;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * Shared factory that assembles a fully configured Apache {@link HttpClientBuilder} for use
 * by the {@link Client contract-based client} and the standalone URL fetcher.
 * <p>
 * The returned builder carries:
 * <ul>
 *   <li>A {@link PoolingHttpClientConnectionManager} wired with a
 *       {@link TimedConnectionOperator} (DNS + TCP timing) and a
 *       {@link TimedTlsSocketStrategy} (TLS handshake timing + protocol/cipher metadata)
 *       so that connection-level observability lands on the {@link HttpContext} as
 *       {@link NetworkDetails} attributes.</li>
 *   <li>A request interceptor that records the request-start timestamp on the
 *       {@link HttpContext}, removes the {@linkplain NetworkDetails#CLIENT_HEADERS headers the
 *       client writes itself} from the outbound request, so none of them reaches the origin while
 *       a caller's own header is sent whatever its name, and appends the configured static
 *       queries to the request URL. A caller's response cache keys each request by a URL ending
 *       with the queries' {@linkplain CacheKey#queryFingerprints(Map) stand-in} in their
 *       place.</li>
 *   <li>A response interceptor that removes every internal header the origin sent and records
 *       the {@linkplain NetworkDetails#CONNECTION_HEADERS connection markers} the context holds
 *       on the response in their place, so a caller that sees only the response - a Feign
 *       transport - reads the same DNS, TCP and TLS timings and TLS protocol and cipher as one
 *       that reads the context.</li>
 *   <li>A pair of execution handlers that has every response body read through an
 *       {@link EofSensorInputStream} whose abort closes the exchange's connection without
 *       reading the body, a body the client decodes for its {@code Content-Encoding} as well as
 *       one it does not, so a caller that sees only the body's stream - a Feign transport - can
 *       abandon a response without downloading it.</li>
 *   <li>Pool sizing, eviction, keep-alive, and connection time-to-live derived from the
 *       given {@link Timings}.</li>
 *   <li>The {@link Timings}' timeouts as the defaults of every request the client sends:
 *       {@link Timings#connectTimeout()} bounds the TCP connect and, through it, the TLS
 *       handshake, and {@link Timings#socketTimeout()} bounds each wait for data once connected.
 *       A Feign transport overrides both per request with the connect and read timeouts its
 *       request options carry.</li>
 *   <li>When an IPv6 address is supplied, a route planner that names it as the local address of
 *       every route, so each connection the client opens binds its socket to that address before
 *       connecting and the origin sees it as the peer. A host reachable only over IPv4 cannot be
 *       reached from it: the connection fails rather than falling back to the system default
 *       address.</li>
 * </ul>
 * <p>
 * Each caller wraps the resulting builder as it sees fit: {@code Client} runs
 * {@code new ApacheHttp5Client(builder.build())} for Feign integration, while
 * {@code UrlFetcher} calls {@code builder.build()} directly to obtain a raw
 * {@link CloseableHttpClient CloseableHttpClient}.
 * <p>
 * The configured static and dynamic headers are not applied here: each caller adds them to the
 * request it hands the transport, where its response cache sees the values the origin receives.
 *
 * @see Client
 * @see Timings
 * @see NetworkDetails
 */
@UtilityClass
public final class ApacheClientFactory {

    /**
     * The request-context attribute holding the stream the transport reads the body of the
     * latest response through, off the exchange it arrived on.
     */
    private static final @NotNull String EXCHANGE_ATTRIBUTE = ApacheClientFactory.class.getName() + ".exchange";

    /**
     * Configures a new {@link HttpClientBuilder} with shared client infrastructure, using
     * the JDK default TLS strategy. Equivalent to
     * {@link #configure(Timings, Map, Optional, TlsSocketStrategy) configure(...,
     * DefaultClientTlsStrategy.createSystemDefault())}.
     *
     * @param timings the connection pool, timeout, and keep-alive configuration
     * @param queries static query parameters appended to every outbound request URL
     * @param inet6Address the local IPv6 address every connection binds to before connecting, or
     *                     empty to connect from the system default address
     * @return a configured {@link HttpClientBuilder} ready to be {@code build()}-ed or further
     *         customized by the caller
     */
    public static @NotNull HttpClientBuilder configure(
        @NotNull Timings timings,
        @NotNull Map<String, String> queries,
        @NotNull Optional<Inet6Address> inet6Address
    ) {
        return configure(timings, queries, inet6Address, DefaultClientTlsStrategy.createSystemDefault());
    }

    /**
     * Configures a new {@link HttpClientBuilder} with shared client infrastructure and a
     * caller-supplied {@link TlsSocketStrategy}. Pass
     * {@link DefaultClientTlsStrategy#createSystemDefault()} for default trust-store behaviour;
     * a custom strategy enables mTLS, alternative trust stores, or trust-all configurations
     * used by loopback test rigs.
     *
     * @param timings the connection pool, timeout, and keep-alive configuration
     * @param queries static query parameters appended to every outbound request URL
     * @param inet6Address the local IPv6 address every connection binds to before connecting, or
     *                     empty to connect from the system default address
     * @param tlsDelegate the TLS socket strategy applied to HTTPS routes; wrapped by
     *                    {@link TimedTlsSocketStrategy} so handshake timings are still captured
     * @return a configured {@link HttpClientBuilder} ready to be {@code build()}-ed or further
     *         customized by the caller
     */
    public static @NotNull HttpClientBuilder configure(
        @NotNull Timings timings,
        @NotNull Map<String, String> queries,
        @NotNull Optional<Inet6Address> inet6Address,
        @NotNull TlsSocketStrategy tlsDelegate
    ) {
        HttpClientBuilder builder = HttpClientBuilder.create()
            .setConnectionManager(connectionManager(timings, timedOperator(tlsDelegate)))
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setResponseTimeout(Timeout.ofMilliseconds(timings.socketTimeout()))
                    .build()
            )
            // Feign's retryer drives retry semantics at the request layer. HC 5's default
            // HttpRequestRetryStrategy auto-retries 5xx responses, which both double-counts
            // retries and causes single 503s to take seconds while HC 5 retries internally.
            // Disable HC 5's transport-level retries entirely.
            .disableAutomaticRetries()
            .evictIdleConnections(TimeValue.ofMilliseconds(timings.connectionIdleTimeout()))
            .addRequestInterceptorFirst((request, entityDetails, context) -> {
                context.setAttribute(NetworkDetails.REQUEST_START, Instant.now());
                removeHeaders(request, NetworkDetails::isClientHeader);

                if (!queries.isEmpty()) appendQueryParameters(request, queries);
            })
            .addResponseInterceptorFirst((response, entityDetails, context) -> {
                removeHeaders(response, NetworkDetails::isInternalHeader);

                for (String marker : NetworkDetails.CONNECTION_HEADERS) {
                    Object value = context.getAttribute(marker);

                    if (value != null)
                        response.addHeader(marker, String.valueOf(value));
                }
            })
            .addExecInterceptorBefore(ChainElement.PROTOCOL.name(), "capture-exchange", ApacheClientFactory::captureExchange)
            .addExecInterceptorFirst("abortable-body", ApacheClientFactory::abortableBody)
            .setKeepAliveStrategy((response, context) -> {
                TimeValue keepAlive = DefaultConnectionKeepAliveStrategy.INSTANCE.getKeepAliveDuration(response, context);
                if (keepAlive == null || keepAlive.getDuration() < 0)
                    return TimeValue.ofMilliseconds(timings.connectionKeepAlive());
                long capped = Math.min(keepAlive.toMilliseconds(), 60_000L);
                return TimeValue.ofMilliseconds(capped);
            });

        inet6Address.ifPresent(localAddress -> builder.setRoutePlanner(bindingTo(localAddress)));
        return builder;
    }

    /**
     * Builds the operator every pooled connection opens through, timing DNS resolution and the TCP
     * connect, and on HTTPS routes the TLS handshake through a {@link TimedTlsSocketStrategy}
     * around the given strategy.
     *
     * @param tlsDelegate the TLS socket strategy applied to HTTPS routes
     * @return the timed connection operator
     */
    private static @NotNull HttpClientConnectionOperator timedOperator(@NotNull TlsSocketStrategy tlsDelegate) {
        return new TimedConnectionOperator(
            null,
            SystemDefaultDnsResolver.INSTANCE,
            RegistryBuilder.<TlsSocketStrategy>create()
                .register(URIScheme.HTTPS.id, new TimedTlsSocketStrategy(tlsDelegate))
                .build()
        );
    }

    /**
     * Builds the connection pool the client draws connections from, sized, aged and timed by the
     * given {@link Timings}: at most {@link Timings#maxConnections()} connections and
     * {@link Timings#maxConnectionsPerRoute()} per route, each living
     * {@link Timings#connectionTimeToLive()} and opened with the connect and socket timeouts.
     *
     * @param timings the pool sizing, time-to-live and timeout configuration
     * @param operator the operator every connection opens through
     * @return the connection manager
     */
    private static @NotNull PoolingHttpClientConnectionManager connectionManager(
        @NotNull Timings timings,
        @NotNull HttpClientConnectionOperator operator
    ) {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(
            operator,
            PoolConcurrencyPolicy.LAX,
            PoolReusePolicy.LIFO,
            TimeValue.ofMilliseconds(timings.connectionTimeToLive()),
            null
        );
        connectionManager.setMaxTotal(timings.maxConnections());
        connectionManager.setDefaultMaxPerRoute(timings.maxConnectionsPerRoute());
        connectionManager.setDefaultConnectionConfig(
            ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(timings.connectTimeout()))
                .setSocketTimeout(Timeout.ofMilliseconds(timings.socketTimeout()))
                .build()
        );
        return connectionManager;
    }

    /**
     * Records the stream the transport reads a response's body through, off the exchange it
     * arrived on, on the request's context under {@link #EXCHANGE_ATTRIBUTE}.
     * <p>
     * Runs just above the protocol handler, which hands a body back to be read through an
     * {@link EofSensorInputStream} whose abort closes the exchange's connection, and beneath the
     * handler that decodes a body sent with a {@code Content-Encoding}, so it sees the body as the
     * exchange delivers it. A response whose body is read otherwise, or that carries none,
     * removes any stream an earlier hop of the request recorded.
     *
     * @param request the request
     * @param scope the execution scope, holding the request's context
     * @param chain the rest of the execution chain
     * @return the response, unchanged
     * @throws IOException if the exchange fails
     * @throws HttpException if the exchange violates the protocol
     */
    private static @NotNull ClassicHttpResponse captureExchange(
        @NotNull ClassicHttpRequest request,
        @NotNull ExecChain.Scope scope,
        @NotNull ExecChain chain
    ) throws IOException, HttpException {
        ClassicHttpResponse response = chain.proceed(request, scope);
        HttpEntity entity = response.getEntity();

        if (entity != null && entity.isStreaming() && entity.getContent() instanceof EofSensorInputStream exchange)
            scope.clientContext.setAttribute(EXCHANGE_ATTRIBUTE, exchange);
        else
            scope.clientContext.removeAttribute(EXCHANGE_ATTRIBUTE);

        return response;
    }

    /**
     * Wraps the body of the response the transport hands back in an {@link AbortableEntity} over
     * the stream {@link #captureExchange} recorded, so the body can be aborted without being read
     * whether or not the transport decoded it.
     * <p>
     * Runs first in the execution chain, above the handler that decodes a body sent with a
     * {@code Content-Encoding}, so it sees the body as a caller reads it. A response whose body
     * arrived on no recorded stream is returned as it stands.
     *
     * @param request the request
     * @param scope the execution scope, holding the request's context
     * @param chain the rest of the execution chain
     * @return the response, its body abortable
     * @throws IOException if the exchange fails
     * @throws HttpException if the exchange violates the protocol
     */
    private static @NotNull ClassicHttpResponse abortableBody(
        @NotNull ClassicHttpRequest request,
        @NotNull ExecChain.Scope scope,
        @NotNull ExecChain chain
    ) throws IOException, HttpException {
        ClassicHttpResponse response = chain.proceed(request, scope);
        Object exchange = scope.clientContext.removeAttribute(EXCHANGE_ATTRIBUTE);
        HttpEntity entity = response.getEntity();

        if (entity != null && entity.isStreaming() && exchange instanceof EofSensorInputStream stream)
            response.setEntity(new AbortableEntity(entity, stream));

        return response;
    }

    /**
     * Builds a route planner that plans every route as the default planner does, naming a fixed
     * local address on each.
     * <p>
     * The connection manager hands a route's local address to the connection operator, which binds
     * the socket to it before connecting, so every connection opened for such a route leaves from
     * that address. Routes are pooled by their local address along with their target, so a pooled
     * connection is reused only by a request planned from the same address.
     *
     * @param localAddress the address every route names as its local address
     * @return the route planner
     */
    private static @NotNull HttpRoutePlanner bindingTo(@NotNull Inet6Address localAddress) {
        return new DefaultRoutePlanner(DefaultSchemePortResolver.INSTANCE) {
            @Override
            protected InetAddress determineLocalAddress(HttpHost firstHop, HttpContext context) {
                return localAddress;
            }
        };
    }

    /**
     * Removes every header whose name matches a test from a message.
     *
     * @param message the request or response to strip
     * @param removed tests whether a header name is removed
     */
    private static void removeHeaders(@NotNull HttpMessage message, @NotNull Predicate<String> removed) {
        for (Header header : message.getHeaders()) {
            if (removed.test(header.getName()))
                message.removeHeader(header);
        }
    }

    /**
     * Rebuilds the outbound request's URI with the configured static query parameters
     * appended, fixing the historical HC 4 bug where {@code request.getParams().setParameter}
     * pushed values into protocol params instead of the URL query string. Uses HC 5's
     * {@link URIBuilder} so the encoding is correct.
     *
     * @param request the outbound request whose URI gets rewritten
     * @param queries the static query parameters to append
     */
    private static void appendQueryParameters(@NotNull org.apache.hc.core5.http.HttpRequest request, @NotNull Map<String, String> queries) {
        try {
            URI uri = request.getUri();
            URIBuilder builder = new URIBuilder(uri);
            queries.forEach(builder::addParameter);
            request.setUri(builder.build());
        } catch (URISyntaxException ignored) {
            // If the URI cannot be parsed for any reason, silently skip - the request
            // proceeds with the original URI. Matches the historical behaviour of the
            // HC 4 code path where the static queries also never reached the URL.
        }
    }

    /**
     * Spawns one Java 21 {@linkplain Thread#ofVirtual() virtual thread} per host that resolves
     * {@link InetAddress#getAllByName(String)}, populating the OS resolver cache so
     * the first real request does not pay the DNS lookup. Failures are swallowed per host -
     * an unreachable host at construction time must not propagate into client setup; the
     * first real request will surface the failure normally.
     *
     * @param hosts the unique hostnames to resolve in the background; safe to pass an empty set
     */
    public static void prewarmDns(@NotNull Set<String> hosts) {
        for (String host : hosts) {
            Thread.ofVirtual().name("client-dns-" + host).start(() -> {
                try {
                    InetAddress.getAllByName(host);
                } catch (Throwable ignored) {
                    // See javadoc.
                }
            });
        }
    }

    /**
     * Spawns one Java 21 {@linkplain Thread#ofVirtual() virtual thread} per host that issues
     * a single {@code HEAD /} via the supplied Feign client, leaving a warmed-up keep-alive
     * connection in the underlying Apache pool for the first real request.
     *
     * <p>Each thread wraps its body in a {@code try/catch(Throwable)} that swallows every
     * failure - an unreachable host at construction time must not surface as a
     * {@code Client.create()} exception. Virtual threads are the right fit because the
     * HEAD probe is short-lived blocking I/O; on HC 5 the carrier thread is no longer
     * pinned during pool operations (HC 4's {@code synchronized} blocks have been replaced
     * with {@link ReentrantLock}), so the JDK actually yields
     * the carrier as advertised.</p>
     *
     * @param hosts the unique hostnames to probe; safe to pass an empty set (no-op)
     * @param feignClient the Feign transport to dispatch the HEAD through; the same instance
     *                    the client will use for real requests so the prewarm seeds the same
     *                    Apache pool
     */
    public static void prewarmHosts(@NotNull Set<String> hosts, @NotNull feign.Client feignClient) {
        if (hosts.isEmpty()) return;

        feign.Request.Options probeOptions = new feign.Request.Options(2, TimeUnit.SECONDS, 2, TimeUnit.SECONDS, true);

        for (String host : hosts) {
            String url = "https://" + host + "/";
            Thread.ofVirtual().name("client-prewarm-" + host).start(() -> {
                try {
                    feign.Request probe = feign.Request.create(
                        feign.Request.HttpMethod.HEAD,
                        url,
                        Map.of(),
                        feign.Request.Body.empty(),
                        null
                    );
                    feign.Response response = feignClient.execute(probe, probeOptions);
                    feign.Util.ensureClosed(response.body());
                } catch (Throwable ignored) {
                    // Best-effort warm-up; an unreachable host at construction time must
                    // not surface here. The first real request will report the failure.
                }
            });
        }
    }

}
