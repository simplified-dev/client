package dev.simplified.client.exception;

import dev.simplified.annotations.Getter;
import dev.simplified.client.fetch.UrlFetcher;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.HttpState;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.NetworkDetails;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.intellij.lang.annotations.PrintFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * Thrown when a {@link UrlFetcher} call cannot complete - either because the local rate-limit
 * budget rejected the request, the response body exceeded the fetch's cap, the transport
 * failed, or the origin answered with a status outside the {@link HttpState#SUCCESS 2xx} class.
 * <p>
 * Each cause has its own type, so a caller tells them apart by the type it catches:
 * <ul>
 *   <li>{@link Redirection} - the origin answered with a {@code 3xx} code the fetch neither
 *       follows nor answers from the response cache, whether or not {@link HttpStatus} has a
 *       constant for it</li>
 *   <li>{@link ClientError} - the origin answered with a status {@link HttpState#CLIENT_ERROR}
 *       classifies, {@code 400} to {@code 451}, or with a {@code 4xx} code {@link HttpStatus}
 *       has no constant for, outside the Nginx range {@code 494-499}</li>
 *   <li>{@code UrlFetchException} itself - the origin answered with any other status outside
 *       the {@code 2xx} class: a {@code 5xx}, or a vendor-specific code such as Nginx's
 *       {@code 444} and {@code 494-499}; or with any other code {@link HttpStatus} has no
 *       constant for outside the {@code 2xx} class, which a fetch reads as {@code 200}</li>
 *   <li>{@link Transport} - no response arrived</li>
 *   <li>{@link BodyCapExceeded} - the body of a {@code 2xx} response was larger than the
 *       fetch's cap; any other status raises its own type whatever the size of its body</li>
 *   <li>{@link RateLimited} - the local budget refused the request before it was sent</li>
 * </ul>
 * {@link #getStatus()} does not separate them on its own: a {@link RateLimited} carries a
 * synthetic {@code 429} and a {@link Transport} or {@link BodyCapExceeded} a synthetic
 * {@link HttpStatus#IO_ERROR}.
 * <p>
 * {@link #getStatusCode()} is the numeric status. For a code {@link HttpStatus} has no constant
 * for, it is the code the origin sent and {@link #getStatus()} is
 * {@link HttpStatus#UNKNOWN_ERROR}; otherwise it is the code of {@link #getStatus()}.
 * <p>
 * Extends {@link ApiException} so URL-fetch failures participate in the same exception family
 * as contract-driven API errors: {@link #getStatus()}, {@link #getHeaders()},
 * {@link #getDetails()}, {@link #getRequest()}, and {@link #getBody()} are all available for
 * observability code that treats responses and exceptions uniformly. The underlying
 * {@link ErrorContext} bundles the HTTP primitives without any feign coupling.
 *
 * @see UrlFetcher
 * @see ApiException
 * @see ErrorContext
 */
public class UrlFetchException extends ApiException {

    /**
     * The short name identifying URL-fetch errors in logs and error tracking.
     */
    public static final @NotNull String NAME = "UrlFetch";

    /**
     * The message format of an exception raised for an origin's error status, taking the
     * status code, the status message and the URL.
     */
    private static final @NotNull String STATUS_MESSAGE = "Origin returned %d %s for URL '%s'";

    /**
     * The message format of an exception raised for a status code {@link HttpStatus} has no
     * constant for, taking the status code and the URL.
     */
    private static final @NotNull String UNKNOWN_STATUS_MESSAGE = "Origin returned unknown status %d for URL '%s'";

    /**
     * The lowest code of the {@code 4xx} class.
     */
    private static final int CLIENT_ERROR_MIN = 400;

    /**
     * The highest code of the {@code 4xx} class.
     */
    private static final int CLIENT_ERROR_MAX = 499;

    /**
     * Constructs a new {@code UrlFetchException} with the given context and message.
     *
     * @param context the HTTP context bundle carrying status, headers, body, and request metadata
     * @param message the detail message describing the failure
     */
    public UrlFetchException(@NotNull ErrorContext context, @NotNull String message) {
        super(null, NAME, context, message, true);
    }

    /**
     * Constructs a new {@code UrlFetchException} with the given cause, context, and message.
     *
     * @param cause the underlying transport or decode failure
     * @param context the HTTP context bundle carrying status, headers, body, and request metadata
     * @param message the detail message describing the failure
     */
    public UrlFetchException(@NotNull Throwable cause, @NotNull ErrorContext context, @NotNull String message) {
        super(cause, NAME, context, message, true);
    }

    /**
     * Constructs a new {@code UrlFetchException} with the given context and a formatted message.
     *
     * @param context the HTTP context bundle carrying status, headers, body, and request metadata
     * @param message the format string of the detail message
     * @param args the format arguments for {@code message}
     */
    public UrlFetchException(@NotNull ErrorContext context, @NotNull @PrintFormat String message, @Nullable Object... args) {
        super(null, NAME, context, String.format(message, args), true);
    }

    /**
     * Constructs a new {@code UrlFetchException} with the given cause, context, and a formatted
     * message.
     *
     * @param cause the underlying transport or decode failure
     * @param context the HTTP context bundle carrying status, headers, body, and request metadata
     * @param message the format string of the detail message
     * @param args the format arguments for {@code message}
     */
    public UrlFetchException(
        @NotNull Throwable cause,
        @NotNull ErrorContext context,
        @NotNull @PrintFormat String message,
        @Nullable Object... args
    ) {
        super(cause, NAME, context, String.format(message, args), true);
    }

    /**
     * Constructs a new {@code UrlFetchException} with a pre-built {@link NetworkDetails} and a
     * message, bypassing the header-map lazy build inside {@link ApiException}.
     * <p>
     * Used by subtypes whose timing data originates from Apache's
     * {@link HttpContext} rather than feign-style header injection -
     * the round-trip markers consumed by the standard lazy path are absent in that source, so
     * the prebuilt snapshot preserves real round-trip / DNS / TCP / TLS timings.
     *
     * @param context the HTTP context bundle
     * @param details a pre-built network timing snapshot to expose via {@link #getDetails()}
     * @param message the detail message describing the failure
     */
    public UrlFetchException(@NotNull ErrorContext context, @NotNull NetworkDetails details, @NotNull String message) {
        super(null, NAME, context, details, message, true);
    }

    /**
     * Constructs a new {@code UrlFetchException} with a cause, a pre-built {@link NetworkDetails}
     * and a message, bypassing the header-map lazy build inside {@link ApiException}.
     *
     * @param cause the underlying transport or decode failure
     * @param context the HTTP context bundle
     * @param details a pre-built network timing snapshot to expose via {@link #getDetails()}
     * @param message the detail message describing the failure
     */
    public UrlFetchException(
        @NotNull Throwable cause,
        @NotNull ErrorContext context,
        @NotNull NetworkDetails details,
        @NotNull String message
    ) {
        super(cause, NAME, context, details, message, true);
    }

    /**
     * Constructs a new {@code UrlFetchException} with a pre-built {@link NetworkDetails} and a
     * formatted message, bypassing the header-map lazy build inside {@link ApiException}.
     *
     * @param context the HTTP context bundle
     * @param details a pre-built network timing snapshot to expose via {@link #getDetails()}
     * @param message the format string of the detail message
     * @param args the format arguments for {@code message}
     */
    public UrlFetchException(
        @NotNull ErrorContext context,
        @NotNull NetworkDetails details,
        @NotNull @PrintFormat String message,
        @Nullable Object... args
    ) {
        super(null, NAME, context, details, String.format(message, args), true);
    }

    /**
     * Constructs a new {@code UrlFetchException} with a cause, a pre-built {@link NetworkDetails}
     * and a formatted message, bypassing the header-map lazy build inside {@link ApiException}.
     *
     * @param cause the underlying transport or decode failure
     * @param context the HTTP context bundle
     * @param details a pre-built network timing snapshot to expose via {@link #getDetails()}
     * @param message the format string of the detail message
     * @param args the format arguments for {@code message}
     */
    public UrlFetchException(
        @NotNull Throwable cause,
        @NotNull ErrorContext context,
        @NotNull NetworkDetails details,
        @NotNull @PrintFormat String message,
        @Nullable Object... args
    ) {
        super(cause, NAME, context, details, String.format(message, args), true);
    }

    /**
     * Returns the URL that was being fetched when the failure occurred.
     * <p>
     * Convenience accessor that parses {@link #getRequest()}'s URL string back into a
     * {@link URI}; callers that already have the {@code String} form can read it directly
     * via {@code getRequest().getUrl()}.
     *
     * @return the originating URL
     */
    public @NotNull URI getUrl() {
        return URI.create(this.getRequest().getUrl());
    }

    /**
     * Builds an {@link ErrorContext} carrying an empty body and empty request headers for
     * pre-response failures - rate-limit rejections, transport faults - where no exchange
     * actually completed.
     * <p>
     * Subtypes whose timing data comes from a non-feign source (e.g. Apache's
     * {@link HttpContext}) feed that {@link NetworkDetails} into the
     * prebuilt-details {@link UrlFetchException} constructor instead of threading it through
     * this helper. The empty request-headers map ensures the lazy {@link NetworkDetails} build
     * in the standard path produces the same result as {@link NetworkDetails#EMPTY}.
     *
     * @param status the synthetic status to expose
     * @param url the URL that was being fetched
     * @param responseHeaders the headers received before the failure, or
     *                        {@link Collections#emptyMap()} when none were received
     * @return a primitive context bundle ready to feed the {@link UrlFetchException} constructor
     */
    static @NotNull ErrorContext syntheticContext(
        @NotNull HttpStatus status,
        @NotNull URI url,
        @NotNull Map<String, Collection<String>> responseHeaders
    ) {
        return new ErrorContext(
            status,
            HttpMethod.GET,
            url.toString(),
            responseHeaders,
            Collections.emptyMap(),
            new byte[0]
        );
    }

    /**
     * Builds the exception a fetch raises when the origin answers with a status outside the
     * {@code 2xx} class - a {@link Redirection} for a {@code 3xx} code, a {@link ClientError} for
     * a status {@link HttpState#CLIENT_ERROR} classifies, a {@code UrlFetchException} for any
     * other.
     * <p>
     * A context carrying a code {@link HttpStatus} has no constant for raises as
     * {@link #ofUnknownStatus(int, URI, Map, byte[], NetworkDetails)} raises it: a
     * {@link Redirection} for a {@code 3xx} code, a {@link ClientError} for a {@code 4xx} code
     * outside the Nginx range {@code 494-499}, a {@code UrlFetchException} for any other.
     *
     * @param context the HTTP context bundle carrying the status, headers, body, and request
     *                metadata
     * @param details the network timing snapshot of the exchange
     * @return the exception to raise
     */
    public static @NotNull UrlFetchException ofStatus(@NotNull ErrorContext context, @NotNull NetworkDetails details) {
        if (HttpState.REDIRECTION.containsCode(context.statusCode()))
            return new Redirection(context, details);

        if (isClientError(context))
            return new ClientError(context, details);

        return new UrlFetchException(context, details, statusMessage(context));
    }

    /**
     * Builds the exception for a status code {@link HttpStatus} has no constant for - a
     * {@link Redirection} for a {@code 3xx} code, a {@link ClientError} for a {@code 4xx} code
     * outside the range {@link HttpState#NGINX_ERROR} holds, a {@code UrlFetchException} for any
     * other, so a code in the Nginx range raises as the Nginx codes {@link HttpStatus} names do.
     * A fetch raises it for every such code outside the {@code 2xx} class; a {@code 2xx} code it
     * reads as {@code 200}.
     * <p>
     * Each carries the code as its {@link #getStatusCode()} and
     * {@link HttpStatus#UNKNOWN_ERROR} as its {@link #getStatus()}.
     *
     * @param statusCode the status code the origin answered with
     * @param url the URL the origin answered
     * @param responseHeaders the response headers
     * @param body the response body
     * @param details the network timing snapshot of the exchange
     * @return the exception to raise
     * @throws IllegalArgumentException if {@link HttpStatus} has a constant for
     *         {@code statusCode}, whose exception {@link #ofStatus(ErrorContext, NetworkDetails)}
     *         builds
     */
    public static @NotNull UrlFetchException ofUnknownStatus(
        int statusCode,
        @NotNull URI url,
        @NotNull Map<String, Collection<String>> responseHeaders,
        byte @NotNull [] body,
        @NotNull NetworkDetails details
    ) {
        if (HttpStatus.findByCode(statusCode).isPresent())
            throw new IllegalArgumentException(String.format("HttpStatus has a constant for status code '%s'", statusCode));

        ErrorContext context = new ErrorContext(
            HttpStatus.UNKNOWN_ERROR,
            statusCode,
            HttpMethod.GET,
            url.toString(),
            responseHeaders,
            Collections.emptyMap(),
            body
        );

        return ofStatus(context, details);
    }

    /**
     * Tells whether the status a context carries raises a {@link ClientError}: a status
     * {@link HttpState#CLIENT_ERROR} classifies, or a {@code 4xx} code {@link HttpStatus} has no
     * constant for outside the range {@link HttpState#NGINX_ERROR} holds.
     *
     * @param context the HTTP context bundle carrying the status the origin answered with
     * @return {@code true} when the status raises a {@link ClientError}
     */
    private static boolean isClientError(@NotNull ErrorContext context) {
        if (context.isKnownStatus())
            return context.status().getState() == HttpState.CLIENT_ERROR;

        int statusCode = context.statusCode();
        return statusCode >= CLIENT_ERROR_MIN && statusCode <= CLIENT_ERROR_MAX && !HttpState.NGINX_ERROR.containsCode(statusCode);
    }

    /**
     * Formats the message of an exception raised for the status an origin answered with,
     * naming the code, the URL and, for a code {@link HttpStatus} has a constant for, the
     * constant's message.
     *
     * @param context the HTTP context bundle carrying the status the origin answered with
     * @return the formatted message
     */
    private static @NotNull String statusMessage(@NotNull ErrorContext context) {
        if (!context.isKnownStatus())
            return String.format(UNKNOWN_STATUS_MESSAGE, context.statusCode(), context.requestUrl());

        return String.format(STATUS_MESSAGE, context.statusCode(), context.status().getMessage(), context.requestUrl());
    }

    /**
     * Thrown when the origin answers a fetch with a {@code 3xx} code the fetch neither follows
     * nor answers from the response cache: a {@code 300}, {@code 305} or {@code 306}, a redirect
     * the transport does not follow - one without a {@code Location}, or one to another host or
     * port from a fetch carrying {@code Authorization} or {@code Cookie} - a
     * {@code 304 Not Modified} that answers no revalidation of a cached entry, or a {@code 3xx}
     * code {@link HttpStatus} has no constant for.
     * <p>
     * {@link #getStatusCode()} is the code the origin sent and {@link #getStatus()} its
     * constant, {@link HttpStatus#UNKNOWN_ERROR} for a code without one. {@link #getBody()} is
     * the body the origin sent with it, cut at the fetch's body cap, and {@link #getHeaders()} its
     * headers, a redirect's {@code Location} among them. Nothing is stored for it, and a fetch
     * answered from the response cache with a {@code 3xx} status raises it as well, with the
     * cached headers.
     */
    public static final class Redirection extends UrlFetchException {

        /**
         * Constructs a new {@code Redirection} with the context and network details of the
         * exchange the origin answered.
         *
         * @param context the HTTP context bundle carrying the {@code 3xx} status, or a
         *                {@code 3xx} code {@link HttpStatus} has no constant for, and the
         *                headers, body, and request metadata
         * @param details the network timing snapshot of the exchange
         */
        public Redirection(@NotNull ErrorContext context, @NotNull NetworkDetails details) {
            super(context, details, statusMessage(context));
        }

    }

    /**
     * Thrown when the origin answers a fetch with a status {@link HttpState#CLIENT_ERROR}
     * classifies, {@code 400} to {@code 451}, or with a {@code 4xx} code {@link HttpStatus} has
     * no constant for, outside the Nginx range {@code 494-499}.
     * <p>
     * {@link #getStatusCode()} is the code the origin sent and {@link #getStatus()} its
     * constant, {@link HttpStatus#UNKNOWN_ERROR} for a code without one. {@link #getBody()} is
     * the body the origin sent with it, cut at the fetch's body cap, and {@link #getHeaders()} its
     * headers. A fetch answered from the response cache with such a status raises it as well,
     * with the cached headers.
     */
    public static final class ClientError extends UrlFetchException {

        /**
         * Constructs a new {@code ClientError} with the context and network details of the
         * exchange the origin answered.
         *
         * @param context the HTTP context bundle carrying the client error status, or a
         *                {@code 4xx} code {@link HttpStatus} has no constant for, and the
         *                headers, body, and request metadata
         * @param details the network timing snapshot of the exchange
         */
        public ClientError(@NotNull ErrorContext context, @NotNull NetworkDetails details) {
            super(context, details, statusMessage(context));
        }

    }

    /**
     * Thrown when the local {@link RateLimitManager RateLimitManager}
     * rejects a fetch because the configured request budget for the resolved bucket has been
     * exhausted.
     */
    @Getter
    public static final class RateLimited extends UrlFetchException {

        /**
         * The identifier of the rate-limit bucket that was exceeded.
         */
        private final @NotNull String bucketId;

        /**
         * The {@link RateLimit} policy associated with the exceeded bucket.
         */
        private final @NotNull RateLimit rateLimit;

        /**
         * Constructs a new {@code RateLimited} for the given URL and bucket.
         *
         * @param url the URL that was being fetched
         * @param bucketId the identifier of the rate-limit bucket
         * @param rateLimit the policy that was exceeded
         */
        public RateLimited(@NotNull URI url, @NotNull String bucketId, @NotNull RateLimit rateLimit) {
            super(
                syntheticContext(HttpStatus.TOO_MANY_REQUESTS, url, Collections.emptyMap()),
                "Rate limit exceeded for bucket '%s' on URL '%s'",
                bucketId,
                url
            );
            this.bucketId = bucketId;
            this.rateLimit = rateLimit;
        }

    }

    /**
     * Thrown when the body of a {@code 2xx} response is larger than the fetch's size cap - one
     * read off the wire, whose exchange is aborted once the body passes the cap, its connection
     * closed rather than drained, so the rest of the body is never downloaded, or one the
     * response cache would replay. A response with any other status raises the exception for its
     * status instead, carrying its body cut at the cap, and its exchange is aborted the same way.
     */
    @Getter
    public static final class BodyCapExceeded extends UrlFetchException {

        /**
         * The maximum body size in bytes the fetch was held to.
         */
        private final long maxBytes;

        /**
         * Constructs a new {@code BodyCapExceeded} for the given URL and cap.
         *
         * @param url the URL that was being fetched
         * @param details the network timing snapshot at the moment the cap was hit
         * @param responseHeaders the response headers received before the cap was hit, or the
         *                        headers of the cache replay the cap refused
         * @param maxBytes the maximum body size in bytes the fetch was held to
         */
        public BodyCapExceeded(
            @NotNull URI url,
            @NotNull NetworkDetails details,
            @NotNull Map<String, Collection<String>> responseHeaders,
            long maxBytes
        ) {
            super(
                syntheticContext(HttpStatus.IO_ERROR, url, responseHeaders),
                details,
                "Response body exceeded cap of %d bytes for URL '%s'",
                maxBytes,
                url
            );
            this.maxBytes = maxBytes;
        }

    }

    /**
     * Thrown when an underlying I/O failure prevents a fetch from completing - DNS failure,
     * connection refused, socket timeout, malformed response, etc.
     */
    public static final class Transport extends UrlFetchException {

        /**
         * Constructs a new {@code Transport} wrapping the given I/O cause.
         *
         * @param cause the underlying I/O failure
         * @param url the URL that was being fetched
         * @param details the network timing snapshot at the moment of failure
         */
        public Transport(@NotNull Throwable cause, @NotNull URI url, @NotNull NetworkDetails details) {
            super(
                cause,
                syntheticContext(HttpStatus.IO_ERROR, url, Collections.emptyMap()),
                details,
                "Transport failure fetching URL '%s'",
                url
            );
        }

    }

}
