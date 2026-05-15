package dev.simplified.client.decoder;

import dev.simplified.client.Client;
import dev.simplified.client.cache.ResponseCache;
import dev.simplified.client.exception.ApiException;
import dev.simplified.client.exception.ErrorContext;
import dev.simplified.client.exception.NotModifiedException;
import dev.simplified.client.exception.PreconditionFailedException;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.exception.RetryableApiException;
import dev.simplified.client.response.HttpState;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.route.RouteDiscovery;
import dev.simplified.client.util.BodyBuffering;
import dev.simplified.client.util.RetryAfterParser;
import feign.Util;
import feign.codec.ErrorDecoder;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.OptionalLong;

/**
 * Feign {@link ErrorDecoder} that sits between the HTTP transport and the caller, providing
 * rate limit detection, retry tracking, and delegation to the client-supplied
 * {@link ClientErrorDecoder}.
 * <p>
 * When an error response is received the decoder executes the following pipeline:
 * <ol>
 *   <li>Maintains a per-thread {@link RetryContext} that tracks consecutive retry attempts
 *       for the same method key. The {@code methodKey} parameter from feign is referenced
 *       only here and never propagated past this method.</li>
 *   <li>Buffers the response body into a {@code byte[]} once and builds a primitive
 *       {@link ErrorContext} via {@link ErrorContext#fromFeign(feign.Response, byte[])}.
 *       That single boundary call is the only feign-touch site involved in producing typed
 *       exceptions.</li>
 *   <li>If the response status is {@link HttpStatus#TOO_MANY_REQUESTS 429}, it constructs a
 *       {@link RateLimitException} directly; if {@link HttpStatus#PRECONDITION_FAILED}, a
 *       {@link PreconditionFailedException}; if a 3xx redirection, a
 *       {@link NotModifiedException}. Otherwise it delegates to the client-supplied
 *       {@link ClientErrorDecoder} for domain-specific error parsing.</li>
 *   <li>Reflectively sets the cumulative {@code retryAttempts} count on the resulting
 *       {@link ApiException} via the shared {@link Reflection} accessor.</li>
 *   <li>Records the exception via {@link ResponseCache#recordLastResponse(dev.simplified.client.response.Response)}
 *       so it is visible through {@link Client#getLastResponse()}.</li>
 *   <li>If a {@code Retry-After} header is present, wraps the exception in a
 *       {@link RetryableApiException} - the only place after this point where a feign
 *       {@link feign.Request} reference still flows, so feign's retry pipeline can act on it.</li>
 *   <li>Cleans up the thread-local retry context once a request sequence completes without
 *       further retries.</li>
 * </ol>
 * <p>
 * This class is instantiated internally by {@link Client} during Feign
 * builder configuration and is not intended for direct use by application code.
 *
 * @see ClientErrorDecoder
 * @see RateLimitException
 * @see RetryableApiException
 * @see RetryAfterParser
 */
public final class InternalErrorDecoder implements ErrorDecoder {

