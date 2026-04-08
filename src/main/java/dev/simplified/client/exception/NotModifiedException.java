package dev.simplified.client.exception;

import dev.simplified.client.decoder.InternalErrorDecoder;
import dev.simplified.client.response.HttpStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown when a remote API responds with {@code 304 Not Modified} to a conditional request.
 *
 * <p>Unlike other {@link ApiException} subclasses, a {@code NotModifiedException} does not
 * represent an error condition - it is the expected outcome of a successful
 * {@code If-None-Match} / {@code If-Modified-Since} exchange and signals that the caller's
 * cached copy is still current. Feign's default response handling treats any non-{@code 2xx}
 * status as an error and invokes the {@link feign.codec.ErrorDecoder}; the framework's
 * {@link InternalErrorDecoder} short-circuits this behaviour for
 * {@code 3xx} responses and returns {@code NotModifiedException} instead of delegating to
 * the domain-specific {@code ClientErrorDecoder}, so callers can catch the type explicitly
 * without needing to inspect the HTTP status code.
 *
 * <p>The {@code 3xx} range in general is not an error range: {@code 301}/{@code 302}/
 * {@code 303} redirects are auto-followed by the underlying HTTP transport, {@code 304}
 * is a successful conditional-request outcome, and {@code 307}/{@code 308} are similarly
 * non-erroneous. The framework lumps all {@code 3xx} responses into this exception type
 * so callers can distinguish them from genuine {@code 4xx} client errors and {@code 5xx}
 * server errors.
 *
 * <p>Intended usage pattern:
 * <pre>{@code
 * try {
 *     ETagContext.callWithEtag(storedEtag, contract::getLatestMasterCommit);
 * } catch (NotModifiedException ex) {
 *     // cached copy is still current - no action needed
 *     return Optional.empty();
 * } catch (ApiException ex) {
 *     // genuine error - log and retry next cycle
 *     log.warn("API call failed: {}", ex.getMessage());
 * }
 * }</pre>
 *
 * @see InternalErrorDecoder
 * @see HttpStatus#NOT_MODIFIED
 */
public class NotModifiedException extends ApiException {

    /** The short name identifying this exception type in logs and error tracking. */
    public static final @NotNull String NAME = "NotModified";

    /**
     * Constructs a new {@code NotModifiedException} from a Feign method key and the raw
     * {@code 304} response.
     *
     * @param methodKey the Feign method key identifying the endpoint that was probed
     * @param response the raw Feign HTTP response carrying the {@code 304} status and any
     *                 accompanying headers ({@code ETag}, {@code Last-Modified}, etc.)
     */
    public NotModifiedException(@NotNull String methodKey, @NotNull feign.Response response) {
        super(methodKey, response, NAME);
    }

}
