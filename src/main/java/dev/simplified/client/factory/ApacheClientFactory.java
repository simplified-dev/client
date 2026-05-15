package dev.simplified.client.factory;

import dev.simplified.client.Client;
import dev.simplified.client.request.Timings;
import dev.simplified.client.response.NetworkDetails;
import lombok.experimental.UtilityClass;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.net.URIBuilder;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.jetbrains.annotations.NotNull;

import java.net.Inet6Address;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
 *   <li>A request interceptor that stamps the request-start timestamp, propagates the
 *       captured timing attributes onto the outbound request as {@code X-Internal-} headers,
 *       and applies the configured static queries, static headers, and dynamic headers.</li>
 *   <li>Pool sizing, eviction, keep-alive, and connection time-to-live derived from the
 *       given {@link Timings}.</li>
 *   <li>An optional local IPv6 address binding when supplied.</li>
 * </ul>
 * <p>
 * Each caller wraps the resulting builder as it sees fit: {@code Client} runs
 * {@code new ApacheHttp5Client(builder.build())} for Feign integration, while
 * {@code UrlFetcher} calls {@code builder.build()} directly to obtain a raw
 * {@link org.apache.hc.client5.http.impl.classic.CloseableHttpClient CloseableHttpClient}.
 *
 * @see Client
 * @see Timings
 * @see NetworkDetails
 */
@UtilityClass
public final class ApacheClientFactory {

    /**
     * Configures a new {@link HttpClientBuilder} with shared client infrastructure, using
     * the JDK default TLS strategy. Equivalent to
     * {@link #configure(Timings, Map, Map, Map, Optional, TlsSocketStrategy) configure(...,
     * DefaultClientTlsStrategy.createSystemDefault())}.
     *
     * @param timings the connection pool, timeout, and keep-alive configuration
     * @param queries static query parameters appended to every outbound request URL
     * @param headers static headers appended to every outbound request
     * @param dynamicHeaders lazily-evaluated headers appended to every outbound request when
     *                       the supplier yields a present value
     * @param inet6Address the optional local IPv6 address for outbound socket binding
     * @return a configured {@link HttpClientBuilder} ready to be {@code build()}-ed or further
     *         customized by the caller
     */
    public static @NotNull HttpClientBuilder configure(
        @NotNull Timings timings,
        @NotNull Map<String, String> queries,
        @NotNull Map<String, String> headers,
        @NotNull Map<String, Supplier<Optional<String>>> dynamicHeaders,
        @NotNull Optional<Inet6Address> inet6Address
    ) {
        return configure(timings, queries, headers, dynamicHeaders, inet6Address,
            DefaultClientTlsStrategy.createSystemDefault());
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
     * @param headers static headers appended to every outbound request
     * @param dynamicHeaders lazily-evaluated headers appended to every outbound request when
     *                       the supplier yields a present value
     * @param inet6Address the optional local IPv6 address for outbound socket binding
     * @param tlsDelegate the TLS socket strategy applied to HTTPS routes; wrapped by
     *                    {@link TimedTlsSocketStrategy} so handshake timings are still captured
     * @return a configured {@link HttpClientBuilder} ready to be {@code build()}-ed or further
     *         customized by the caller
     */
    public static @NotNull HttpClientBuilder configure(
        @NotNull Timings timings,
        @NotNull Map<String, String> queries,
        @NotNull Map<String, String> headers,
        @NotNull Map<String, Supplier<Optional<String>>> dynamicHeaders,
        @NotNull Optional<Inet6Address> inet6Address,
        @NotNull TlsSocketStrategy tlsDelegate
    ) {
        HttpClientConnectionOperator timedOperator = new TimedConnectionOperator(
            null,
            SystemDefaultDnsResolver.INSTANCE,
            RegistryBuilder.<TlsSocketStrategy>create()
                .register(URIScheme.HTTPS.id, new TimedTlsSocketStrategy(tlsDelegate))
                .build()
        );

        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(
            timedOperator,
            PoolConcurrencyPolicy.LAX,
            PoolReusePolicy.LIFO,
            TimeValue.ofMilliseconds(timings.connectionTimeToLive()),
            null
        );
        connectionManager.setMaxTotal(timings.maxConnections());
        connectionManager.setDefaultMaxPerRoute(timings.maxConnectionsPerRoute());

        HttpClientBuilder builder = HttpClientBuilder.create()
            .setConnectionManager(connectionManager)
            // Feign's retryer drives retry semantics at the request layer. HC 5's default
            // HttpRequestRetryStrategy auto-retries 5xx responses, which both double-counts
            // retries and causes single 503s to take seconds while HC 5 retries internally.
            // Disable HC 5's transport-level retries entirely.
            .disableAutomaticRetries()
            .evictIdleConnections(TimeValue.ofMilliseconds(timings.connectionIdleTimeout()))
            .addRequestInterceptorFirst((HttpRequestInterceptor) (request, entityDetails, context) -> {
                context.setAttribute(NetworkDetails.REQUEST_START, Instant.now());

                addHeader(request, context, NetworkDetails.REQUEST_START);
                addHeader(request, context, NetworkDetails.DNS_START);
                addHeader(request, context, NetworkDetails.DNS_END);
                addHeader(request, context, NetworkDetails.TCP_CONNECT_START);
                addHeader(request, context, NetworkDetails.TCP_CONNECT_END);
                addHeader(request, context, NetworkDetails.TLS_HANDSHAKE_START);
                addHeader(request, context, NetworkDetails.TLS_HANDSHAKE_END);
                addHeader(request, context, NetworkDetails.TLS_PROTOCOL);
                addHeader(request, context, NetworkDetails.TLS_CIPHER);

                if (!queries.isEmpty()) appendQueryParameters(request, queries);
                headers.forEach(request::addHeader);
                dynamicHeaders.forEach((key, supplier) -> supplier.get()
                    .ifPresent(value -> request.addHeader(key, value))
                );
            })
            .setKeepAliveStrategy((response, context) -> {
                TimeValue keepAlive = DefaultConnectionKeepAliveStrategy.INSTANCE.getKeepAliveDuration(response, context);
                if (keepAlive == null || keepAlive.getDuration() < 0)
                    return TimeValue.ofMilliseconds(timings.connectionKeepAlive());
                long capped = Math.min(keepAlive.toMilliseconds(), 60_000L);
                return TimeValue.ofMilliseconds(capped);
            });

        inet6Address.ifPresent(addr -> builder.setDefaultRequestConfig(
            RequestConfig.copy(RequestConfig.DEFAULT)
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(timings.connectTimeout()))
                .setResponseTimeout(Timeout.ofMilliseconds(timings.socketTimeout()))
                .build()
        ));

        return builder;
    }

