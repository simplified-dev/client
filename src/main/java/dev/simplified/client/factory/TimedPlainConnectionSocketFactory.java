package dev.simplified.client.factory;

import dev.simplified.client.response.NetworkDetails;
import org.apache.http.HttpHost;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.protocol.HttpContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;

/**
 * Decorating {@link ConnectionSocketFactory} for plain (HTTP) connections that measures and
 * records DNS resolution and TCP connection timing into the Apache {@link HttpContext}.
 * <p>
 * All socket creation and connection work is delegated to an inner
 * {@link ConnectionSocketFactory} (typically {@link org.apache.http.conn.socket.PlainConnectionSocketFactory}).
 * This wrapper adds nanosecond-precision timing around DNS resolution (via the supplied
 * {@link DnsResolver}) and the subsequent TCP connect call. The measured values are stored
 * as {@link HttpContext} attributes using the keys defined in {@link NetworkDetails}:
 * <ul>
 *   <li>{@link NetworkDetails#DNS_START} / {@link NetworkDetails#DNS_END} - DNS resolution timestamps</li>
 *   <li>{@link NetworkDetails#TCP_CONNECT_START} / {@link NetworkDetails#TCP_CONNECT_END} - TCP handshake timestamps</li>
 * </ul>
 * <p>
 * These attributes are later propagated as internal headers by the Apache request interceptor
 * configured in {@link dev.simplified.client.Client}, making them available to
 * {@link NetworkDetails} for per-request latency reporting.
 *
 * @see TimedSecureConnectionSocketFactory
 * @see NetworkDetails
 * @see dev.simplified.client.Client
 */
public final class TimedPlainConnectionSocketFactory implements ConnectionSocketFactory {

    /** The inner factory that performs the actual socket creation and connection. */
    private final @NotNull ConnectionSocketFactory delegate;

    /** The DNS resolver used to time hostname resolution independently. */
    private final @NotNull DnsResolver dnsResolver;

    /**
     * Constructs a new timed plain connection socket factory.
     *
     * @param delegate the inner {@link ConnectionSocketFactory} to delegate socket operations to
     * @param dnsResolver the {@link DnsResolver} used for timed hostname resolution
     */
    public TimedPlainConnectionSocketFactory(@NotNull ConnectionSocketFactory delegate, @NotNull DnsResolver dnsResolver) {
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
    public Socket connectSocket(int connectTimeout, @NotNull Socket socket, @NotNull HttpHost host, @NotNull InetSocketAddress remoteAddress, @Nullable InetSocketAddress localAddress, @NotNull HttpContext context) throws IOException {
        // Time DNS resolution
        Instant dnsStart = Instant.now();
        this.dnsResolver.resolve(host.getHostName());
        Instant dnsEnd = Instant.now();
        context.setAttribute(NetworkDetails.DNS_START, dnsStart);
        context.setAttribute(NetworkDetails.DNS_END, dnsEnd);

        // Time TCP connection
        Instant tcpStart = Instant.now();
        Socket result = delegate.connectSocket(connectTimeout, socket, host, remoteAddress, localAddress, context);
        Instant tcpEnd = Instant.now();
        context.setAttribute(NetworkDetails.TCP_CONNECT_START, tcpStart);
        context.setAttribute(NetworkDetails.TCP_CONNECT_END, tcpEnd);

        return result;
    }

}
