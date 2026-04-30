package dev.simplified.client.response;

import dev.simplified.util.StringUtil;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Enumeration of HTTP response status codes, providing a strongly-typed representation of
 * every standard, vendor-specific, and application-specific status code used by the HTTP
 * client framework.
 * <p>
 * Each constant carries a numeric {@code getCode()} value, a human-readable
 * {@code getMessage()} string, and an owning {@link HttpState} that classifies the
 * code into a broad category (informational, success, redirection, client error, server
 * error, or a vendor/application-specific error range). When an explicit {@link HttpState}
 * is provided at construction time, the message is prefixed with the state's title
 * (e.g. {@code "Nginx Error: No Response"}) to make vendor-specific codes immediately
 * identifiable.
 * <p>
 * The enumeration covers:
 * <ul>
 *     <li>RFC 7231 standard status codes ({@code 100}-{@code 511})</li>
 *     <li>Nginx-specific codes ({@code 444}, {@code 494}-{@code 499})</li>
 *     <li>Cloudflare-specific codes ({@code 520}-{@code 530})</li>
 *     <li>Network timeout codes ({@code 598}-{@code 599})</li>
 *     <li>Application-level Java error codes ({@code 990}-{@code 999})</li>
 * </ul>
 *
 * @see HttpState
 * @see Response
 */
@Getter
public enum HttpStatus {

    // --- 1xx Informational ---

    /** {@code 100 Continue} - the server has received the request headers and the client should proceed to send the body. */
    CONTINUE(100),

    /** {@code 101 Switching Protocols} - the server is switching to the protocol requested by the client. */
    SWITCHING_PROTOCOLS(101),

    /** {@code 102 Processing} - the server has accepted the request but has not yet completed it (WebDAV). */
    PROCESSING(102),

    /** {@code 103 Early Hints} - the server is sending preliminary headers before the final response. */
    EARLY_HINTS(103),

    // --- 2xx Success ---

    /** {@code 200 OK} - the request has succeeded. */
    OK(200, "OK"),

    /** {@code 201 Created} - the request has been fulfilled and a new resource has been created. */
    CREATED(201),

    /** {@code 202 Accepted} - the request has been accepted for processing but is not yet complete. */
    ACCEPTED(202),

    /** {@code 203 Non-Authoritative Information} - the returned metadata is from a local or third-party copy. */
    NON_AUTHORITATIVE_INFORMATION(203, "Non-Authoritative Information"),

    /** {@code 204 No Content} - the server has fulfilled the request but there is no content to return. */
    NO_CONTENT(204),

    /** {@code 205 Reset Content} - the server has fulfilled the request and the client should reset the document view. */
    RESET_CONTENT(205),

    /** {@code 206 Partial Content} - the server is delivering only part of the resource due to a range header. */
    PARTIAL_CONTENT(206),

    /** {@code 207 Multi-Status} - the response body contains multiple status codes for independent operations (WebDAV). */
    MULTI_STATUS(207, "Multi-Status"),

    /** {@code 208 Already Reported} - the members of a DAV binding have already been enumerated (WebDAV). */
    ALREADY_REPORTED(208),

    /** {@code 226 IM Used} - the server has fulfilled a GET request using instance manipulations. */
    IM_USED(226, "IM Used"),

    // --- 3xx Redirection ---

    /** {@code 300 Multiple Choices} - the request has more than one possible response. */
    MULTIPLE_CHOICES(300),

    /** {@code 301 Moved Permanently} - the resource has been permanently moved to a new URI. */
    MOVED_PERMANENTLY(301),

    /** {@code 302 Found} - the resource resides temporarily under a different URI. */
    FOUND(302),

    /** {@code 303 See Other} - the response can be found under a different URI using a GET method. */
    SEE_OTHER(303),

    /** {@code 304 Not Modified} - the resource has not been modified since the last request. */
    NOT_MODIFIED(304),

    /** {@code 305 Use Proxy} - the requested resource must be accessed through the proxy given by the Location header. */
    USE_PROXY(305),

    /** {@code 306 Switch Proxy} - a now-deprecated code indicating that subsequent requests should use a different proxy. */
    SWITCH_PROXY(306),

    /** {@code 307 Temporary Redirect} - the request should be repeated with another URI but future requests should still use the original. */
    TEMPORARY_REDIRECT(307),

    /** {@code 308 Permanent Redirect} - the request and all future requests should be repeated using a different URI. */
    PERMANENT_REDIRECT(308),

    // --- 4xx Client Error ---

    /** {@code 400 Bad Request} - the server cannot process the request due to malformed syntax. */
    BAD_REQUEST(400),

