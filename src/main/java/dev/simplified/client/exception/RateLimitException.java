package dev.simplified.client.exception;

import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.route.RouteDiscovery;
import feign.RequestTemplate;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;

/**
 * Thrown when an HTTP request is rejected due to rate-limit enforcement.
 * <p>
 * Two distinct enforcement modes are represented:
 * <ul>
 *   <li><b>Server-enforced (reactive)</b> - The remote server returned an
 *       HTTP {@code 429 Too Many Requests} response. The exception is
 *       constructed from the {@link ErrorContext} bundle prepared by the
 *       error decoder via {@link #RateLimitException(ErrorContext, RouteDiscovery.Metadata)}.</li>
 *   <li><b>Client-enforced (proactive)</b> - The local
 *       {@link RateLimitManager} detected that
 *       the request would exceed the configured quota and blocked it before
 *       it reached the network. A synthetic primitive context is built directly
 *       from the {@link RequestTemplate} via
 *       {@link #RateLimitException(RequestTemplate, RouteDiscovery.Metadata)}.</li>
 * </ul>
 * <p>
 * The {@link #serverEnforced} flag distinguishes between these two cases,
 * enabling callers and the retry pipeline to apply different back-off
 * strategies as appropriate.
 *
 * @see ApiException
 * @see RetryableApiException
 * @see RateLimitManager
 */
@Getter
public final class RateLimitException extends ApiException {

    /** Whether the rate limit was enforced by the remote server ({@code true}) or locally by the client ({@code false}). */
    private final boolean serverEnforced;

    /** The identifier of the rate-limit bucket that was exceeded (typically the resolved route string). */
    private final @NotNull String bucketId;

    /** The {@link RateLimit} policy associated with the exceeded bucket. */
    private final @NotNull RateLimit rateLimit;

    /**
     * Constructs a server-enforced rate-limit exception from a primitive HTTP context built
     * around an actual {@code 429} response.
     * <p>
     * This constructor is invoked by the error decoder when the remote server explicitly
     * rejects a request with a {@code 429 Too Many Requests} status.
     *
     * @param context the primitive HTTP context carrying the {@code 429} status, headers, and request metadata
     * @param routeMetadata the route metadata providing the bucket identifier and rate-limit policy
     */
    public RateLimitException(@NotNull ErrorContext context, @NotNull RouteDiscovery.Metadata routeMetadata) {
        super(null, "RateLimit", context, false);
        this.serverEnforced = true;
        this.bucketId = routeMetadata.getRoute();
        this.rateLimit = routeMetadata.getRateLimit();
    }

    /**
     * Constructs a client-enforced rate-limit exception from a request that was blocked
     * before being sent.
     * <p>
     * This constructor is invoked by the request interceptor when the local
     * {@link RateLimitManager} determines that sending the request would exceed the
     * configured quota. A synthetic {@link ErrorContext} carrying
     * {@link HttpStatus#TOO_MANY_REQUESTS}, empty {@link NetworkDetails}, and the request
     * template's method and url is built so the exception carries the same shape as a
     * server-enforced one without fabricating an intermediate {@link feign.Response}.
     *
     * @param template the Feign request template that was blocked
     * @param routeMetadata the route metadata providing the bucket identifier and rate-limit policy
     */
    public RateLimitException(@NotNull RequestTemplate template, @NotNull RouteDiscovery.Metadata routeMetadata) {
        super(
            null,
            "RateLimit",
            new ErrorContext(
                HttpStatus.TOO_MANY_REQUESTS,
                HttpMethod.of(template.request().httpMethod().name()),
                template.request().url(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                new byte[0]
            ),
            false
        );
        this.serverEnforced = false;
        this.bucketId = routeMetadata.getRoute();
        this.rateLimit = routeMetadata.getRateLimit();
    }

}
