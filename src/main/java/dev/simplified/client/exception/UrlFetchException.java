package dev.simplified.client.exception;

import dev.simplified.client.fetch.UrlFetcher;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.NetworkDetails;
import lombok.Getter;
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
 * budget rejected the request, the response body exceeded the configured cap, or the origin
 * returned a non-success status.
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
     * Thrown when a fetched response body would exceed the configured size cap before the
     * stream was fully drained.
     */
    @Getter
    public static final class BodyCapExceeded extends UrlFetchException {

        /**
         * The configured maximum body size in bytes.
         */
        private final long maxBytes;

        /**
         * Constructs a new {@code BodyCapExceeded} for the given URL and cap.
         *
         * @param url the URL that was being fetched
         * @param details the network timing snapshot at the moment the cap was hit
         * @param responseHeaders the raw response headers received before the cap was hit
         * @param maxBytes the configured maximum body size in bytes
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
