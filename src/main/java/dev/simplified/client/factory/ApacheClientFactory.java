package dev.simplified.client.factory;

import dev.simplified.client.Client;
import dev.simplified.client.request.Timings;
import dev.simplified.client.response.NetworkDetails;
import lombok.experimental.UtilityClass;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.LayeredConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.conn.SystemDefaultDnsResolver;
import org.apache.http.protocol.HttpContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.SSLContext;
import java.net.Inet6Address;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Shared factory that assembles a fully configured Apache {@link HttpClientBuilder} for use by
 * the {@link Client contract-based client} and the standalone URL fetcher.
 * <p>
 * The returned builder carries:
 * <ul>
 *   <li>A {@link PoolingHttpClientConnectionManager} wired with
 *       {@link TimedPlainConnectionSocketFactory} and
 *       {@link TimedSecureConnectionSocketFactory} so that DNS, TCP, and TLS timings are
 *       captured into the {@link HttpContext} as {@link NetworkDetails} attributes.</li>
 *   <li>A request interceptor that stamps the request-start timestamp, propagates the
 *       captured timing attributes onto the outbound request as {@code X-Internal-} headers,
 *       and applies the configured static queries, static headers, and dynamic headers.</li>
 *   <li>Pool sizing, eviction, keep-alive, and connection time-to-live derived from the
 *       given {@link Timings}.</li>
 *   <li>An optional local IPv6 address binding when supplied.</li>
 * </ul>
 * <p>
 * Each caller wraps the resulting builder as it sees fit: {@code Client} runs
 * {@code new ApacheHttpClient(builder.build())} for Feign integration, while
 * {@code UrlFetcher} calls {@code builder.build()} directly to obtain a raw
 * {@link org.apache.http.impl.client.CloseableHttpClient CloseableHttpClient}.
 *
 * @see Client
 * @see Timings
 * @see NetworkDetails
 */
@UtilityClass
public final class ApacheClientFactory {

    /**
     * Configures a new {@link HttpClientBuilder} with shared client infrastructure.
     *
     * @param timings the connection pool, timeout, and keep-alive configuration
     * @param queries static query parameters appended to every outbound request
     * @param headers static headers appended to every outbound request
     * @param dynamicHeaders lazily-evaluated headers appended to every outbound request when
     *                       the supplier yields a present value
     * @param inet6Address the optional local IPv6 address for outbound socket binding
     * @param sslContextOverride optional {@link SSLContext} to substitute for the JDK default
     *                           on the HTTPS socket factory; when non-null, hostname
     *                           verification is also disabled via {@link NoopHostnameVerifier}
     *                           - reserved for benchmarks and tests
     * @return a configured {@link HttpClientBuilder} ready to be {@code build()}-ed or further
     *         customized by the caller
     */
    @SuppressWarnings("deprecation")
    public static @NotNull HttpClientBuilder configure(
        @NotNull Timings timings,
        @NotNull Map<String, String> queries,
        @NotNull Map<String, String> headers,
        @NotNull Map<String, Supplier<Optional<String>>> dynamicHeaders,
        @NotNull Optional<Inet6Address> inet6Address,
        @Nullable SSLContext sslContextOverride
    ) {
        LayeredConnectionSocketFactory secureDelegate = sslContextOverride != null
            ? new SSLConnectionSocketFactory(sslContextOverride, NoopHostnameVerifier.INSTANCE)
            : SSLConnectionSocketFactory.getSocketFactory();

        HttpClientBuilder builder = HttpClientBuilder.create()
            .setConnectionManager(new PoolingHttpClientConnectionManager(
                RegistryBuilder.<ConnectionSocketFactory>create()
                    .register("http", new TimedPlainConnectionSocketFactory(
                        PlainConnectionSocketFactory.getSocketFactory(),
                        SystemDefaultDnsResolver.INSTANCE
                    ))
                    .register("https", new TimedSecureConnectionSocketFactory(
                        secureDelegate,
                        SystemDefaultDnsResolver.INSTANCE
                    ))
                    .build()
            ))
            .addInterceptorFirst((HttpRequestInterceptor) (request, context) -> {
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

                queries.forEach((key, value) -> request.getParams().setParameter(key, value));
                headers.forEach(request::addHeader);
                dynamicHeaders.forEach((key, supplier) -> supplier.get()
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

        inet6Address.ifPresent(addr -> builder.setDefaultRequestConfig(
            RequestConfig.copy(RequestConfig.DEFAULT)
                .setLocalAddress(addr)
                .build()
        ));

        return builder;
    }

    /**
     * Copies a named attribute from the {@link HttpContext} into the {@link HttpRequest} as
     * a header, if the attribute is present.
     *
     * @param request the outbound request to add the header to
     * @param context the HTTP context carrying connection-layer attributes
     * @param id the attribute name and header name to propagate
     */
    private static void addHeader(@NotNull HttpRequest request, @NotNull HttpContext context, @NotNull String id) {
        Object value = context.getAttribute(id);

        if (value != null)
            request.addHeader(id, String.valueOf(value));
    }

}
