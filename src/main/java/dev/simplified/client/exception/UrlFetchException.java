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
 * failed, or the origin answered with an {@linkplain HttpState#isError() error} status.
 * <p>
 * Each cause has its own type, so a caller tells them apart by the type it catches:
 * <ul>
 *   <li>{@link ClientError} - the origin answered with a status {@link HttpState#CLIENT_ERROR}
 *       classifies, {@code 400} to {@code 451}, or with a code from {@code 400} to {@code 499}
 *       that {@link HttpStatus} has no constant for</li>
 *   <li>{@code UrlFetchException} itself - the origin answered with any other error status: a
 *       {@code 5xx}, or a vendor-specific code such as Nginx's {@code 444} and
 *       {@code 494-499}; or with a code outside {@code 400} to {@code 499} that
 *       {@link HttpStatus} has no constant for, whatever its class</li>
 *   <li>{@link Transport} - no response arrived</li>
 *   <li>{@link BodyCapExceeded} - the body of a response that is not an error was larger than
 *       the fetch's cap; an error status, or a code {@link HttpStatus} has no constant for,
 *       raises its own type whatever the size of its body</li>
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
     * The lowest code without an {@link HttpStatus} constant that raises a {@link ClientError}.
     */
    private static final int CLIENT_ERROR_MIN = 400;

    /**
     * The highest code without an {@link HttpStatus} constant that raises a {@link ClientError}.
     */
    private static final int CLIENT_ERROR_MAX = 499;

    /**
     * The numeric status - the code the origin answered with, including one {@link HttpStatus}
     * has no constant for, or the code of the synthetic status a failure without an origin
     * status carries.
     */
    @Getter
    private final int statusCode;

    /**
     * Constructs a new {@code UrlFetchException} with the given context and pre-formatted message.
     *
     * @param context the HTTP context bundle carrying status, headers, body, and request metadata
     * @param message the detail message describing the failure
     * @param args optional format arguments for {@code message}
     */
    public UrlFetchException(@NotNull ErrorContext context, @NotNull @PrintFormat String message, @Nullable Object... args) {
        this(null, context, message, args);
    }

    /**
     * Constructs a new {@code UrlFetchException} with the given cause, context, and pre-formatted message.
     *
     * @param cause the underlying transport or decode failure, or {@code null} if none
     * @param context the HTTP context bundle carrying status, headers, body, and request metadata
     * @param message the detail message describing the failure
     * @param args optional format arguments for {@code message}
     */
    public UrlFetchException(
        @Nullable Throwable cause,
        @NotNull ErrorContext context,
        @NotNull @PrintFormat String message,
        @Nullable Object... args
    ) {
        super(cause, NAME, context, args.length == 0 ? message : String.format(message, args), true);
        this.statusCode = context.status().getCode();
    }

    /**
     * Constructs a new {@code UrlFetchException} with a pre-built {@link NetworkDetails},
     * bypassing the header-map lazy build inside {@link ApiException}.
     * <p>
     * Used by subtypes whose timing data originates from Apache's
     * {@link HttpContext} rather than feign-style header injection -
     * the {@code X-Internal-*} markers consumed by the standard lazy path are absent in that
     * source, so the prebuilt snapshot preserves real round-trip / DNS / TCP / TLS timings.
     *
     * @param context the HTTP context bundle
     * @param details a pre-built network timing snapshot to expose via {@link #getDetails()}
     * @param message the detail message describing the failure
     * @param args optional format arguments for {@code message}
     */
    public UrlFetchException(
        @NotNull ErrorContext context,
        @NotNull NetworkDetails details,
        @NotNull @PrintFormat String message,
        @Nullable Object... args
    ) {
        this(null, context, details, message, args);
    }

    /**
     * Constructs a new {@code UrlFetchException} with a cause and a pre-built
     * {@link NetworkDetails}, bypassing the header-map lazy build inside {@link ApiException}.
     *
     * @param cause the underlying transport or decode failure, or {@code null} if none
     * @param context the HTTP context bundle
     * @param details a pre-built network timing snapshot to expose via {@link #getDetails()}
     * @param message the detail message describing the failure
     * @param args optional format arguments for {@code message}
     */
    public UrlFetchException(
        @Nullable Throwable cause,
        @NotNull ErrorContext context,
        @NotNull NetworkDetails details,
        @NotNull @PrintFormat String message,
        @Nullable Object... args
    ) {
        super(cause, NAME, context, details, args.length == 0 ? message : String.format(message, args), true);
        this.statusCode = context.status().getCode();
    }

    /**
     * Constructs a new {@code UrlFetchException} for a status code {@link HttpStatus} has no
     * constant for.
     *
     * @param statusCode the status code the origin answered with
     * @param context the HTTP context bundle, carrying {@link HttpStatus#UNKNOWN_ERROR} in place
     *                of the code
     * @param details the network timing snapshot of the exchange
     */
    private UrlFetchException(int statusCode, @NotNull ErrorContext context, @NotNull NetworkDetails details) {
        super(null, NAME, context, details, String.format(UNKNOWN_STATUS_MESSAGE, statusCode, context.requestUrl()), true);
        this.statusCode = statusCode;
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
     * in the standard path produces the same result as {@link NetworkDetails#empty()}.
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
     * Builds the exception a fetch raises when the origin answers with an error status - a
     * {@link ClientError} for a status {@link HttpState#CLIENT_ERROR} classifies, a
     * {@code UrlFetchException} for any other.
     *
     * @param context the HTTP context bundle carrying the error status, headers, body, and
     *                request metadata
     * @param details the network timing snapshot of the exchange
     * @return the exception to raise
     */
    public static @NotNull UrlFetchException ofStatus(@NotNull ErrorContext context, @NotNull NetworkDetails details) {
        if (context.status().getState() == HttpState.CLIENT_ERROR)
            return new ClientError(context, details);

        return new UrlFetchException(
            context,
            details,
            STATUS_MESSAGE,
            context.status().getCode(),
            context.status().getMessage(),
            context.requestUrl()
        );
    }

    /**
     * Builds the exception a fetch raises when the origin answers with a status code
     * {@link HttpStatus} has no constant for - a {@link ClientError} for a code from {@code 400}
     * to {@code 499}, a {@code UrlFetchException} for any other, whatever its class.
     * <p>
     * Either carries the code as its {@link #getStatusCode()} and
     * {@link HttpStatus#UNKNOWN_ERROR} as its {@link #getStatus()}.
     *
     * @param statusCode the status code the origin answered with
     * @param url the URL the origin answered
     * @param responseHeaders the response headers
     * @param body the response body
     * @param details the network timing snapshot of the exchange
     * @return the exception to raise
     */
    public static @NotNull UrlFetchException ofUnknownStatus(
        int statusCode,
        @NotNull URI url,
        @NotNull Map<String, Collection<String>> responseHeaders,
        byte @NotNull [] body,
        @NotNull NetworkDetails details
    ) {
        ErrorContext context = new ErrorContext(
            HttpStatus.UNKNOWN_ERROR,
            HttpMethod.GET,
            url.toString(),
            responseHeaders,
            Collections.emptyMap(),
            body
        );

        if (statusCode >= CLIENT_ERROR_MIN && statusCode <= CLIENT_ERROR_MAX)
            return new ClientError(statusCode, context, details);

        return new UrlFetchException(statusCode, context, details);
    }

    /**
     * Thrown when the origin answers a fetch with a status {@link HttpState#CLIENT_ERROR}
     * classifies, {@code 400} to {@code 451}, or with a code from {@code 400} to {@code 499}
     * that {@link HttpStatus} has no constant for.
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
         * @param context the HTTP context bundle carrying the client error status, headers, body,
         *                and request metadata
         * @param details the network timing snapshot of the exchange
         */
        public ClientError(@NotNull ErrorContext context, @NotNull NetworkDetails details) {
            super(
                context,
                details,
                STATUS_MESSAGE,
                context.status().getCode(),
                context.status().getMessage(),
                context.requestUrl()
            );
        }

        /**
         * Constructs a new {@code ClientError} for a status code {@link HttpStatus} has no
         * constant for.
         *
         * @param statusCode the status code the origin answered with
         * @param context the HTTP context bundle, carrying {@link HttpStatus#UNKNOWN_ERROR} in
         *                place of the code
         * @param details the network timing snapshot of the exchange
         */
        private ClientError(int statusCode, @NotNull ErrorContext context, @NotNull NetworkDetails details) {
            super(statusCode, context, details);
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
     * Thrown when a response body is larger than the fetch's size cap - one read off the wire,
     * which stops reading at the cap, or one the response cache would replay. A response with an
     * error status raises the exception for its status instead, carrying its body cut at the
     * cap.
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
