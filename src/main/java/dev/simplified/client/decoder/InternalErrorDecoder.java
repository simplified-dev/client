package dev.simplified.client.decoder;

import dev.simplified.client.Client;
import dev.simplified.client.cache.ResponseCache;
import dev.simplified.client.exception.ApiException;
import dev.simplified.client.exception.NotModifiedException;
import dev.simplified.client.exception.PreconditionFailedException;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.exception.RetryableApiException;
import dev.simplified.client.response.HttpState;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.RetryAfterParser;
import dev.simplified.client.route.RouteDiscovery;
import dev.simplified.reflection.Reflection;
import dev.simplified.reflection.accessor.FieldAccessor;
import feign.Util;
import feign.codec.ErrorDecoder;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
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
 *   <li>Records the exception via {@link ResponseCache#recordLastResponse(dev.simplified.client.response.Response)}
 *       so it is visible through {@link dev.simplified.client.Client#getLastResponse()}.</li>
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

    /** The shared response cache used for observability of error outcomes. */
    private final @NotNull ResponseCache responseCache;

    /** Thread-local retry state tracker. */
    private final @NotNull ThreadLocal<RetryContext> retryContext;

    /**
     * Constructs a new internal error decoder.
     *
     * @param clientDecoder the client-supplied decoder for domain-specific error parsing
     * @param routeDiscovery the route discovery engine for resolving route metadata
     * @param responseCache the shared response cache used for recording error responses
     */
    public InternalErrorDecoder(@NotNull ClientErrorDecoder clientDecoder, @NotNull RouteDiscovery routeDiscovery, @NotNull ResponseCache responseCache) {
        this.customDecoder = clientDecoder;
        this.routeDiscovery = routeDiscovery;
        this.responseCache = responseCache;
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

        // Framework-typed HTTP statuses short-circuit the domain ClientErrorDecoder so callers
        // can catch them explicitly without inspecting numeric status codes:
        //
        //  - 3xx (redirection, including 304 Not Modified) -> NotModifiedException. Feign
        //    invokes the ErrorDecoder for every status outside 2xx, so 304 (a successful
        //    outcome of a conditional request) and other 3xx responses land here; the domain
        //    decoder would otherwise try to parse an empty body and produce a confusing
        //    "Unknown (body missing or not JSON)" trace.
        //  - 412 Precondition Failed -> PreconditionFailedException. Signals that an
        //    If-Match / If-Unmodified-Since precondition evaluated to false on the server,
        //    so the caller's cached ETag is stale and the pending mutation must be retried
        //    after re-reading the resource.
        //  - 429 Too Many Requests -> RateLimitException with server-advertised bucket
        //    metadata for exponential backoff.
        //
        // Genuine 4xx/5xx errors still flow to the domain decoder unchanged.
        ApiException exception;
        feign.Response anchor = bufferBody(response);

        if (HttpState.REDIRECTION.containsCode(anchor.status())) {
            exception = new NotModifiedException(methodKey, anchor);
        } else if (anchor.status() == HttpStatus.PRECONDITION_FAILED.getCode()) {
            exception = new PreconditionFailedException(methodKey, anchor);
        } else if (anchor.status() == HttpStatus.TOO_MANY_REQUESTS.getCode()) {
            exception = new RateLimitException(
                methodKey,
                anchor,
                this.routeDiscovery.findMatchingMetadata(anchor.request().url())
            );
        } else {
            exception = this.customDecoder.decode(methodKey, anchor);
        }

        RETRY_ATTEMPTS_FIELD.set(exception, context.retryAttempt);
        this.responseCache.recordLastResponse(exception);

        // If retryable, wrap for Feign's retry mechanism
        OptionalLong retryAfter = RetryAfterParser.parseFromHeaders(anchor.headers());

        if (retryAfter.isPresent())
            return new RetryableApiException(exception, retryAfter.getAsLong());

        // If this was the final attempt (no retry-after), clean up context
        if (!isRetry)
            this.retryContext.remove();

        return exception;
    }

    /**
     * Buffers the given Feign response's body into a {@code byte[]}-backed body, so that
     * the resulting anchor can drive the lazy {@link ApiException} body / headers / details
     * fields without contending over a consumed stream.
     * <p>
     * Returns {@code response} unchanged when the body is already absent.
     *
     * @param response the raw Feign response received from the transport
     * @return a buffered copy whose body is a {@code byte[]} (possibly empty)
     */
    private static @NotNull feign.Response bufferBody(@NotNull feign.Response response) {
        feign.Response.Body raw = response.body();

        if (raw == null)
            return response.toBuilder().body(new byte[0]).build();

        try {
            byte[] bytes = Util.toByteArray(raw.asInputStream());
            return response.toBuilder().body(bytes).build();
        } catch (IOException ex) {
            return response.toBuilder().body(new byte[0]).build();
        } finally {
            Util.ensureClosed(raw);
        }
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
