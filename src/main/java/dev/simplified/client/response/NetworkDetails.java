package dev.simplified.client.response;

import dev.simplified.annotations.Getter;
import dev.simplified.client.cache.CachingFeignClient;
import dev.simplified.util.time.Stopwatch;
import org.apache.hc.core5.http.protocol.BasicHttpContext;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Immutable snapshot of network-level timing and TLS metadata collected during an HTTP
 * request/response cycle.
 * <p>
 * Instances are constructed either from a {@link feign.Response} (by extracting the internal
 * headers the client's transport and {@link CachingFeignClient} record on the response) or
 * directly from an Apache {@link HttpContext} (by reading context attributes set during
 * connection establishment). Internal headers use the {@code X-Internal-} prefix and are
 * automatically stripped from the public response headers by {@link Response#getHeaders(Map)}.
 * The ones the client writes itself, the {@link #CLIENT_HEADERS}, never leave it on a request; a
 * request header of any other name a caller gives the prefix is sent as given.
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

    /**
     * The common prefix for all internal headers injected by the HTTP interceptor layer.
     */
    public static final @NotNull String INTERNAL_HEADER_PREFIX = "X-Internal-";

    /**
     * Internal header key storing the request start timestamp as an ISO-8601 instant.
     */
    public static final @NotNull String REQUEST_START = INTERNAL_HEADER_PREFIX + "Request-Start";

    /**
     * Internal header key storing the response received timestamp as an ISO-8601 instant.
     */
    public static final @NotNull String RESPONSE_RECEIVED = INTERNAL_HEADER_PREFIX + "Response-Received";

    /**
     * Internal header key storing the DNS resolution start timestamp.
     */
    public static final @NotNull String DNS_START = INTERNAL_HEADER_PREFIX + "DNS-Start";

    /**
     * Internal header key storing the DNS resolution end timestamp.
     */
    public static final @NotNull String DNS_END = INTERNAL_HEADER_PREFIX + "DNS-End";

    /**
     * Internal header key storing the TCP connection start timestamp.
     */
    public static final @NotNull String TCP_CONNECT_START = INTERNAL_HEADER_PREFIX + "TCP-Connect-Start";

    /**
     * Internal header key storing the TCP connection end timestamp.
     */
    public static final @NotNull String TCP_CONNECT_END = INTERNAL_HEADER_PREFIX + "TCP-Connect-End";

    /**
     * Internal header key storing the TLS handshake start timestamp.
     */
    public static final @NotNull String TLS_HANDSHAKE_START = INTERNAL_HEADER_PREFIX + "TLS-Handshake-Start";

    /**
     * Internal header key storing the TLS handshake end timestamp.
     */
    public static final @NotNull String TLS_HANDSHAKE_END = INTERNAL_HEADER_PREFIX + "TLS-Handshake-End";

    /**
     * Internal header key storing the negotiated TLS protocol version (e.g. {@code "TLSv1.3"}).
     */
    public static final @NotNull String TLS_PROTOCOL = INTERNAL_HEADER_PREFIX + "TLS-Protocol";

    /**
     * Internal header key storing the negotiated TLS cipher suite name.
     */
    public static final @NotNull String TLS_CIPHER = INTERNAL_HEADER_PREFIX + "TLS-Cipher";

    /**
     * The internal headers describing the connection an exchange used - the DNS, TCP and TLS
     * markers and the negotiated TLS protocol and cipher - which the client's transport records on
     * each response it receives from the attributes of its {@link HttpContext}.
     */
    public static final @NotNull List<String> CONNECTION_HEADERS = List.of(
        DNS_START,
        DNS_END,
        TCP_CONNECT_START,
        TCP_CONNECT_END,
        TLS_HANDSHAKE_START,
        TLS_HANDSHAKE_END,
        TLS_PROTOCOL,
        TLS_CIPHER
    );

    /**
     * Internal header key carrying a request's rate-limit sequence number from the client's
     * request interceptor to its response interceptor.
     */
    public static final @NotNull String REQUEST_SEQUENCE = INTERNAL_HEADER_PREFIX + "Request-Sequence";

    /**
     * The internal headers the client writes itself - the round-trip markers, the
     * {@link #CONNECTION_HEADERS} and the {@link #REQUEST_SEQUENCE} - which the client's transport
     * removes from every request it sends, whoever set them.
     */
    public static final @NotNull List<String> CLIENT_HEADERS = Stream.concat(
        Stream.of(REQUEST_START, RESPONSE_RECEIVED, REQUEST_SEQUENCE),
        CONNECTION_HEADERS.stream()
    ).toList();

    /**
     * Timing for the full request/response round trip.
     */
    private final @NotNull Stopwatch roundTrip;

    /**
     * Timing for DNS hostname resolution.
     */
    private final @NotNull Stopwatch dnsResolution;

    /**
     * Timing for TCP connection establishment.
     */
    private final @NotNull Stopwatch tcpConnection;

    /**
     * Timing for the TLS handshake.
     */
    private final @NotNull Stopwatch tlsHandshake;

    /**
     * The negotiated TLS protocol version, or {@link Optional#empty()} if the connection was not secured or the value was not captured.
     */
    private final @NotNull Optional<String> tlsProtocol;

    /**
     * The negotiated TLS cipher suite name, or {@link Optional#empty()} if the connection was not secured or the value was not captured.
     */
    private final @NotNull Optional<String> tlsCipher;

    /**
     * Shared empty {@code NetworkDetails} sentinel with zero-duration stopwatches and empty
     * TLS metadata. Used by callers that need to synthesize a response or exception that did
     * not produce a real network exchange (e.g. cache replays, client-side rate-limit
     * rejections) so consumers reading {@link #roundTrip} or the TLS optionals see
     * consistent "no exchange" defaults rather than {@code null}.
     */
    public static final @NotNull NetworkDetails EMPTY = new NetworkDetails(new BasicHttpContext());

    /**
     * Constructs a {@link NetworkDetails} by extracting internal headers from a Feign response.
     * <p>
     * Reads the headers as {@link #NetworkDetails(Map, Map)} does, from the response and the
     * request it answers.
     *
     * @param response the Feign response from which to extract network timing and TLS metadata
     */
    public NetworkDetails(@NotNull feign.Response response) {
        this(response.headers(), response.request().headers());
    }

    /**
     * Constructs a {@link NetworkDetails} from raw response and request header maps.
     * <p>
     * The request start is read from the request headers, or from the response headers when the
     * request carries none - {@link CachingFeignClient} records it on the response it returns,
     * because Feign rebuilds that response around the request it built. The response-received
     * timestamp is read from the response headers. The DNS, TCP and TLS markers and the TLS
     * protocol and cipher - the {@link #CONNECTION_HEADERS} - are read from the response headers,
     * where the client's transport records them, or from the request headers when the response
     * carries none of them. Any missing header defaults to {@link Instant#EPOCH} for timestamps
     * and {@link Optional#empty()} for strings.
     *
     * @param responseHeaders the response headers carrying the response-received marker and the
     *                        connection markers, and the request-start marker when the request
     *                        headers lack it
     * @param requestHeaders the request headers carrying the request-start marker, and the
     *                       connection markers when the response headers lack them
     */
    public NetworkDetails(
        @NotNull Map<String, Collection<String>> responseHeaders,
        @NotNull Map<String, Collection<String>> requestHeaders
    ) {
        Instant requestStart = extractHeader(requestHeaders, REQUEST_START)
            .or(() -> extractHeader(responseHeaders, REQUEST_START))
            .map(Instant::parse)
            .orElse(Instant.EPOCH);
        Instant responseReceived = extractInstant(responseHeaders, RESPONSE_RECEIVED);
        this.roundTrip = Stopwatch.of(requestStart, responseReceived);

        Map<String, Collection<String>> connection = CONNECTION_HEADERS.stream().anyMatch(responseHeaders::containsKey)
            ? responseHeaders
            : requestHeaders;

        this.dnsResolution = extractStopwatch(connection, DNS_START, DNS_END);
        this.tcpConnection = extractStopwatch(connection, TCP_CONNECT_START, TCP_CONNECT_END);
        this.tlsHandshake = extractStopwatch(connection, TLS_HANDSHAKE_START, TLS_HANDSHAKE_END);

        this.tlsProtocol = extractHeader(connection, TLS_PROTOCOL);
        this.tlsCipher = extractHeader(connection, TLS_CIPHER);
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
        Object value = context.getAttribute(id);
        return value != null ? (T) value : defaultValue;
    }

    /**
     * Checks whether the given header name is an internal header injected by the HTTP
     * interceptor layer.
     * <p>
     * Internal headers use the {@code X-Internal-} prefix and are excluded from the
     * public response headers exposed through {@link Response#getHeaders()}. The prefix is
     * matched ignoring case, as HTTP header names are, because a {@link feign.Response}
     * holds its header names in lower case.
     *
     * @param headerName the header name to test
     * @return {@code true} if the header name starts with the internal header prefix, in any
     *         case; {@code false} otherwise
     */
    public static boolean isInternalHeader(@NotNull String headerName) {
        return headerName.regionMatches(true, 0, INTERNAL_HEADER_PREFIX, 0, INTERNAL_HEADER_PREFIX.length());
    }

    /**
     * Checks whether the given header name is one of the {@link #CONNECTION_HEADERS} the client's
     * transport records on a response, matched ignoring case as {@link #isInternalHeader(String)}
     * matches.
     *
     * @param headerName the header name to test
     * @return {@code true} if the header name is a connection marker, in any case; {@code false}
     *         otherwise
     */
    public static boolean isConnectionHeader(@NotNull String headerName) {
        return CONNECTION_HEADERS.stream().anyMatch(headerName::equalsIgnoreCase);
    }

    /**
     * Checks whether the given header name is one of the {@link #CLIENT_HEADERS} the client writes
     * itself, matched ignoring case as {@link #isInternalHeader(String)} matches.
     * <p>
     * The client's transport removes a request header for which this answers {@code true} and
     * sends every other as given, the internal prefix or not.
     *
     * @param headerName the header name to test
     * @return {@code true} if the header name is one the client writes itself, in any case;
     *         {@code false} otherwise
     */
    public static boolean isClientHeader(@NotNull String headerName) {
        return CLIENT_HEADERS.stream().anyMatch(headerName::equalsIgnoreCase);
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
