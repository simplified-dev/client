package dev.simplified.client.factory;

import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.client.response.NetworkDetails;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.jetbrains.annotations.NotNull;

import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.Socket;

/**
 * Decorating {@link TlsSocketStrategy} that measures the TLS handshake duration and records
 * the negotiated protocol and cipher into the Apache {@link HttpContext}.
 * <p>
 * All TLS upgrade work is delegated to an inner {@link TlsSocketStrategy} (typically
 * {@link DefaultClientTlsStrategy}). This wrapper adds
 * nanosecond-precision timing around the {@link TlsSocketStrategy#upgrade(Socket, String, int, Object, HttpContext)
 * upgrade()} call and harvests the negotiated {@link SSLSession} state.
 * <p>
 * The measured values are stored as {@link HttpContext} attributes using keys defined in
 * {@link NetworkDetails}:
 * <ul>
 *   <li>{@link NetworkDetails#TLS_HANDSHAKE_START} / {@link NetworkDetails#TLS_HANDSHAKE_END}
 *       - TLS handshake start and completion timestamps</li>
 *   <li>{@link NetworkDetails#TLS_PROTOCOL} - the negotiated TLS protocol version
 *       (e.g. {@code "TLSv1.3"})</li>
 *   <li>{@link NetworkDetails#TLS_CIPHER} - the negotiated cipher suite name</li>
 * </ul>
 * <p>
 * These attributes are recorded on each response as internal headers by the response
 * interceptor {@link ApacheClientFactory} configures, making them available to
 * {@link NetworkDetails} for per-request TLS reporting.
 *
 * <p><b>HC 5 migration note:</b> this class replaces the HC 4-era
 * {@code TimedSecureConnectionSocketFactory.createLayeredSocket()} timing path. HC 5
 * splits TLS handling out of the {@code ConnectionSocketFactory} interface into a dedicated
 * {@link TlsSocketStrategy}, which is what made the migration cleaner than HC 4's combined
 * {@code LayeredConnectionSocketFactory} shape.
 *
 * @see TimedConnectionOperator
 * @see NetworkDetails
 * @see dev.simplified.client.Client
 */
@RequiredArgsConstructor
public final class TimedTlsSocketStrategy implements TlsSocketStrategy {

    /**
     * The inner strategy that performs the actual TLS upgrade.
     */
    private final @NotNull TlsSocketStrategy delegate;

    /**
     * {@inheritDoc}
     */
    @Override
    public SSLSocket upgrade(@NotNull Socket socket, @NotNull String target, int port, Object attachment, @NotNull HttpContext context) throws IOException {
        // The connection operator's anchor when it is opening this connection, so the handshake
        // and the connection window share one clock domain.
        ClockAnchor anchor = ClockAnchor.of(context);
        long tlsStartNanos = System.nanoTime();
        SSLSocket upgraded = this.delegate.upgrade(socket, target, port, attachment, context);
        long tlsEndNanos = System.nanoTime();

        context.setAttribute(ClockAnchor.TLS_START_NANOS, tlsStartNanos);
        context.setAttribute(NetworkDetails.TLS_HANDSHAKE_START, anchor.at(tlsStartNanos));
        context.setAttribute(NetworkDetails.TLS_HANDSHAKE_END, anchor.at(tlsEndNanos));

        SSLSession session = upgraded.getSession();
        context.setAttribute(NetworkDetails.TLS_PROTOCOL, session.getProtocol());
        context.setAttribute(NetworkDetails.TLS_CIPHER, session.getCipherSuite());

        return upgraded;
    }

}