    /** {@code 401 Unauthorized} - authentication is required and has failed or has not been provided. */
    UNAUTHORIZED(401),

    /** {@code 402 Payment Required} - reserved for future use; sometimes used for digital payment schemes. */
    PAYMENT_REQUIRED(402),

    /** {@code 403 Forbidden} - the server understood the request but refuses to authorize it. */
    FORBIDDEN(403),

    /** {@code 404 Not Found} - the server cannot find the requested resource. */
    NOT_FOUND(404),

    /** {@code 405 Method Not Allowed} - the request method is not supported for the target resource. */
    METHOD_NOT_ALLOWED(405),

    /** {@code 406 Not Acceptable} - the server cannot produce a response matching the accept headers sent in the request. */
    NOT_ACCEPTABLE(406),

    /** {@code 407 Proxy Authentication Required} - authentication with the proxy is required. */
    PROXY_AUTHENTICATION_REQUIRED(407),

    /** {@code 408 Request Timeout} - the server timed out waiting for the request. */
    REQUEST_TIMEOUT(408),

    /** {@code 409 Conflict} - the request could not be processed because of a conflict with the current state of the resource. */
    CONFLICT(409),

    /** {@code 410 Gone} - the resource is no longer available and will not be available again. */
    GONE(410),

    /** {@code 411 Length Required} - the request did not specify a Content-Length header, which is required. */
    LENGTH_REQUIRED(411),

    /** {@code 412 Precondition Failed} - one or more conditions in the request header fields evaluated to false. */
    PRECONDITION_FAILED(412),

    /** {@code 413 Request Entity Too Large} - the request payload is larger than the server is willing to process. */
    REQUEST_ENTITY_TOO_LARGE(413),

    /** {@code 414 Request-URI Too Long} - the request URI is longer than the server is willing to interpret. */
    REQUEST_URI_TOO_LONG(414, "Request-URI Too Long"),

    /** {@code 415 Unsupported Media Type} - the request payload format is not supported by this method on the target resource. */
    UNSUPPORTED_MEDIA_TYPE(415),

    /** {@code 416 Requested Range Not Satisfiable} - the range specified in the Range header cannot be fulfilled. */
    REQUESTED_RANGE_NOT_SATISFIABLE(416),

    /** {@code 417 Expectation Failed} - the server cannot meet the requirements of the Expect request header. */
    EXPECTATION_FAILED(417),

    /** {@code 418 I'm a teapot} - the server refuses to brew coffee because it is, in fact, a teapot (RFC 2324). */
    IM_A_TEAPOT(418, "I'm a teapot"),

    /** {@code 419 Authentication Timeout} - the previously valid authentication has expired. */
    AUTHENTICATION_TIMEOUT(419),

    /** {@code 420 Method Failure} - the method executed on the resource failed (Spring Framework extension). */
    METHOD_FAILURE(420),

    /** {@code 421 Misdirected Request} - the request was directed at a server that cannot produce a response. */
    MISDIRECTED_REQUEST(421),

    /** {@code 422 Unprocessable Entity} - the request was well-formed but semantically erroneous (WebDAV). */
    UNPROCESSABLE_ENTITY(422),

    /** {@code 423 Locked} - the resource that is being accessed is locked (WebDAV). */
    LOCKED(423),

    /** {@code 424 Failed Dependency} - the request failed because it depended on another request that failed (WebDAV). */
    FAILED_DEPENDENCY(424),

    /** {@code 425 Too Early} - the server is unwilling to process a request that might be replayed. */
    TOO_EARLY(425),

    /** {@code 426 Upgrade Required} - the client should switch to a different protocol. */
    UPGRADE_REQUIRED(426),

    /** {@code 428 Precondition Required} - the server requires the request to be conditional. */
    PRECONDITION_REQUIRED(428),

    /** {@code 429 Too Many Requests} - the user has sent too many requests in a given amount of time (rate limiting). */
    TOO_MANY_REQUESTS(429),

    /** {@code 431 Request Header Fields Too Large} - the server refuses to process the request because a header field is too large. */
    REQUEST_HEADER_FIELDS_TOO_LARGE(431),

    /** {@code 440 Login Timeout} - the client's session has expired and must log in again (IIS extension). */
    LOGIN_TIMEOUT(440),

    // --- Nginx-specific 4xx ---

    /** {@code 444 No Response} - Nginx closed the connection without sending a response to the client. */
    NO_RESPONSE(444, HttpState.NGINX_ERROR),

    /** {@code 449 Retry With} - the request should be retried after performing the appropriate action (IIS extension). */
    RETRY_WITH(449),

