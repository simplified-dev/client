package dev.simplified.client.exception;

import dev.simplified.annotations.Getter;
import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.HttpStatus;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Map;

/**
 * Thrown when the body of a {@code 2xx} response a {@link Client} reads is larger than the
 * {@linkplain ClientConfig#maxBodyBytes cap} its configuration sets - one read off the wire,
 * whose exchange is aborted once the body passes the cap, its connection closed rather than
 * drained, so the rest of the body is never downloaded, or one the response cache would replay.
 * <p>
 * It is the contract client's counterpart of {@link UrlFetchException.BodyCapExceeded}, and
 * carries what that one does: the synthetic {@link HttpStatus#IO_ERROR} as its
 * {@link #getStatus()}, the headers of the refused response as its {@link #getHeaders()}, and no
 * body. A response with any other status raises the exception for its status instead, carrying
 * its body cut at the cap, and its exchange is aborted the same way.
 *
 * @see ClientConfig.Builder#withMaxBodyBytes(long)
 * @see UrlFetchException.BodyCapExceeded
 */
@Getter
public final class BodyCapExceededException extends ApiException {

    /**
     * The short name identifying this exception type in logs and error tracking.
     */
    public static final @NotNull String NAME = "BodyCapExceeded";

    /**
     * The message format, taking the cap, the request method and the request URL.
     */
    private static final @NotNull String MESSAGE = "Response body exceeded cap of '%s' bytes for %s '%s'";

    /**
     * The maximum body size in bytes the client was held to.
     */
    private final long maxBytes;

    /**
     * Constructs a new {@code BodyCapExceededException} with the request and response headers of
     * the refused exchange and the cap its body passed.
     *
     * @param requestMethod the HTTP method of the request the refused response answers
     * @param requestUrl the URL of the request the refused response answers
     * @param responseHeaders the headers of the refused response, including the internal
     *                        round-trip and connection markers its network details are read from
     * @param requestHeaders the headers of the request as Feign built it
     * @param maxBytes the maximum body size in bytes the client was held to
     */
    public BodyCapExceededException(
        @NotNull HttpMethod requestMethod,
        @NotNull String requestUrl,
        @NotNull Map<String, Collection<String>> responseHeaders,
        @NotNull Map<String, Collection<String>> requestHeaders,
        long maxBytes
    ) {
        super(
            null,
            NAME,
            new ErrorContext(HttpStatus.IO_ERROR, requestMethod, requestUrl, responseHeaders, requestHeaders, new byte[0]),
            String.format(MESSAGE, maxBytes, requestMethod, requestUrl),
            true
        );
        this.maxBytes = maxBytes;
    }

}