    /**
     * Copies a named attribute from the {@link HttpContext} into the request as a header,
     * if the attribute is present.
     *
     * @param request the outbound request to add the header to
     * @param context the HTTP context carrying connection-layer attributes
     * @param id the attribute name and header name to propagate
     */
    private static void addHeader(@NotNull org.apache.hc.core5.http.HttpRequest request, @NotNull HttpContext context, @NotNull String id) {
        Object value = context.getAttribute(id);

        if (value != null)
            request.addHeader(id, String.valueOf(value));
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
     * {@link java.net.InetAddress#getAllByName(String)}, populating the OS resolver cache so
     * the first real request does not pay the DNS lookup. Failures are swallowed per host -
     * an unreachable host at construction time must not propagate into client setup; the
     * first real request will surface the failure normally.
     *
     * @param hosts the unique hostnames to resolve in the background; safe to pass an empty set
     */
    public static void prewarmDns(@NotNull java.util.Set<String> hosts) {
        for (String host : hosts) {
            Thread.ofVirtual().name("client-dns-" + host).start(() -> {
                try {
                    java.net.InetAddress.getAllByName(host);
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
     * with {@link java.util.concurrent.locks.ReentrantLock}), so the JDK actually yields
     * the carrier as advertised.</p>
     *
     * @param hosts the unique hostnames to probe; safe to pass an empty set (no-op)
     * @param feignClient the Feign transport to dispatch the HEAD through; the same instance
     *                    the client will use for real requests so the prewarm seeds the same
     *                    Apache pool
     */
    public static void prewarm(@NotNull java.util.Set<String> hosts, @NotNull feign.Client feignClient) {
        if (hosts.isEmpty()) return;

        feign.Request.Options probeOptions = new feign.Request.Options(2, TimeUnit.SECONDS, 2, TimeUnit.SECONDS, true);

        for (String host : hosts) {
            String url = "https://" + host + "/";
            Thread.ofVirtual().name("client-prewarm-" + host).start(() -> {
                try {
                    feign.Request probe = feign.Request.create(
                        feign.Request.HttpMethod.HEAD,
                        url,
                        java.util.Map.of(),
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
