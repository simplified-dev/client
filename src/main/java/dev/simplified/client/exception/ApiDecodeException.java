package dev.simplified.client.exception;

import feign.codec.DecodeException;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown when a successful HTTP response cannot be deserialized into the expected type.
 * <p>
 * Unlike other {@link ApiException} subclasses which represent HTTP error responses,
 * this exception indicates that the server returned a successful status code but the
 * response body could not be decoded by the configured deserializer (e.g. Gson).
 * The full response body is preserved in {@link #getBody()} for debugging.
 *
 * @see ApiException
 */
public final class ApiDecodeException extends ApiException {

    /**
     * Constructs a new {@code ApiDecodeException} from a Feign response and decode failure.
     * <p>
     * The cause is wrapped in a {@link DecodeException} to satisfy the
     * {@link ApiException} constructor's {@link feign.FeignException} parameter.
     *
     * @param cause the exception thrown during deserialization
     * @param response the raw Feign HTTP response that could not be decoded
     * @param body the pre-read response body text
     */
    public ApiDecodeException(@NotNull Exception cause, @NotNull feign.Response response, @NotNull String body) {
        super(new DecodeException(response.status(), cause.getMessage(), response.request(), cause), response, "Decode", body);
    }

}
