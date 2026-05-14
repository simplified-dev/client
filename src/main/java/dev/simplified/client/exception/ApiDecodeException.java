package dev.simplified.client.exception;

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
        super(cause, "Decode", context);
    }

}
