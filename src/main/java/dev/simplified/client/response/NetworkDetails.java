package dev.simplified.client.response;

import dev.simplified.util.time.Stopwatch;
import lombok.Getter;
import org.apache.http.protocol.HttpContext;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable snapshot of network-level timing and TLS metadata collected during an HTTP
 * request/response cycle.
 * <p>
 * Instances are constructed either from a {@link feign.Response} (by extracting internal
 * headers injected by the HTTP interceptor layer) or directly from an Apache
 * {@link HttpContext} (by reading context attributes set during connection establishment).
 * Internal headers use the {@code X-Internal-} prefix and are automatically stripped from
 * the public response headers by {@link Response#getHeaders(Map)}.
 * <p>
 * The captured metrics include:
 * <ul>
 *     <li><b>Timing</b> - round-trip, DNS resolution, TCP connection, and TLS handshake
 *         durations, each represented as a {@link Stopwatch} with start and completion
 *         timestamps</li>
 *     <li><b>TLS information</b> - the negotiated TLS protocol version and cipher suite,
 *         if the connection was secured</li>
 * </ul>
 *
 * @see Response
 * @see Stopwatch
 */
@Getter
public final class NetworkDetails {

    /** The common prefix for all internal headers injected by the HTTP interceptor layer. */
    public static final @NotNull String INTERNAL_HEADER_PREFIX = "X-Internal-";

    /** Internal header key storing the request start timestamp as an ISO-8601 instant. */
    public static final @NotNull String REQUEST_START = INTERNAL_HEADER_PREFIX + "Request-Start";

    /** Internal header key storing the response received timestamp as an ISO-8601 instant. */
    public static final @NotNull String RESPONSE_RECEIVED = INTERNAL_HEADER_PREFIX + "Response-Received";

    /** Internal header key storing the DNS resolution start timestamp. */
    public static final @NotNull String DNS_START = INTERNAL_HEADER_PREFIX + "DNS-Start";

    /** Internal header key storing the DNS resolution end timestamp. */
    public static final @NotNull String DNS_END = INTERNAL_HEADER_PREFIX + "DNS-End";

    /** Internal header key storing the TCP connection start timestamp. */
    public static final @NotNull String TCP_CONNECT_START = INTERNAL_HEADER_PREFIX + "TCP-Connect-Start";

    /** Internal header key storing the TCP connection end timestamp. */
    public static final @NotNull String TCP_CONNECT_END = INTERNAL_HEADER_PREFIX + "TCP-Connect-End";

    /** Internal header key storing the TLS handshake start timestamp. */
    public static final @NotNull String TLS_HANDSHAKE_START = INTERNAL_HEADER_PREFIX + "TLS-Handshake-Start";

    /** Internal header key storing the TLS handshake end timestamp. */
    public static final @NotNull String TLS_HANDSHAKE_END = INTERNAL_HEADER_PREFIX + "TLS-Handshake-End";

    /** Internal header key storing the negotiated TLS protocol version (e.g. {@code "TLSv1.3"}). */
    public static final @NotNull String TLS_PROTOCOL = INTERNAL_HEADER_PREFIX + "TLS-Protocol";

    /** Internal header key storing the negotiated TLS cipher suite name. */
    public static final @NotNull String TLS_CIPHER = INTERNAL_HEADER_PREFIX + "TLS-Cipher";

    /** Timing for the full request/response round trip. */
    private final @NotNull Stopwatch roundTrip;

    /** Timing for DNS hostname resolution. */
    private final @NotNull Stopwatch dnsResolution;

    /** Timing for TCP connection establishment. */
    private final @NotNull Stopwatch tcpConnection;

    /** Timing for the TLS handshake. */
    private final @NotNull Stopwatch tlsHandshake;

    /** The negotiated TLS protocol version, or {@link Optional#empty()} if the connection was not secured or the value was not captured. */
    private final @NotNull Optional<String> tlsProtocol;

    /** The negotiated TLS cipher suite name, or {@link Optional#empty()} if the connection was not secured or the value was not captured. */
    private final @NotNull Optional<String> tlsCipher;

    /**
     * Constructs a {@link NetworkDetails} by extracting internal headers from a Feign response.
     * <p>
     * Timing headers are read from the request headers (where they were injected by the
     * interceptor), while the response-received timestamp is read from the response headers.
     * Any missing header defaults to {@link Instant#EPOCH} for timestamps.
     *
     * @param response the Feign response from which to extract network timing and TLS metadata
     */
    public NetworkDetails(@NotNull feign.Response response) {
        Instant requestStart = extractInstant(response.request().headers(), REQUEST_START);
        Instant responseReceived = extractInstant(response.headers(), RESPONSE_RECEIVED);
        this.roundTrip = Stopwatch.of(requestStart, responseReceived);

        this.dnsResolution = extractStopwatch(response.request().headers(), DNS_START, DNS_END);
        this.tcpConnection = extractStopwatch(response.request().headers(), TCP_CONNECT_START, TCP_CONNECT_END);
        this.tlsHandshake = extractStopwatch(response.request().headers(), TLS_HANDSHAKE_START, TLS_HANDSHAKE_END);

        this.tlsProtocol = extractHeader(response.request().headers(), TLS_PROTOCOL);
        this.tlsCipher = extractHeader(response.request().headers(), TLS_CIPHER);
    }

    /**
     * Constructs a {@link NetworkDetails} from an Apache {@link HttpContext}, reading
     * timing and TLS attributes set during connection establishment by the HTTP transport
     * layer.
     * <p>
     * Missing attributes default to {@link Instant#EPOCH} for timestamps and {@code null}
     * (wrapped in {@link Optional}) for string values.
     *
     * @param context the Apache HTTP execution context containing network timing attributes
     */
    public NetworkDetails(@NotNull HttpContext context) {
        Instant requestStart = getAttribute(context, REQUEST_START, Instant.EPOCH);
        Instant responseReceived = getAttribute(context, RESPONSE_RECEIVED, Instant.EPOCH);
        this.roundTrip = Stopwatch.of(requestStart, responseReceived);

        this.dnsResolution = extractStopwatch(context, DNS_START, DNS_END);
        this.tcpConnection = extractStopwatch(context, TCP_CONNECT_START, TCP_CONNECT_END);
        this.tlsHandshake = extractStopwatch(context, TLS_HANDSHAKE_START, TLS_HANDSHAKE_END);

        this.tlsProtocol = Optional.ofNullable(getAttribute(context, TLS_PROTOCOL, null));
        this.tlsCipher = Optional.ofNullable(getAttribute(context, TLS_CIPHER, null));
    }

    /**
     * Retrieves a typed attribute from the given {@link HttpContext}, returning a default
     * value if the attribute is not present.
     *
     * @param context the HTTP execution context to query
     * @param id the attribute key to look up
     * @param defaultValue the value to return if the attribute is absent
     * @param <T> the expected type of the attribute value
     * @return the attribute value cast to {@code T}, or {@code defaultValue} if not present
     */
    @SuppressWarnings("unchecked")
    private static <T> T getAttribute(@NotNull HttpContext context, @NotNull String id, T defaultValue) {
        return context.getAttribute(id) != null ? (T) context.getAttribute(id) : defaultValue;
    }

    /**
     * Checks whether the given header name is an internal header injected by the HTTP
     * interceptor layer.
     * <p>
     * Internal headers use the {@code X-Internal-} prefix and are excluded from the
     * public response headers exposed through {@link Response#getHeaders()}.
     *
     * @param headerName the header name to test
     * @return {@code true} if the header name starts with the internal header prefix;
     *         {@code false} otherwise
     */
    public static boolean isInternalHeader(@NotNull String headerName) {
        return headerName.startsWith(INTERNAL_HEADER_PREFIX);
    }

    /**
     * Extracts the first value of a named header from a multi-valued headers map.
     *
     * @param headers the map of header names to their value collections
     * @param key the header name to look up
     * @return an {@link Optional} containing the first header value, or
     *         {@link Optional#empty()} if the header is absent or has no values
     */
    private static @NotNull Optional<String> extractHeader(@NotNull Map<String, Collection<String>> headers, @NotNull String key) {
        return Optional.ofNullable(headers.get(key)).flatMap(values -> values.stream().findFirst());
    }

    /**
     * Extracts an {@link Instant} from a named header, defaulting to {@link Instant#EPOCH}
     * if the header is absent.
     *
     * @param headers the map of header names to their value collections
     * @param key the header name to look up
     * @return the parsed instant, or {@link Instant#EPOCH} if not present
     */
    private static @NotNull Instant extractInstant(@NotNull Map<String, Collection<String>> headers, @NotNull String key) {
        return extractHeader(headers, key)
            .map(Instant::parse)
            .orElse(Instant.EPOCH);
    }

    /**
     * Constructs a {@link Stopwatch} from a pair of start/end instant headers.
     *
     * @param headers the map of header names to their value collections
     * @param startKey the header key for the start timestamp
     * @param endKey the header key for the end timestamp
     * @return a stopwatch representing the interval, or a zero-duration stopwatch if headers are absent
     */
    private static @NotNull Stopwatch extractStopwatch(@NotNull Map<String, Collection<String>> headers, @NotNull String startKey, @NotNull String endKey) {
        return Stopwatch.of(extractInstant(headers, startKey), extractInstant(headers, endKey));
    }

    /**
     * Constructs a {@link Stopwatch} from a pair of start/end instant attributes in the given context.
     *
     * @param context the HTTP execution context to query
     * @param startKey the attribute key for the start timestamp
     * @param endKey the attribute key for the end timestamp
     * @return a stopwatch representing the interval, or a zero-duration stopwatch if attributes are absent
     */
    private static @NotNull Stopwatch extractStopwatch(@NotNull HttpContext context, @NotNull String startKey, @NotNull String endKey) {
        return Stopwatch.of(
            getAttribute(context, startKey, Instant.EPOCH),
            getAttribute(context, endKey, Instant.EPOCH)
        );
    }

}
