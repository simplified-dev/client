package dev.simplified.client.exception;

import dev.simplified.client.Client;
import org.jetbrains.annotations.NotNull;

/**
 * Contract for structured error information returned by a remote API.
 * <p>
 * Implementations encapsulate the human-readable reason extracted from an HTTP
 * error response body.  Each {@link Client} subclass typically defines its own
 * concrete {@code ApiErrorResponse} whose fields match the target API's error
 * JSON schema, while this interface provides the common accessor consumed by
 * {@link ApiException}.
 *
 * @see ApiException#getResponse()
 */
public interface ApiErrorResponse {

    /**
     * The human-readable reason string describing the error, typically deserialized
     * from the response body of a failed HTTP request.
     */
    @NotNull String getReason();

}
