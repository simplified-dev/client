package dev.simplified.client.exception;

import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.route.RouteDiscovery;
import feign.FeignException;
import feign.RequestTemplate;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown when an HTTP request is rejected due to rate-limit enforcement.
 * <p>
 * Two distinct enforcement modes are represented:
 * <ul>
 *   <li><b>Server-enforced (reactive)</b> - The remote server returned an
 *       HTTP {@code 429 Too Many Requests} response.  The exception is
 *       constructed from the actual {@link feign.Response} via
 *       {@link #RateLimitException(String, feign.Response, RouteDiscovery.Metadata)}.</li>
 *   <li><b>Client-enforced (proactive)</b> - The local
 *       {@link RateLimitManager} detected that
 *       the request would exceed the configured quota and blocked it before
 *       it reached the network.  A synthetic {@code 429} response is
 *       fabricated from the {@link RequestTemplate} via
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
     * Constructs a server-enforced rate-limit exception from an actual HTTP
     * {@code 429} response.
     * <p>
     * This constructor is invoked by the error decoder when the remote server
     * explicitly rejects a request with a {@code 429 Too Many Requests} status.
     *
     * @param methodKey the Feign method key identifying the endpoint that was rate-limited
     * @param response the raw Feign HTTP response containing the {@code 429} status
     * @param routeMetadata the route metadata providing the bucket identifier and rate-limit policy
     */
    public RateLimitException(@NotNull String methodKey, @NotNull feign.Response response, @NotNull RouteDiscovery.Metadata routeMetadata) {
        super(FeignException.errorStatus(methodKey, response), response, "RateLimit", false);
        this.serverEnforced = true;
        this.bucketId = routeMetadata.getRoute();
        this.rateLimit = routeMetadata.getRateLimit();
    }

    /**
     * Constructs a client-enforced rate-limit exception from a request that was
     * blocked before being sent.
     * <p>
     * This constructor is invoked by the request interceptor when the local
     * {@link RateLimitManager} determines that
     * sending the request would exceed the configured quota.  A synthetic
     * {@link feign.Response} with HTTP status {@link HttpStatus#TOO_MANY_REQUESTS}
     * is fabricated so that the exception carries the same structure as a
     * server-enforced one.
     *
     * @param template the Feign request template that was blocked
     * @param routeMetadata the route metadata providing the bucket identifier and rate-limit policy
     */
    public RateLimitException(@NotNull RequestTemplate template, @NotNull RouteDiscovery.Metadata routeMetadata) {
        this(template.methodMetadata().method().getName(), syntheticResponse(template), routeMetadata, false);
    }

    /**
     * Private delegating constructor that ensures the synthetic response is built
     * once and reused across both the {@link FeignException} factory and the
     * superclass call.
     *
     * @param methodKey the resolved Feign method key for the blocked request
     * @param response the synthetic {@code 429} response
     * @param routeMetadata the route metadata providing the bucket identifier and rate-limit policy
     * @param serverEnforced whether the rate limit was enforced by the remote server
     */
    private RateLimitException(@NotNull String methodKey, @NotNull feign.Response response, @NotNull RouteDiscovery.Metadata routeMetadata, boolean serverEnforced) {
        super(FeignException.errorStatus(methodKey, response), response, "RateLimit", false);
        this.serverEnforced = serverEnforced;
        this.bucketId = routeMetadata.getRoute();
        this.rateLimit = routeMetadata.getRateLimit();
    }

    /**
     * Builds the synthetic {@code 429 Too Many Requests} response used by the
     * client-enforced constructor.
     *
     * @param template the request template to fabricate a response for
     * @return a synthetic feign response carrying the {@code 429} status
     */
    private static @NotNull feign.Response syntheticResponse(@NotNull RequestTemplate template) {
        // Buffered empty body so the lazy ApiException.body / getRawBody readers see a
        // byte[]-backed body rather than a live stream.
        return feign.Response.builder()
            .status(HttpStatus.TOO_MANY_REQUESTS.getCode())
            .reason(HttpStatus.TOO_MANY_REQUESTS.getMessage())
            .headers(template.headers())
            .request(template.request())
            .protocolVersion(template.request().protocolVersion())
            .body(new byte[0])
            .build();
    }

}
