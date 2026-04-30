package dev.simplified.client.factory;

import dev.simplified.client.response.NetworkDetails;
import org.apache.http.HttpHost;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.socket.LayeredConnectionSocketFactory;
import org.apache.http.protocol.HttpContext;
import org.jetbrains.annotations.NotNull;

import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;

/**
 * Decorating {@link LayeredConnectionSocketFactory} for secure (HTTPS) connections that
 * measures and records DNS resolution, TCP + TLS connection timing, and TLS session details
 * into the Apache {@link HttpContext}.
 * <p>
 * All socket creation and connection work is delegated to an inner
 * {@link LayeredConnectionSocketFactory} (typically
 * {@link org.apache.http.conn.ssl.SSLConnectionSocketFactory}). This wrapper adds
 * nanosecond-precision timing around DNS resolution, the combined TCP + TLS connect, and
 * (for layered connections) the isolated TLS handshake. The measured values and TLS metadata
 * are stored as {@link HttpContext} attributes using the keys defined in {@link NetworkDetails}:
 * <ul>
 *   <li>{@link NetworkDetails#DNS_START} / {@link NetworkDetails#DNS_END} - DNS resolution timestamps</li>
 *   <li>{@link NetworkDetails#TCP_CONNECT_START} / {@link NetworkDetails#TCP_CONNECT_END} - TCP + TLS timestamps
 *       (combined during the initial connect)</li>
 *   <li>{@link NetworkDetails#TLS_HANDSHAKE_START} / {@link NetworkDetails#TLS_HANDSHAKE_END} - TLS handshake timestamps
 *       when layering TLS over an existing plain socket</li>
 *   <li>{@link NetworkDetails#TLS_PROTOCOL} - the negotiated TLS protocol version
 *       (e.g. {@code "TLSv1.3"})</li>
 *   <li>{@link NetworkDetails#TLS_CIPHER} - the negotiated cipher suite name</li>
 * </ul>
 * <p>
 * These attributes are later propagated as internal headers by the Apache request interceptor
 * configured in {@link dev.simplified.client.Client}, making them available to
 * {@link NetworkDetails} for per-request latency and security reporting.
 *
 * @see TimedPlainConnectionSocketFactory
 * @see NetworkDetails
 * @see dev.simplified.client.Client
 */
public final class TimedSecureConnectionSocketFactory implements LayeredConnectionSocketFactory {

    /** The inner factory that performs the actual SSL socket creation and connection. */
    private final @NotNull LayeredConnectionSocketFactory delegate;

    /** The DNS resolver used to time hostname resolution independently. */
    private final @NotNull DnsResolver dnsResolver;

    /**
     * Constructs a new timed secure connection socket factory.
     *
     * @param delegate the inner {@link LayeredConnectionSocketFactory} to delegate socket operations to
     * @param dnsResolver the {@link DnsResolver} used for timed hostname resolution
     */
    public TimedSecureConnectionSocketFactory(@NotNull LayeredConnectionSocketFactory delegate, @NotNull DnsResolver dnsResolver) {
        this.delegate = delegate;
        this.dnsResolver = dnsResolver;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Socket createSocket(@NotNull HttpContext context) throws IOException {
        return this.delegate.createSocket(context);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Socket connectSocket(int connectTimeout, @NotNull Socket socket, @NotNull HttpHost host, @NotNull InetSocketAddress remoteAddress, InetSocketAddress localAddress, @NotNull HttpContext context) throws IOException {
        // Anchor a single wall-clock Instant against a monotonic nanoTime baseline so that
        // subsequent stopwatch boundaries can be derived via nanoTime deltas (single
        // non-allocating native call) instead of paying for Instant.now() syscalls per sample.
        Instant anchorInstant = Instant.now();
        long anchorNanos = System.nanoTime();

        // Time DNS resolution
        long dnsStartNanos = System.nanoTime();
        dnsResolver.resolve(host.getHostName());
        long dnsEndNanos = System.nanoTime();
        context.setAttribute(NetworkDetails.DNS_START, instantAt(anchorInstant, anchorNanos, dnsStartNanos));
        context.setAttribute(NetworkDetails.DNS_END, instantAt(anchorInstant, anchorNanos, dnsEndNanos));

        // Time TCP + TLS connection (combined in initial connect for HTTPS)
        long tcpStartNanos = System.nanoTime();
        Socket result = delegate.connectSocket(connectTimeout, socket, host, remoteAddress, localAddress, context);
        long tcpEndNanos = System.nanoTime();

        // For HTTPS, this includes both TCP and TLS handshake
        context.setAttribute(NetworkDetails.TCP_CONNECT_START, instantAt(anchorInstant, anchorNanos, tcpStartNanos));
        context.setAttribute(NetworkDetails.TCP_CONNECT_END, instantAt(anchorInstant, anchorNanos, tcpEndNanos));

        recordTlsMetadata(result, context);

        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Socket createLayeredSocket(@NotNull Socket socket, @NotNull String target, int port, @NotNull HttpContext context) throws IOException {
        // Anchor a single wall-clock Instant against a monotonic nanoTime baseline so that
        // the TLS handshake boundaries can be derived from nanoTime deltas instead of
        // paying for an Instant.now() syscall on each sample.
        Instant anchorInstant = Instant.now();
        long anchorNanos = System.nanoTime();

        // Time TLS handshake specifically (when layering TLS over existing connection)
        long tlsStartNanos = System.nanoTime();
        Socket result = delegate.createLayeredSocket(socket, target, port, context);
        long tlsEndNanos = System.nanoTime();
        context.setAttribute(NetworkDetails.TLS_HANDSHAKE_START, instantAt(anchorInstant, anchorNanos, tlsStartNanos));
        context.setAttribute(NetworkDetails.TLS_HANDSHAKE_END, instantAt(anchorInstant, anchorNanos, tlsEndNanos));

        recordTlsMetadata(result, context);

        return result;
    }

    /**
     * Records the negotiated TLS protocol and cipher suite onto the given context when the
     * connected socket is an {@link SSLSocket}. No-op for plain sockets.
     *
     * @param socket the socket returned by the delegate
     * @param context the HTTP execution context to populate
     */
    private static void recordTlsMetadata(@NotNull Socket socket, @NotNull HttpContext context) {
        if (socket instanceof SSLSocket sslSocket) {
            SSLSession session = sslSocket.getSession();
            context.setAttribute(NetworkDetails.TLS_PROTOCOL, session.getProtocol());
            context.setAttribute(NetworkDetails.TLS_CIPHER, session.getCipherSuite());
        }
    }

    /**
     * Derives an {@link Instant} for the given monotonic-clock sample by offsetting the
     * anchor instant by the elapsed nanoseconds between the anchor and sample readings.
     * Because {@link System#nanoTime()} is monotonic, the resulting timestamps preserve
     * accurate elapsed-time semantics across NTP adjustments that would perturb
     * {@link Instant#now()}.
     *
     * @param anchorInstant the wall-clock anchor sampled at the start of the measurement window
     * @param anchorNanos the monotonic-clock reading captured alongside {@code anchorInstant}
     * @param sampleNanos the monotonic-clock reading at the moment to derive an instant for
     * @return the wall-clock instant corresponding to {@code sampleNanos}
     */
    private static @NotNull Instant instantAt(@NotNull Instant anchorInstant, long anchorNanos, long sampleNanos) {
        return anchorInstant.plusNanos(sampleNanos - anchorNanos);
    }

}
