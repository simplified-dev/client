package dev.simplified.client.exception;

import dev.simplified.client.fetch.UrlFetcher;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.NetworkDetails;
import lombok.Getter;
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

    /** The short name identifying URL-fetch errors in logs and error tracking. */
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
     * Builds an {@link ErrorContext} carrying an empty body and empty response headers for
     * pre-response failures - rate-limit rejections, transport faults - where no exchange
     * actually completed.
     *
     * @param status the synthetic status to expose
     * @param details the timing snapshot to expose ({@link NetworkDetails#empty()} for
     *                pre-network failures)
     * @param url the URL that was being fetched
     * @param responseHeaders the headers received before the failure, or
     *                        {@link Collections#emptyMap()} when none were received
     * @return a primitive context bundle ready to feed the {@link UrlFetchException} constructor
     */
    static @NotNull ErrorContext syntheticContext(
        @NotNull HttpStatus status,
        @NotNull NetworkDetails details,
        @NotNull URI url,
        @NotNull Map<String, Collection<String>> responseHeaders
    ) {
        return new ErrorContext(
            status,
            details,
            HttpMethod.GET,
            url.toString(),
            responseHeaders,
            new byte[0]
        );
    }

    /**
     * Thrown when the local {@link dev.simplified.client.ratelimit.RateLimitManager RateLimitManager}
     * rejects a fetch because the configured request budget for the resolved bucket has been
     * exhausted.
     */
    @Getter
    public static final class RateLimited extends UrlFetchException {

        /** The identifier of the rate-limit bucket that was exceeded. */
        private final @NotNull String bucketId;

        /** The {@link RateLimit} policy associated with the exceeded bucket. */
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
                syntheticContext(HttpStatus.TOO_MANY_REQUESTS, NetworkDetails.empty(), url, Collections.emptyMap()),
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

        /** The configured maximum body size in bytes. */
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
                syntheticContext(HttpStatus.IO_ERROR, details, url, responseHeaders),
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
                syntheticContext(HttpStatus.IO_ERROR, details, url, Collections.emptyMap()),
                "Transport failure fetching URL '%s'",
                url
            );
        }

    }

}
