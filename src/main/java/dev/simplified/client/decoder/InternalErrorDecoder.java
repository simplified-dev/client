package dev.simplified.client.decoder;

import dev.simplified.client.Client;
import dev.simplified.client.exception.ApiException;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.exception.RetryableApiException;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.Response;
import dev.simplified.client.response.RetryAfterParser;
import dev.simplified.client.route.RouteDiscovery;
import dev.simplified.collection.concurrent.ConcurrentList;
import dev.simplified.reflection.Reflection;
import dev.simplified.reflection.accessor.FieldAccessor;
import feign.codec.ErrorDecoder;
import org.jetbrains.annotations.NotNull;

import java.util.OptionalLong;

/**
 * Feign {@link ErrorDecoder} that sits between the HTTP transport and the caller, providing
 * rate limit detection, retry tracking, and delegation to the client-supplied
 * {@link ClientErrorDecoder}.
 * <p>
 * When an error response is received the decoder executes the following pipeline:
 * <ol>
 *   <li>Maintains a per-thread {@link RetryContext} that tracks consecutive retry attempts
 *       for the same method key.</li>
 *   <li>If the response status is {@link HttpStatus#TOO_MANY_REQUESTS 429}, it constructs a
 *       {@link RateLimitException} directly; otherwise it delegates to the
 *       {@link ClientErrorDecoder} for domain-specific error parsing.</li>
 *   <li>Reflectively sets the cumulative {@code retryAttempts} count on the resulting
 *       {@link ApiException} via the shared {@link Reflection} accessor.</li>
 *   <li>Appends the exception to the shared {@code recentResponses} list so it is visible
 *       through {@link dev.simplified.client.Client#getRecentResponses()}.</li>
 *   <li>If a {@code Retry-After} header is present, wraps the exception in a
 *       {@link RetryableApiException} that Feign's retry mechanism can act upon.</li>
 *   <li>Cleans up the thread-local retry context once a request sequence completes without
 *       further retries.</li>
 * </ol>
 * <p>
 * This class is instantiated internally by {@link dev.simplified.client.Client} during Feign
 * builder configuration and is not intended for direct use by application code.
 *
 * @see ClientErrorDecoder
 * @see RateLimitException
 * @see RetryableApiException
 * @see RetryAfterParser
 */
public final class InternalErrorDecoder implements ErrorDecoder {

    /** Pre-resolved field accessor for the {@code retryAttempts} field on {@link ApiException}. */
    private static final @NotNull FieldAccessor<?> RETRY_ATTEMPTS_FIELD = new Reflection<>(ApiException.class).getField("retryAttempts");

    /** The client-supplied decoder responsible for domain-specific error parsing. */
    private final @NotNull ClientErrorDecoder customDecoder;

    /** The route discovery engine used to resolve route metadata for rate limit exceptions. */
    private final @NotNull RouteDiscovery routeDiscovery;

    /** The shared recent response list maintained by the owning {@link Client}. */
    private final @NotNull ConcurrentList<Response<?>> recentResponses;

    /** Thread-local retry state tracker. */
    private final @NotNull ThreadLocal<RetryContext> retryContext;

    /**
     * Constructs a new internal error decoder.
     *
     * @param clientDecoder the client-supplied decoder for domain-specific error parsing
     * @param routeDiscovery the route discovery engine for resolving route metadata
     * @param recentResponses the shared list to which decoded error responses are appended
     */
    public InternalErrorDecoder(@NotNull ClientErrorDecoder clientDecoder, @NotNull RouteDiscovery routeDiscovery, @NotNull ConcurrentList<Response<?>> recentResponses) {
        this.customDecoder = clientDecoder;
        this.routeDiscovery = routeDiscovery;
        this.recentResponses = recentResponses;
        this.retryContext = ThreadLocal.withInitial(RetryContext::new);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull Exception decode(@NotNull String methodKey, feign.Response response) {
        RetryContext context = this.retryContext.get();

        // Check if this is a retry of the same request
        boolean isRetry = methodKey.equals(context.lastMethodKey);

        if (isRetry) {
            context.retryAttempt++;
        } else {
            // New request - reset counter
            context.retryAttempt = 0;
            context.lastMethodKey = methodKey;
        }

        ApiException exception = response.status() == HttpStatus.TOO_MANY_REQUESTS.getCode() ?
            new RateLimitException(
                methodKey,
                response,
                this.routeDiscovery.findMatchingMetadata(response.request().url())
            ) : this.customDecoder.decode(methodKey, response);

        RETRY_ATTEMPTS_FIELD.set(exception, context.retryAttempt);
        this.recentResponses.add(exception);

        // If retryable, wrap for Feign's retry mechanism
        OptionalLong retryAfter = RetryAfterParser.parseFromHeaders(response.headers());

        if (retryAfter.isPresent())
            return new RetryableApiException(exception, retryAfter.getAsLong());

        // If this was the final attempt (no retry-after), clean up context
        if (!isRetry)
            this.retryContext.remove();

        return exception;
    }

    /**
     * Thread-local mutable holder that tracks the method key and cumulative retry attempt
     * count for a single request sequence within a thread.
     */
    private static final class RetryContext {

        /** The Feign method key of the most recently decoded error, or {@code null} if none. */
        private String lastMethodKey = null;

        /** The number of consecutive retry attempts for the current method key. */
        private int retryAttempt = 0;

    }

}