    /** {@code 450 Blocked by Windows Parental Controls} - access denied by Windows Parental Controls (Microsoft extension). */
    BLOCKED_BY_WINDOWS_PARENTAL_CONTROLS(450),

    /** {@code 451 Unavailable For Legal Reasons} - the resource is unavailable due to legal demands. */
    UNAVAILABLE_FOR_LEGAL_REASONS(451),

    /** {@code 494 Request Header Too Large} - Nginx returned an error because the request header was too large. */
    REQUEST_HEADER_TOO_LARGE(494, HttpState.NGINX_ERROR),

    /** {@code 495 SSL Certificate Error} - Nginx returned an error due to an invalid client certificate. */
    SSL_CERTIFICATE_ERROR(495, HttpState.NGINX_ERROR),

    /** {@code 496 SSL Certificate Required} - Nginx requires a client certificate that was not provided. */
    SSL_CERTIFICATE_REQUIRED(496, HttpState.NGINX_ERROR),

    /** {@code 497 HTTP Request Sent to HTTPS} - an HTTP request was sent to an HTTPS-only port (Nginx). */
    HTTP_REQUEST_SENT_TO_HTTPS(497, "HTTP Request Sent to HTTPS", HttpState.NGINX_ERROR),

    /** {@code 499 Client Closed Request} - the client closed the connection before the server could respond (Nginx). */
    CLIENT_CLOSED_REQUEST(499, HttpState.NGINX_ERROR),

    // --- 5xx Server Error ---

    /** {@code 500 Internal Server Error} - the server encountered an unexpected condition that prevented it from fulfilling the request. */
    INTERNAL_SERVER_ERROR(500),

    /** {@code 501 Not Implemented} - the server does not support the functionality required to fulfill the request. */
    NOT_IMPLEMENTED(501),

    /** {@code 502 Bad Gateway} - the server received an invalid response from an upstream server. */
    BAD_GATEWAY(502),

    /** {@code 503 Service Unavailable} - the server is currently unable to handle the request due to overload or maintenance. */
    SERVICE_UNAVAILABLE(503),

    /** {@code 504 Gateway Timeout} - the server did not receive a timely response from an upstream server. */
    GATEWAY_TIMEOUT(504),

    /** {@code 505 HTTP Version Not Supported} - the server does not support the HTTP protocol version used in the request. */
    HTTP_VERSION_NOT_SUPPORTED(505, "HTTP Version Not Supported"),

    /** {@code 506 Variant Also Negotiates} - transparent content negotiation resulted in a circular reference. */
    VARIANT_ALSO_NEGOTIATES(506),

    /** {@code 507 Insufficient Storage} - the server is unable to store the representation needed to complete the request (WebDAV). */
    INSUFFICIENT_STORAGE(507),

    /** {@code 508 Loop Detected} - the server detected an infinite loop while processing the request (WebDAV). */
    LOOP_DETECTED(508),

    /** {@code 509 Bandwidth Limit Exceeded} - the server has exceeded its bandwidth allocation (Apache/cPanel extension). */
    BANDWIDTH_LIMIT_EXCEEDED(509),

    /** {@code 510 Not Extended} - further extensions to the request are required for the server to fulfill it. */
    NOT_EXTENDED(510),

    /** {@code 511 Network Authentication Required} - the client needs to authenticate to gain network access. */
    NETWORK_AUTHENTICATION_REQUIRED(511),

    // --- Cloudflare-specific ---

    /** {@code 520 Web Server Returns An Unknown Error} - the origin web server returned an unexpected response to Cloudflare. */
    CLOUDFLARE_WEB_SERVER_UNKNOWN_ERROR(520, "Web Server Returns An Unknown Error", HttpState.CLOUDFLARE_ERROR),

    /** {@code 521 Web Server Is Down} - the origin web server refused or did not respond to the Cloudflare connection. */
    CLOUDFLARE_WEB_SERVER_DOWN(521, "Web Server Is Down", HttpState.CLOUDFLARE_ERROR),

    /** {@code 522 Connection Timed Out} - Cloudflare timed out contacting the origin web server. */
    CLOUDFLARE_CONNECTION_TIMED_OUT(522, "Connection Timed Out", HttpState.CLOUDFLARE_ERROR),

    /** {@code 523 Origin Is Unreachable} - Cloudflare could not reach the origin web server. */
    CLOUDFLARE_ORIGIN_IS_UNREACHABLE(523, "Origin Is Unreachable", HttpState.CLOUDFLARE_ERROR),

