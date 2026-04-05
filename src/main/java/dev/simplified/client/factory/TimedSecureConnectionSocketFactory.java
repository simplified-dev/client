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
        // Time DNS resolution
        Instant dnsStart = Instant.now();
        dnsResolver.resolve(host.getHostName());
        Instant dnsEnd = Instant.now();
        context.setAttribute(NetworkDetails.DNS_START, dnsStart);
        context.setAttribute(NetworkDetails.DNS_END, dnsEnd);

        // Time TCP + TLS connection (combined in initial connect for HTTPS)
        Instant tcpStart = Instant.now();
        Socket result = delegate.connectSocket(connectTimeout, socket, host, remoteAddress, localAddress, context);
        Instant tcpEnd = Instant.now();

        // For HTTPS, this includes both TCP and TLS handshake
        context.setAttribute(NetworkDetails.TCP_CONNECT_START, tcpStart);
        context.setAttribute(NetworkDetails.TCP_CONNECT_END, tcpEnd);

        // Extract TLS info
        if (result instanceof SSLSocket) {
            SSLSession session = ((SSLSocket) result).getSession();
            context.setAttribute(NetworkDetails.TLS_PROTOCOL, session.getProtocol());
            context.setAttribute(NetworkDetails.TLS_CIPHER, session.getCipherSuite());
        }

        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Socket createLayeredSocket(@NotNull Socket socket, @NotNull String target, int port, @NotNull HttpContext context) throws IOException {
        // Time TLS handshake specifically (when layering TLS over existing connection)
        Instant tlsStart = Instant.now();
        Socket result = delegate.createLayeredSocket(socket, target, port, context);
        Instant tlsEnd = Instant.now();
        context.setAttribute(NetworkDetails.TLS_HANDSHAKE_START, tlsStart);
        context.setAttribute(NetworkDetails.TLS_HANDSHAKE_END, tlsEnd);

        // Extract TLS info
        if (result instanceof SSLSocket) {
            SSLSession session = ((SSLSocket) result).getSession();
            context.setAttribute(NetworkDetails.TLS_PROTOCOL, session.getProtocol());
            context.setAttribute(NetworkDetails.TLS_CIPHER, session.getCipherSuite());
        }

        return result;
    }

}
