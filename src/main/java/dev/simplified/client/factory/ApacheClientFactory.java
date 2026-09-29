package dev.simplified.client.factory;

import dev.simplified.annotations.UtilityClass;
import dev.simplified.client.Client;
import dev.simplified.client.request.Timings;
import dev.simplified.client.response.NetworkDetails;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultConnectionKeepAliveStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpMessage;
import org.apache.hc.core5.http.HttpRequestInterceptor;
import org.apache.hc.core5.http.HttpResponseInterceptor;
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
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

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
 *       {@link HttpContext}, removes every {@linkplain NetworkDetails#isInternalHeader(String)
 *       internal header} from the outbound request, so none reaches the origin, and appends the
 *       configured static queries to the request URL.</li>
 *   <li>A response interceptor that removes every internal header the origin sent and records
 *       the {@linkplain NetworkDetails#CONNECTION_HEADERS connection markers} the context holds
 *       on the response in their place, so a caller that sees only the response - a Feign
 *       transport - reads the same DNS, TCP and TLS timings and TLS protocol and cipher as one
 *       that reads the context.</li>
 *   <li>Pool sizing, eviction, keep-alive, and connection time-to-live derived from the
 *       given {@link Timings}.</li>
 *   <li>An optional local IPv6 address binding when supplied.</li>
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
     * Configures a new {@link HttpClientBuilder} with shared client infrastructure, using
     * the JDK default TLS strategy. Equivalent to
     * {@link #configure(Timings, Map, Optional, TlsSocketStrategy) configure(...,
     * DefaultClientTlsStrategy.createSystemDefault())}.
     *
     * @param timings the connection pool, timeout, and keep-alive configuration
     * @param queries static query parameters appended to every outbound request URL
     * @param inet6Address the optional local IPv6 address for outbound socket binding
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
     * @param inet6Address the optional local IPv6 address for outbound socket binding
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
                removeInternalHeaders(request);

                if (!queries.isEmpty()) appendQueryParameters(request, queries);
            })
            .addResponseInterceptorFirst((HttpResponseInterceptor) (response, entityDetails, context) -> {
                removeInternalHeaders(response);

                for (String marker : NetworkDetails.CONNECTION_HEADERS) {
                    Object value = context.getAttribute(marker);

                    if (value != null)
                        response.addHeader(marker, String.valueOf(value));
                }
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
     * Removes every {@linkplain NetworkDetails#isInternalHeader(String) internal header} from a
     * message.
     *
     * @param message the request or response to strip
     */
    private static void removeInternalHeaders(@NotNull HttpMessage message) {
        for (Header header : message.getHeaders()) {
            if (NetworkDetails.isInternalHeader(header.getName()))
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
