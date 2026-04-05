package dev.simplified.client.exception;

import dev.simplified.client.Client;
import feign.RetryableException;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown when a failed API request is eligible for automatic retry through
 * Feign's {@link RetryableException} mechanism.
 * <p>
 * When the client's error-handling pipeline determines that a failed request is
 * eligible for automatic retry (e.g. after a rate-limit back-off period), it wraps
 * the original {@link ApiException} in a {@code RetryableApiException}.  Feign's
 * built-in {@link feign.Retryer} recognizes instances of {@link RetryableException}
 * and will re-attempt the request according to its configured policy.
 * <p>
 * After all retry attempts are exhausted, the {@link Client}
 * unwrapping proxy intercepts this exception and re-throws the original
 * {@link ApiException} via {@link #getWrappedException()}, ensuring that callers
 * never observe the internal Feign wrapper type.
 *
 * @see ApiException
 * @see RateLimitException
 * @see Client
 */
public final class RetryableApiException extends RetryableException {

    /** The original {@link ApiException} that triggered the retry. */
    private final @NotNull ApiException wrappedException;

    /**
     * Constructs a retryable wrapper around the given {@link ApiException}.
     * <p>
     * The HTTP status code, message, request method, cause, and a synthetic
     * {@link feign.Request} are forwarded to the {@link RetryableException}
     * superclass so that Feign's retry infrastructure can inspect them.
     *
     * @param apiException the original API exception to wrap for retry
     * @param retryAfter epoch-millisecond timestamp indicating the earliest time
     *                     at which the request should be retried, typically derived
     *                     from a {@code Retry-After} header or a rate-limit reset timestamp
     */
    public RetryableApiException(@NotNull ApiException apiException, long retryAfter) {
        super(
            apiException.getStatus().getCode(),
            apiException.getMessage(),
            apiException.getFeignRequest().httpMethod(),
            apiException.getCause(),
            retryAfter,
            apiException.getFeignRequest()
        );

        this.wrappedException = apiException;
    }

    /**
     * Returns the original {@link ApiException} that was wrapped for retry.
     * <p>
     * Callers should use this method to obtain the fully-typed, domain-specific
     * exception (e.g. {@link RateLimitException}) once Feign's retry cycle
     * completes or is exhausted.
     *
     * @return the underlying API exception, never {@code null}
     */
    public @NotNull ApiException getWrappedException() {
        return this.wrappedException;
    }

}
