package dev.simplified.client.factory;

import dev.simplified.client.response.NetworkDetails;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.io.ManagedHttpClientConnection;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.Lookup;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.net.NamedEndpoint;
import org.apache.hc.core5.util.Timeout;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;

/**
 * Decorating {@link DefaultHttpClientConnectionOperator} that captures DNS resolution and
 * TCP connection timing into the Apache {@link HttpContext} on every {@code connect()} call.
 * <p>
 * In HC 5 the plain-socket creation and connect orchestration moved out of the
 * {@code ConnectionSocketFactory} interface and into the connection operator, so this class
 * extends the default operator and wraps its {@code connect()} with a {@link System#nanoTime()}
 * stopwatch. The recorded interval covers <b>both DNS resolution and TCP handshake</b>
 * (HC 5 does not expose them separately), which is the trade-off for migrating off the
 * HC 4 {@code ConnectionSocketFactory.connectSocket()} hook where the two were timed
 * individually.
 * <p>
 * Every {@code connect()} overload of the default operator ends in the one taking a Unix domain
 * socket path, which is also the one {@link PoolingHttpClientConnectionManager} calls, so that
 * overload is the one timed, and each connection is timed once whichever overload a caller
 * reaches.
 * <p>
 * The TLS handshake is timed separately via {@link TimedTlsSocketStrategy}. The default operator
 * performs it inside {@code connect()}, after the TCP handshake, so the connection window ends
 * where the handshake {@link TimedTlsSocketStrategy} recorded for the connection starts. Both are
 * derived from one {@link ClockAnchor} this operator shares through the context, so the window
 * never ends after the handshake starts. The window is recorded whether or not the connection
 * succeeds.
 * <p>
 * Attributes written into the context use keys from {@link NetworkDetails}:
 * <ul>
 *   <li>{@link NetworkDetails#TCP_CONNECT_START} / {@link NetworkDetails#TCP_CONNECT_END}
 *       - the combined DNS + TCP connection window</li>
 * </ul>
 * The {@link NetworkDetails#DNS_START} / {@link NetworkDetails#DNS_END} keys are no longer
 * populated under HC 5; consumers read {@link NetworkDetails#getDnsResolution()} as an
 * empty {@code Stopwatch} on the HC 5 path.
 *
 * @see TimedTlsSocketStrategy
 * @see NetworkDetails
 * @see dev.simplified.client.Client
 */
public final class TimedConnectionOperator extends DefaultHttpClientConnectionOperator {

    /**
     * Constructs a new timed connection operator.
     *
     * @param schemePortResolver optional scheme-to-port resolver; {@code null} uses HC 5 default
     * @param dnsResolver optional DNS resolver; {@code null} uses HC 5 default
     * @param tlsSocketStrategyLookup lookup for the HTTPS TLS strategy registry; usually
     *                                a {@link Lookup} that returns a {@link TimedTlsSocketStrategy}
     *                                for the {@code https} scheme
     */
    public TimedConnectionOperator(@Nullable SchemePortResolver schemePortResolver, @Nullable DnsResolver dnsResolver, @NotNull Lookup<TlsSocketStrategy> tlsSocketStrategyLookup) {
        super(schemePortResolver, dnsResolver, tlsSocketStrategyLookup);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void connect(
        @NotNull ManagedHttpClientConnection conn,
        @NotNull HttpHost endpointHost,
        @Nullable NamedEndpoint endpointName,
        @Nullable Path unixDomainSocket,
        @Nullable InetSocketAddress localAddress,
        @Nullable Timeout connectTimeout,
        @NotNull SocketConfig socketConfig,
        @Nullable Object attachment,
        @NotNull HttpContext context
    ) throws IOException {
        // One anchor for the whole connection, shared with the TLS strategy through the context,
        // so the connection window and the handshake are derived in the same clock domain.
        ClockAnchor anchor = ClockAnchor.now();
        context.setAttribute(ClockAnchor.ATTRIBUTE, anchor);
        context.removeAttribute(ClockAnchor.TLS_START_NANOS);
        long startNanos = System.nanoTime();
        try {
            super.connect(conn, endpointHost, endpointName, unixDomainSocket, localAddress, connectTimeout, socketConfig, attachment, context);
        } finally {
            long endNanos = System.nanoTime();

            if (context.removeAttribute(ClockAnchor.TLS_START_NANOS) instanceof Long tlsStartNanos && tlsStartNanos >= startNanos && tlsStartNanos < endNanos)
                endNanos = tlsStartNanos;

            context.removeAttribute(ClockAnchor.ATTRIBUTE);
            context.setAttribute(NetworkDetails.TCP_CONNECT_START, anchor.at(startNanos));
            context.setAttribute(NetworkDetails.TCP_CONNECT_END, anchor.at(endNanos));
        }
    }

}