    /**
     * Direct handle to the {@code retryAttempts} field on {@link ApiException}. Resolved
     * via {@link MethodHandles#privateLookupIn} so this decoder can write the field across
     * the {@code decoder} / {@code exception} package boundary without exposing a public
     * setter or constructor parameter on {@code ApiException}. VarHandle stores are
     * JIT-intrinsified down to plain field stores at hot temperatures, eliminating the
     * reflection thunks the previous {@code FieldAccessor}-based access went through.
     * <p>
     * Note: works because the entire client module currently lives in the unnamed module.
     * If a {@code module-info.java} is added later, the exception module must
     * {@code opens dev.simplified.client.exception} to this decoder module for the
     * {@code privateLookupIn} call to succeed.
     */
    private static final @NotNull VarHandle RETRY_ATTEMPTS_HANDLE;
    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(ApiException.class, MethodHandles.lookup());
            RETRY_ATTEMPTS_HANDLE = lookup.findVarHandle(ApiException.class, "retryAttempts", int.class);
        } catch (IllegalAccessException | NoSuchFieldException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    /**
     * The client-supplied decoder responsible for domain-specific error parsing.
     */
    private final @NotNull ClientErrorDecoder customDecoder;

    /**
     * The route discovery engine used to resolve route metadata for rate limit exceptions.
     */
    private final @NotNull RouteDiscovery routeDiscovery;

    /**
     * The shared response cache used for observability of error outcomes.
     */
    private final @NotNull ResponseCache responseCache;

    /**
     * Thread-local retry state tracker.
     */
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
        RetryContext retryCtx = this.retryContext.get();

        // Check if this is a retry of the same request. methodKey is used here and only here -
        // it is feign-specific retry-tracking jargon and never crosses into ErrorContext,
        // ApiException, decoder interfaces, or domain subclasses.
        boolean isRetry = methodKey.equals(retryCtx.lastMethodKey);

        if (isRetry) {
            retryCtx.retryAttempt++;
        } else {
            retryCtx.retryAttempt = 0;
            retryCtx.lastMethodKey = methodKey;
        }

        // Buffer the body once; rebuild the feign anchor solely so a retryable wrapper can later
        // hand feign back its own Request object. The primitive ErrorContext is the canonical
        // input to every typed exception below.
        byte[] bodyBytes = bufferBodyBytes(response);
        feign.Response anchor = response.toBuilder().body(bodyBytes).build();
        ErrorContext context = ErrorContext.fromFeign(anchor, bodyBytes);

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

        if (HttpState.REDIRECTION.containsCode(context.status().getCode())) {
            exception = new NotModifiedException(context);
        } else if (context.status() == HttpStatus.PRECONDITION_FAILED) {
            exception = new PreconditionFailedException(context);
        } else if (context.status() == HttpStatus.TOO_MANY_REQUESTS) {
            exception = new RateLimitException(
                context,
                this.routeDiscovery.findMatchingMetadata(context.requestUrl())
            );
        } else {
            exception = this.customDecoder.decode(context);
        }

        RETRY_ATTEMPTS_HANDLE.set(exception, retryCtx.retryAttempt);
        this.responseCache.recordLastResponse(exception);

        // If retryable, wrap for Feign's retry mechanism. The feign.Request flows through here
        // and into RetryableException's own retained field - it is never stored on the wrapper
        // or on the underlying ApiException.
        OptionalLong retryAfter = RetryAfterParser.parseFromHeaders(context.responseHeaders());

        if (retryAfter.isPresent())
            return new RetryableApiException(exception, retryAfter.getAsLong(), anchor.request());

        // If this was the final attempt (no retry-after), clean up context
        if (!isRetry)
            this.retryContext.remove();

        return exception;
    }

    /**
     * Buffers the given Feign response's body into a {@code byte[]} so the resulting bytes
     * can drive both the rebuilt anchor for retry plumbing and the primitive
     * {@link ErrorContext} fed to every typed exception.
     * <p>
     * Returns an empty array when the body is absent or unreadable.
     *
     * @param response the raw Feign response received from the transport
     * @return the buffered body bytes (possibly empty)
     */
    private static byte @NotNull [] bufferBodyBytes(@NotNull feign.Response response) {
        feign.Response.Body raw = response.body();

        if (raw == null)
            return new byte[0];

        try {
            return BodyBuffering.toByteArray(raw, response.headers());
        } catch (IOException ex) {
            return new byte[0];
        } finally {
            Util.ensureClosed(raw);
        }
    }

    /**
     * Thread-local mutable holder that tracks the method key and cumulative retry attempt
     * count for a single request sequence within a thread.
     */
    private static final class RetryContext {

        /**
         * The Feign method key of the most recently decoded error, or {@code null} if none.
         */
        private String lastMethodKey = null;

        /**
         * The number of consecutive retry attempts for the current method key.
         */
        private int retryAttempt = 0;

    }

}
