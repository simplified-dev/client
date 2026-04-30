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
        // Anchor a single wall-clock Instant against a monotonic nanoTime baseline so that
        // subsequent stopwatch boundaries can be derived via nanoTime deltas (single
        // non-allocating native call) instead of paying for Instant.now() syscalls per sample.
        Instant anchorInstant = Instant.now();
        long anchorNanos = System.nanoTime();

        // Time DNS resolution
        long dnsStartNanos = System.nanoTime();
        this.dnsResolver.resolve(host.getHostName());
        long dnsEndNanos = System.nanoTime();
        context.setAttribute(NetworkDetails.DNS_START, instantAt(anchorInstant, anchorNanos, dnsStartNanos));
        context.setAttribute(NetworkDetails.DNS_END, instantAt(anchorInstant, anchorNanos, dnsEndNanos));

        // Time TCP connection
        long tcpStartNanos = System.nanoTime();
        Socket result = delegate.connectSocket(connectTimeout, socket, host, remoteAddress, localAddress, context);
        long tcpEndNanos = System.nanoTime();
        context.setAttribute(NetworkDetails.TCP_CONNECT_START, instantAt(anchorInstant, anchorNanos, tcpStartNanos));
        context.setAttribute(NetworkDetails.TCP_CONNECT_END, instantAt(anchorInstant, anchorNanos, tcpEndNanos));

        return result;
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
