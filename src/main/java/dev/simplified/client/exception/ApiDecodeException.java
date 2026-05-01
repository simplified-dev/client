package dev.simplified.client.exception;

import feign.codec.DecodeException;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown when a successful HTTP response cannot be deserialized into the expected type.
 * <p>
 * Unlike other {@link ApiException} subclasses which represent HTTP error responses,
 * this exception indicates that the server returned a successful status code but the
 * response body could not be decoded by the configured deserializer (e.g. Gson).
 * The full response body is preserved in {@link #getBody()} - lazily decoded on demand
 * from the buffered anchor's bytes - for debugging.
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
     * <p>
     * The response body is read on demand from {@code anchor}'s buffered bytes via
     * {@link ApiException#getBody()}, eliminating the duplicate UTF-8 decode that the prior
     * pre-read-body constructor performed at the decode-error site.
     *
     * @param cause the exception thrown during deserialization
     * @param anchor the buffered Feign HTTP response whose bytes carry the undecodable body
     */
    public ApiDecodeException(@NotNull Exception cause, @NotNull feign.Response anchor) {
        super(new StacklessDecodeException(anchor.status(), cause.getMessage(), anchor.request(), cause), anchor, "Decode");
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
