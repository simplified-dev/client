package dev.simplified.client.request;

import dev.simplified.client.Client;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Immutable configuration holder for all timing and concurrency parameters used by
 * a {@link Client} and its underlying Apache HttpClient connection pool.
 * <p>
 * The parameters are divided into four groups:
 * <ul>
 *   <li><b>HTTP connection lifecycle</b> - time-to-live, idle eviction, and keep-alive timeouts
 *       that govern how long pooled connections remain open.</li>
 *   <li><b>Feign request timeouts</b> - connect and read timeouts applied to each outgoing
 *       Feign request via {@link feign.Request.Options}.</li>
 *   <li><b>Concurrency limits</b> - maximum total connections and maximum connections per
 *       route in the {@link PoolingHttpClientConnectionManager}.</li>
 *   <li><b>Client-level caching</b> - duration and size for which recent responses are retained in
 *       the client's response cache.</li>
 * </ul>
 * <p>
 * Subclasses of {@link Client} may override
 * {@code configureTimings()} to supply a custom {@code Timings} instance; otherwise the
 * sensible defaults provided by {@link #createDefault()} are used.
 *
 * @see Client
 */
@Getter
@RequiredArgsConstructor
public class Timings {

    // HTTP

    /**
     * Maximum lifetime of a pooled HTTP connection before it is permanently closed,
     * regardless of activity. Passed to
     * {@link org.apache.http.impl.client.HttpClientBuilder#setConnectionTimeToLive(long, java.util.concurrent.TimeUnit)
     * HttpClientBuilder.setConnectionTimeToLive()}.
     * <p>
     * Value is in milliseconds. Default: 120 000 (2 minutes).
     */
    private final long connectionTimeToLive;

    /**
     * Maximum duration a pooled connection may sit idle before it is evicted by the
     * background cleanup thread. Passed to
     * {@link HttpClientBuilder#evictIdleConnections(long, TimeUnit)
     * HttpClientBuilder.evictIdleConnections()}.
     * <p>
     * Value is in milliseconds. Default: 45,000 (45 seconds).
     */
    private final long connectionIdleTimeout;

    /**
     * Default keep-alive duration for persistent connections when the server response
     * does not include a {@code Keep-Alive} header. Applied by the custom keep-alive
     * strategy in {@link Client#configureInternalClient()}.
     * <p>
     * Value is in milliseconds. Default: 30,000 (30 seconds).
     */
    private final long connectionKeepAlive;

    // Feign

    /**
     * Maximum time to wait for a TCP connection to be established. Passed to
     * {@link feign.Request.Options} as the connect timeout. Controls
     * {@code SO_CONNECT_TIMEOUT} on the underlying socket.
     * <p>
     * Value is in milliseconds. Default: 5,000 (5 seconds).
     */
    private final long connectTimeout;

    /**
     * Maximum time of inactivity between consecutive data packets after the connection
     * is established. Passed to {@link feign.Request.Options} as the read timeout,
     * which maps to {@code SO_TIMEOUT} on the underlying socket.
     * <p>
     * This is a per-packet inactivity limit, not a total transfer timeout - streaming
     * large files works as long as data arrives within this interval.
     * <p>
     * Value is in milliseconds. Default: 10,000 (10 seconds).
     */
    private final long socketTimeout;

    // Concurrency

    /**
     * Maximum total concurrent HTTP connections across all routes. Passed to
     * {@link PoolingHttpClientConnectionManager#setMaxTotal(int)
     * PoolingHttpClientConnectionManager.setMaxTotal()}.
     * <p>
     * Default: 200.
     */
    private final int maxConnections;

    /**
     * Maximum concurrent HTTP connections per individual route. Passed to
     * {@link PoolingHttpClientConnectionManager#setDefaultMaxPerRoute(int)
     * PoolingHttpClientConnectionManager.setDefaultMaxPerRoute()}.
     * <p>
     * Default: 50.
     */
    private final int maxConnectionsPerRoute;

    // Cache

    /**
     * Duration for which recent responses are retained in the client's response cache
     * before pruning. Used by the response interceptor in
     * {@link Client#configureInternalClient()}.
     * <p>
     * Value is in milliseconds. Default: 3,600,000 (1 hour).
     */
    private final long cacheDuration;

    /**
     * Maximum number of entries in the client's response cache before pruning triggers.
     * <p>
     * Default: 100.
     */
    private final long maxCacheSize;

    /**
     * Creates a {@code Timings} instance populated with sensible default values.
     * <p>
     * Defaults:
     * <ul>
     *   <li>{@link #getConnectionTimeToLive() connectionTimeToLive} - 120,000 ms (2 minutes)</li>
     *   <li>{@link #getConnectionIdleTimeout() connectionIdleTimeout} - 45,000 ms (45 seconds)</li>
     *   <li>{@link #getConnectionKeepAlive() connectionKeepAlive} - 30,000 ms (30 seconds)</li>
     *   <li>{@link #getConnectTimeout() connectTimeout} - 5,000 ms (5 seconds)</li>
     *   <li>{@link #getSocketTimeout() socketTimeout} - 10,000 ms (10 seconds)</li>
     *   <li>{@link #getMaxConnections() maxConnections} - 200</li>
     *   <li>{@link #getMaxConnectionsPerRoute() maxConnectionsPerRoute} - 50</li>
     *   <li>{@link #getCacheDuration() cacheDuration} - 3,600,000 ms (1 hour)</li>
     *   <li>{@link #getMaxCacheSize() maxCacheSize} - 100</li>
     * </ul>
     *
     * @return a new {@code Timings} with default configuration values
     */
    public static @NotNull Timings createDefault() {
        return new Timings(
            120 * 1_000,
            45 * 1_000,
            30 * 1_000,
            5 * 1_000,
            10 * 1_000,
            200,
            50,
            Duration.ofHours(1).toMillis(),
            100
        );
    }

}
