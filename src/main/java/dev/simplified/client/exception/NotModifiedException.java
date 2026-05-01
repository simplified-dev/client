package dev.simplified.client.exception;

import dev.simplified.client.decoder.InternalErrorDecoder;
import dev.simplified.client.response.ETag;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.Response;
import feign.FeignException;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown when a remote API responds with {@code 304 Not Modified} to a conditional request
 * for which the framework has no cached body to serve.
 *
 * <p>Unlike other {@link ApiException} subclasses, a {@code NotModifiedException} does not
 * represent an error condition - it is the expected outcome of a successful
 * {@code If-None-Match} / {@code If-Modified-Since} exchange and signals that the caller's
 * cached copy is still current. Feign's default response handling treats any non-{@code 2xx}
 * status as an error and invokes the {@link feign.codec.ErrorDecoder}; the framework's
 * {@link InternalErrorDecoder} short-circuits this behaviour for {@code 3xx} responses and
 * returns {@code NotModifiedException} instead of delegating to the domain-specific
 * {@code ClientErrorDecoder}, so callers can catch the type explicitly without needing to
 * inspect the HTTP status code.
 *
 * <p>In most cases callers will <b>not</b> see this exception thrown. When the framework
 * holds a matching cached variant in
 * {@link dev.simplified.client.cache.ResponseCache ResponseCache},
 * {@link dev.simplified.client.cache.CachingFeignClient CachingFeignClient} transparently
 * returns a synthesized replay of the cached body before Feign's error pipeline runs -
 * the method appears to complete successfully and {@link Response#getStatus()} reports
 * {@link HttpStatus#NOT_MODIFIED} as the wire-truth indicator. This exception is reserved
 * for <i>cache-miss revalidations</i>: the server said the cache is fresh, but the
 * framework no longer holds the corresponding response (for example, streaming endpoints
 * are never cached, and cache entries are bounded by
 * {@link dev.simplified.client.request.Timings#maxCacheBytes()} and expire according to
 * each entry's {@code Cache-Control} directives capped by
 * {@link dev.simplified.client.request.Timings#cacheSafetyFallback()}).
 *
 * <p>The {@code 3xx} range in general is not an error range: {@code 301}/{@code 302}/
 * {@code 303} redirects are auto-followed by the underlying HTTP transport, {@code 304}
 * is a successful conditional-request outcome, and {@code 307}/{@code 308} are similarly
 * non-erroneous. The framework lumps all {@code 3xx} responses into this exception type
 * so callers can distinguish them from genuine {@code 4xx} client errors and {@code 5xx}
 * server errors.
 *
 * <p>Intended usage pattern - only needed when a cached body may not exist (e.g. after
 * long idle periods, across client restarts, or for streaming endpoints):
 * <pre>{@code
 * try {
 *     Commit commit = contract.getLatestCommit();  // framework auto-attaches If-None-Match
 *     return Optional.of(commit);
 * } catch (NotModifiedException ex) {
 *     // cache miss on revalidation - the server confirmed freshness but the framework
 *     // no longer has the body; re-fetch without conditional headers and try again.
 *     return Optional.empty();
 * } catch (ApiException ex) {
 *     log.warn("API call failed: {}", ex.getMessage());
 *     return Optional.empty();
 * }
 * }</pre>
 *
 * @see InternalErrorDecoder
 * @see dev.simplified.client.cache.CachingFeignClient
 * @see ETag
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
        super(FeignException.errorStatus(methodKey, response), response, NAME);
    }

}
