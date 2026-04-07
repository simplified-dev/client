package dev.simplified.client.request;

import dev.simplified.client.Client;
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
 * @param connectionTimeToLive maximum lifetime of a pooled HTTP connection in milliseconds, before it is permanently
 *                             closed regardless of activity. Passed to
 *                             {@link HttpClientBuilder#setConnectionTimeToLive(long, TimeUnit)
 *                             HttpClientBuilder.setConnectionTimeToLive()}. Default: 120,000 (2 minutes).
 * @param connectionIdleTimeout maximum duration in milliseconds a pooled connection may sit idle before it is evicted
 *                              by the background cleanup thread. Passed to
 *                              {@link HttpClientBuilder#evictIdleConnections(long, TimeUnit)
 *                              HttpClientBuilder.evictIdleConnections()}. Default: 45,000 (45 seconds).
 * @param connectionKeepAlive default keep-alive duration in milliseconds for persistent connections when the server
 *                            response does not include a {@code Keep-Alive} header. Applied by the custom keep-alive
 *                            strategy in {@link Client#configureInternalClient()}. Default: 30,000 (30 seconds).
 * @param connectTimeout maximum time in milliseconds to wait for a TCP connection to be established. Passed to
 *                       {@link feign.Request.Options} as the connect timeout. Controls {@code SO_CONNECT_TIMEOUT}
 *                       on the underlying socket. Default: 5,000 (5 seconds).
 * @param socketTimeout maximum time in milliseconds of inactivity between consecutive data packets after the
 *                      connection is established. Passed to {@link feign.Request.Options} as the read timeout, which
 *                      maps to {@code SO_TIMEOUT} on the underlying socket. This is a per-packet inactivity limit,
 *                      not a total transfer timeout - streaming large files works as long as data arrives within
 *                      this interval. Default: 10,000 (10 seconds).
 * @param maxConnections maximum total concurrent HTTP connections across all routes. Passed to
 *                       {@link PoolingHttpClientConnectionManager#setMaxTotal(int)
 *                       PoolingHttpClientConnectionManager.setMaxTotal()}. Default: 200.
 * @param maxConnectionsPerRoute maximum concurrent HTTP connections per individual route. Passed to
 *                               {@link PoolingHttpClientConnectionManager#setDefaultMaxPerRoute(int)
 *                               PoolingHttpClientConnectionManager.setDefaultMaxPerRoute()}. Default: 50.
 * @param cacheDuration duration in milliseconds for which recent responses are retained in the client's response
 *                      cache before pruning. Used by the response interceptor in
 *                      {@link Client#configureInternalClient()}. Default: 3,600,000 (1 hour).
 * @param maxCacheSize maximum number of entries in the client's response cache before pruning triggers. Default: 100.
 * @see Client
 */
public record Timings(
    long connectionTimeToLive,
    long connectionIdleTimeout,
    long connectionKeepAlive,
    long connectTimeout,
    long socketTimeout,
    int maxConnections,
    int maxConnectionsPerRoute,
    long cacheDuration,
    long maxCacheSize
) {

    /**
     * Creates a {@code Timings} instance populated with sensible default values.
     * <p>
     * Defaults:
     * <ul>
     *   <li>{@link #connectionTimeToLive() connectionTimeToLive} - 120,000 ms (2 minutes)</li>
     *   <li>{@link #connectionIdleTimeout() connectionIdleTimeout} - 45,000 ms (45 seconds)</li>
     *   <li>{@link #connectionKeepAlive() connectionKeepAlive} - 30,000 ms (30 seconds)</li>
     *   <li>{@link #connectTimeout() connectTimeout} - 5,000 ms (5 seconds)</li>
     *   <li>{@link #socketTimeout() socketTimeout} - 10,000 ms (10 seconds)</li>
     *   <li>{@link #maxConnections() maxConnections} - 200</li>
     *   <li>{@link #maxConnectionsPerRoute() maxConnectionsPerRoute} - 50</li>
     *   <li>{@link #cacheDuration() cacheDuration} - 3,600,000 ms (1 hour)</li>
     *   <li>{@link #maxCacheSize() maxCacheSize} - 100</li>
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
