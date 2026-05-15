package dev.simplified.client.factory;

import dev.simplified.client.response.NetworkDetails;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.io.ManagedHttpClientConnection;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.annotation.Internal;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.Lookup;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.TimeValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;

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
 * The TLS handshake is timed separately via {@link TimedTlsSocketStrategy}.
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
    @Internal
    public void connect(@NotNull ManagedHttpClientConnection conn, @NotNull HttpHost host, @Nullable InetSocketAddress localAddress, @NotNull TimeValue connectTimeout, @NotNull SocketConfig socketConfig, @NotNull HttpContext context) throws IOException {
        // Anchor a single wall-clock Instant against a monotonic nanoTime baseline so that
        // the stopwatch boundaries can be derived from nanoTime deltas (single non-allocating
        // native call) instead of paying for Instant.now() syscalls per sample.
        Instant anchorInstant = Instant.now();
        long anchorNanos = System.nanoTime();
        long startNanos = System.nanoTime();
        try {
            super.connect(conn, host, localAddress, connectTimeout, socketConfig, context);
        } finally {
            long endNanos = System.nanoTime();
            context.setAttribute(NetworkDetails.TCP_CONNECT_START, instantAt(anchorInstant, anchorNanos, startNanos));
            context.setAttribute(NetworkDetails.TCP_CONNECT_END, instantAt(anchorInstant, anchorNanos, endNanos));
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
