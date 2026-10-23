package dev.simplified.client.exception;

import dev.simplified.client.response.HttpStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown when a successful HTTP response cannot be deserialized into the expected type.
 * <p>
 * Unlike other {@link ApiException} subclasses which represent HTTP error responses,
 * this exception indicates that the server returned a successful status code but the
 * response body could not be decoded by the configured deserializer (e.g. Gson).
 * The full response body is preserved in {@link #getBody()} - lazily decoded on demand
 * from the buffered context's bytes - for debugging.
 *
 * @see ApiException
 */
public final class ApiDecodeException extends ApiException {

    /**
     * Constructs a new {@code ApiDecodeException} wrapping a decode failure.
     * <p>
     * The supplied {@code cause} is forwarded to the {@link ApiException} superclass and
     * preserved as the underlying cause for full diagnostic context. The response body is
     * read on demand from {@code context}'s buffered bytes via {@link ApiException#getBody()},
     * avoiding any duplicate decode at the decode-error site.
     *
     * @param cause the exception thrown during deserialization
     * @param context the primitive HTTP context whose bytes carry the undecodable body
     */
    public ApiDecodeException(@NotNull Exception cause, @NotNull ErrorContext context) {
        super(cause, "Decode", context, decodeMessage(context), true);
    }

    /**
     * Synthesizes the exception message from the request method, URL, and status, naming the
     * body rather than the status as what failed.
     * <p>
     * Produces a diagnostic line of the form
     * {@code "GET https://api.example.com/v1/resource answered 200 OK with a body that could not be decoded"},
     * or {@code "GET https://api.example.com/v1/resource answered unknown status 460 with a body that could not be decoded"}
     * for a code {@link HttpStatus} has no constant for.
     *
     * @param context the primitive context whose fields drive the message
     * @return the synthesized message text
     */
    private static @NotNull String decodeMessage(@NotNull ErrorContext context) {
        String status = context.isKnownStatus()
            ? "%d %s".formatted(context.status().getCode(), context.status().getMessage())
            : "unknown status %d".formatted(context.statusCode());

        return "%s %s answered %s with a body that could not be decoded".formatted(context.requestMethod(), context.requestUrl(), status);
    }

}
