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
 *
 * @param status the HTTP status code returned by the remote server
 * @param requestMethod the HTTP method of the originating request
 * @param requestUrl the URL of the originating request
 * @param responseHeaders the raw response headers, including internal instrumentation entries
 * @param requestHeaders the raw request headers, carrying the {@code X-Internal-*} timing
 *                       markers consumed by {@link NetworkDetails}
 * @param bodyBytes the buffered response body bytes - empty when the body was absent
 */
public record ErrorContext(
    @NotNull HttpStatus status,
    @NotNull HttpMethod requestMethod,
    @NotNull String requestUrl,
    @NotNull Map<String, Collection<String>> responseHeaders,
    @NotNull Map<String, Collection<String>> requestHeaders,
    byte @NotNull [] bodyBytes
) {

    /**
     * Builds an {@code ErrorContext} from a buffered {@link feign.Response} anchor and its
     * pre-extracted body bytes.
     * <p>
     * Called once at the {@link InternalErrorDecoder} boundary so the decoder is the sole
     * site that touches feign types when constructing typed exceptions.
     *
     * @param anchor the buffered feign response carrying status, headers, and request metadata
     * @param bodyBytes the response body bytes already extracted from {@code anchor}
     * @return a primitive context bundle ready to feed an {@link ApiException} constructor
     */
    public static @NotNull ErrorContext fromFeign(@NotNull feign.Response anchor, byte @NotNull [] bodyBytes) {
        return new ErrorContext(
            HttpStatus.of(anchor.status()),
            HttpMethod.of(anchor.request().httpMethod().name()),
            anchor.request().url(),
            anchor.headers(),
            anchor.request().headers(),
            bodyBytes
        );
    }

}