    /** {@code 524 A Timeout Occurred} - Cloudflare established a TCP connection but the origin did not reply in time. */
    CLOUDFLARE_A_TIME_OUT_OCCURRED(524, "A Timeout Occurred", HttpState.CLOUDFLARE_ERROR),

    /** {@code 525 SSL Handshake Failed} - Cloudflare could not negotiate an SSL/TLS handshake with the origin server. */
    CLOUDFLARE_SSL_HANDSHAKE_FAILED(525, "SSL Handshake Failed", HttpState.CLOUDFLARE_ERROR),

    /** {@code 526 Invalid SSL Certificate} - Cloudflare could not validate the SSL certificate on the origin server. */
    CLOUDFLARE_INVALID_SSL_CERTIFICATE(526, "Invalid SSL Certificate", HttpState.CLOUDFLARE_ERROR),

    /** {@code 527 Railgun Listener To Origin Error} - Cloudflare's Railgun connection to the origin server encountered an error. */
    CLOUDFLARE_RAILGUN_LISTENER_TO_ORIGIN_ERROR(527, "Railgun Listener To Origin Error", HttpState.CLOUDFLARE_ERROR),

    /** {@code 530 Generic Error} - a generic Cloudflare error, typically accompanied by a {@code 1xxx} error in the response body. */
    CLOUDFLARE_GENERIC_ERROR(530, "Generic Error", HttpState.CLOUDFLARE_ERROR),

    // --- Network Errors ---

    /** {@code 598 Read Timeout Error} - the network read operation timed out before a complete response was received. */
    NETWORK_READ_TIMEOUT_ERROR(598, "Read Timeout Error", HttpState.NETWORK_ERROR),

    /** {@code 599 Connect Timeout Error} - the network connection could not be established within the allowed time. */
    NETWORK_CONNECT_TIMEOUT_ERROR(599, "Connect Timeout Error", HttpState.NETWORK_ERROR),

    // --- Java Application Errors ---

    /** {@code 990 Socket Error} - a Java socket-level error occurred during the request. */
    SOCKET_ERROR(990, HttpState.JAVA_ERROR),

    /** {@code 991 IO Error} - a Java I/O error occurred while processing the request or response. */
    IO_ERROR(991, "IO Error", HttpState.JAVA_ERROR),

    /** {@code 999 Unknown Error} - an unclassifiable Java-level error occurred. */
    UNKNOWN_ERROR(999, HttpState.JAVA_ERROR);

    /** Cached snapshot of {@link #values()} reused by lookups and iteration to avoid the per-call defensive array clone. */
    private static final HttpStatus @NotNull [] CACHED_VALUES = values();

    /** Index of status codes to enum constants for O(1) lookup by numeric code. */
    private static final @NotNull Map<Integer, HttpStatus> BY_CODE;

    static {
        Map<Integer, HttpStatus> byCode = new HashMap<>(CACHED_VALUES.length * 2);

        for (HttpStatus value : CACHED_VALUES)
            byCode.put(value.code, value);

        BY_CODE = Map.copyOf(byCode);
    }

    /** The numeric HTTP status code. */
    private final int code;

    /** The human-readable status message, optionally prefixed with the owning {@link HttpState} title for vendor-specific codes. */
    private final @NotNull String message;

    /** The {@link HttpState} category that this status code belongs to. */
    private final @NotNull HttpState state;

    HttpStatus(int code) {
        this(code, null, null);
    }

    HttpStatus(int code, @NotNull HttpState state) {
        this(code, null, state);
    }

    HttpStatus(int code, @Nullable String message) {
        this(code, message, null);
    }

    HttpStatus(int code, @Nullable String message, @Nullable HttpState state) {
        this.code = code;
        this.state = state != null ? state : HttpState.of(code);
        message = StringUtil.isEmpty(message) ? StringUtil.capitalizeFully(this.name().replace("_", " ")) : message;

        if (state != null)
            message = String.format("%s: %s", state.getTitle(), message);

        this.message = message;
    }

    /**
     * Resolves the {@link HttpStatus} constant for the given numeric HTTP status code.
     * <p>
     * Performs an O(1) lookup against an immutable index of all defined constants and
     * returns the matching value. If no match is found, an
     * {@link IllegalArgumentException} is thrown.
     *
     * @param code the numeric HTTP status code to look up
     * @return the {@link HttpStatus} constant matching {@code code}
     * @throws IllegalArgumentException if no constant is defined for {@code code}
     */
    public static @NotNull HttpStatus of(int code) {
        HttpStatus status = BY_CODE.get(code);

        if (status == null)
            throw new IllegalArgumentException("Invalid HTTP status code: " + code);

        return status;
    }

}
