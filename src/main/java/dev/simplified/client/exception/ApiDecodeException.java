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
     * The cause is wrapped in a stack-trace-suppressed {@link DecodeException} to satisfy
     * the {@link ApiException} constructor's {@link feign.FeignException} parameter without
     * paying the cost of a redundant {@link Throwable#fillInStackTrace()} on the inner
     * wrapper. The outer {@code ApiDecodeException} retains its own writable stack trace,
     * and the original {@code cause} is preserved for full diagnostic context.
     *
     * @param cause the exception thrown during deserialization
     * @param response the raw Feign HTTP response that could not be decoded
     * @param body the pre-read response body text
     */
    public ApiDecodeException(@NotNull Exception cause, @NotNull feign.Response response, @NotNull String body) {
        super(new StacklessDecodeException(response.status(), cause.getMessage(), response.request(), cause), response, "Decode", body);
    }

    /**
     * Stack-trace-suppressed {@link DecodeException} subclass used purely as a transport
     * shim for the {@link ApiException} constructor's {@link feign.FeignException} parameter.
     */
    private static final class StacklessDecodeException extends DecodeException {

        StacklessDecodeException(int status, @NotNull String message, @NotNull feign.Request request, @NotNull Throwable cause) {
            super(status, message, request, cause);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }

    }

}
