package dev.simplified.client.factory;

import org.apache.hc.core5.http.protocol.HttpContext;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;

/**
 * A wall-clock instant paired with the monotonic reading sampled beside it, from which every
 * timestamp of one connection is derived.
 * <p>
 * {@link TimedConnectionOperator} and {@link TimedTlsSocketStrategy} time the same connection.
 * The operator samples one anchor and shares it through the {@link HttpContext}, and the TLS
 * strategy derives its handshake timestamps from it, so both sets of timestamps sit in one clock
 * domain and the connection window ends exactly where the handshake starts. Two anchors sampled
 * separately can disagree by more than the time between their readings, which would order the
 * two windows arbitrarily.
 *
 * @param instant the wall-clock instant sampled beside {@code nanos}
 * @param nanos the {@link System#nanoTime()} reading sampled beside {@code instant}
 */
record ClockAnchor(@NotNull Instant instant, long nanos) {

    /**
     * Context attribute under which the connection operator shares its anchor with the TLS
     * strategy for the connection it is opening.
     */
    static final @NotNull String ATTRIBUTE = ClockAnchor.class.getName();

    /**
     * Context attribute under which the TLS strategy records the monotonic reading at which its
     * handshake started, so the connection operator can end the connection window there.
     */
    static final @NotNull String TLS_START_NANOS = ClockAnchor.class.getName() + ".tlsStartNanos";

    /**
     * Samples a new anchor.
     *
     * @return an anchor pairing the current wall-clock instant with the current monotonic reading
     */
    static @NotNull ClockAnchor now() {
        return new ClockAnchor(Instant.now(), System.nanoTime());
    }

    /**
     * Returns the anchor the context holds for the connection being opened, or a new one when it
     * holds none.
     *
     * @param context the context of the connection being opened
     * @return the shared anchor, or a newly sampled one
     */
    static @NotNull ClockAnchor of(@NotNull HttpContext context) {
        return context.getAttribute(ATTRIBUTE) instanceof ClockAnchor anchor ? anchor : now();
    }

    /**
     * Derives the wall-clock instant of a monotonic reading by offsetting this anchor's instant by
     * the nanoseconds elapsed between the anchor's reading and the sample. Because
     * {@link System#nanoTime()} is monotonic, derived instants keep their elapsed-time order
     * across wall-clock adjustments.
     *
     * @param sampleNanos a {@link System#nanoTime()} reading
     * @return the wall-clock instant corresponding to {@code sampleNanos}
     */
    @NotNull Instant at(long sampleNanos) {
        return this.instant.plusNanos(sampleNanos - this.nanos);
    }

}
