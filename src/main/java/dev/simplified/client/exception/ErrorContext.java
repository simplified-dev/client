package dev.simplified.client.exception;

import dev.simplified.client.decoder.InternalErrorDecoder;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.NetworkDetails;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Map;

/**
 * Immutable primitive bundle carrying the HTTP context of a failed request, used to
 * construct an {@link ApiException} without coupling the exception hierarchy to
 * {@link feign.Response} or {@link feign.Request}.
 * <p>
 * Instances are built once at the {@link InternalErrorDecoder} boundary via
 * {@link #fromFeign(feign.Response, byte[])} and then handed to typed exception
 * constructors. Both header maps are retained so {@link ApiException} can lazily
 * construct its {@link NetworkDetails} on demand; the request-headers map is otherwise
 * unused by the public {@link ApiException} surface, but holding one extra reference
 * is far cheaper than eagerly parsing the timing instants for every exception that is
 * inspected only via {@link ApiException#getStatus()}.
 * <p>
 * {@link #statusCode()} is the numeric status and {@link #status()} its constant. A code
 * {@link HttpStatus} has no constant for, such as {@code 460}, is carried as it was sent, with
 * {@link HttpStatus#UNKNOWN_ERROR} as its status; {@link #isKnownStatus()} tells the two apart.
 *
 * @param status the HTTP status returned by the remote server, or
 *               {@link HttpStatus#UNKNOWN_ERROR} for a code {@link HttpStatus} has no
 *               constant for
 * @param statusCode the numeric status code returned by the remote server, including one
 *                   {@link HttpStatus} has no constant for
 * @param requestMethod the HTTP method of the originating request
 * @param requestUrl the URL of the originating request
 * @param responseHeaders the raw response headers, including the internal round-trip and
 *                        connection markers consumed by {@link NetworkDetails}
 * @param requestHeaders the raw headers of the request as Feign built it
 * @param bodyBytes the buffered response body bytes - empty when the body was absent
 */
public record ErrorContext(
    @NotNull HttpStatus status,
    int statusCode,
    @NotNull HttpMethod requestMethod,
    @NotNull String requestUrl,
    @NotNull Map<String, Collection<String>> responseHeaders,
    @NotNull Map<String, Collection<String>> requestHeaders,
    byte @NotNull [] bodyBytes
) {

    /**
     * Constructs a new {@code ErrorContext}, refusing a status that is not the constant of the
     * status code.
     *
     * @throws IllegalArgumentException if {@code status} is not the constant {@link HttpStatus}
     *         has for {@code statusCode}, or {@link HttpStatus#UNKNOWN_ERROR} for a code it has
     *         no constant for
     */
    public ErrorContext {
        if (status.getCode() != statusCode && (status != HttpStatus.UNKNOWN_ERROR || HttpStatus.findByCode(statusCode).isPresent()))
            throw new IllegalArgumentException(String.format("Status '%s' is not the status of code '%s'", status, statusCode));
    }

    /**
     * Constructs a new {@code ErrorContext} whose status code is the code of {@code status}.
     *
     * @param status the HTTP status returned by the remote server, or the synthetic status of a
     *               failure without one
     * @param requestMethod the HTTP method of the originating request
     * @param requestUrl the URL of the originating request
     * @param responseHeaders the raw response headers, including the internal round-trip and
     *                        connection markers consumed by {@link NetworkDetails}
     * @param requestHeaders the raw headers of the request as Feign built it
     * @param bodyBytes the buffered response body bytes - empty when the body was absent
     */
    public ErrorContext(
        @NotNull HttpStatus status,
        @NotNull HttpMethod requestMethod,
        @NotNull String requestUrl,
        @NotNull Map<String, Collection<String>> responseHeaders,
        @NotNull Map<String, Collection<String>> requestHeaders,
        byte @NotNull [] bodyBytes
    ) {
        this(status, status.getCode(), requestMethod, requestUrl, responseHeaders, requestHeaders, bodyBytes);
    }

    /**
     * Builds an {@code ErrorContext} from a buffered {@link feign.Response} anchor and its
     * pre-extracted body bytes.
     * <p>
     * Called once at the {@link InternalErrorDecoder} boundary so the decoder is the sole
     * site that touches feign types when constructing typed exceptions. A status code
     * {@link HttpStatus} has no constant for is carried as {@link #statusCode()}, with
     * {@link HttpStatus#UNKNOWN_ERROR} as {@link #status()}.
     *
     * @param anchor the buffered feign response carrying status, headers, and request metadata
     * @param bodyBytes the response body bytes already extracted from {@code anchor}
     * @return a primitive context bundle ready to feed an {@link ApiException} constructor
     */
    public static @NotNull ErrorContext fromFeign(@NotNull feign.Response anchor, byte @NotNull [] bodyBytes) {
        int statusCode = anchor.status();

        return new ErrorContext(
            HttpStatus.findByCode(statusCode).orElse(HttpStatus.UNKNOWN_ERROR),
            statusCode,
            HttpMethod.of(anchor.request().httpMethod().name()),
            anchor.request().url(),
            anchor.headers(),
            anchor.request().headers(),
            bodyBytes
        );
    }

    /**
     * Tells whether {@link HttpStatus} has a constant for {@link #statusCode()}.
     *
     * @return {@code true} when {@link #status()} is the constant of {@link #statusCode()},
     *         {@code false} when it stands in for a code {@link HttpStatus} has no constant for
     */
    public boolean isKnownStatus() {
        return this.status.getCode() == this.statusCode;
    }

}
