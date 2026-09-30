package dev.simplified.client.exception;

import dev.simplified.annotations.Getter;
import dev.simplified.client.Client;
import dev.simplified.client.Proxy;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.ratelimit.RateLimitingFeignClient;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.route.RouteDiscovery;
import dev.simplified.client.subnet.pool.SubnetBucket;
import feign.Request;
import feign.RequestTemplate;
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
 *       from the blocked {@link Request} via
 *       {@link #RateLimitException(Request, RouteDiscovery.Metadata)}.</li>
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

    /**
     * Whether the rate limit was enforced by the remote server ({@code true}) or locally by the client ({@code false}).
     */
    private final boolean serverEnforced;

    /**
     * The identifier of the rate-limit bucket that was exceeded (typically the resolved route string).
     */
    private final @NotNull String bucketId;

    /**
     * The {@link RateLimit} policy associated with the exceeded bucket.
     */
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
     * Constructs a client-enforced rate-limit exception from a request template whose request
     * was blocked before being sent.
     * <p>
     * Builds the exception {@link #RateLimitException(Request, RouteDiscovery.Metadata)} builds
     * for the request the template produces.
     *
     * @param template the Feign request template that was blocked
     * @param routeMetadata the route metadata providing the bucket identifier and rate-limit policy
     */
    public RateLimitException(@NotNull RequestTemplate template, @NotNull RouteDiscovery.Metadata routeMetadata) {
        this(template.request(), routeMetadata);
    }

    /**
     * Constructs a client-enforced rate-limit exception from a request that was blocked
     * before being sent.
     * <p>
     * This constructor is invoked by {@link RateLimitingFeignClient} when the local
     * {@link RateLimitManager} determines that sending the request would exceed the
     * configured quota. A synthetic {@link ErrorContext} carrying
     * {@link HttpStatus#TOO_MANY_REQUESTS}, empty {@link NetworkDetails}, and the request's
     * method and url is built so the exception carries the same shape as a server-enforced one
     * without fabricating an intermediate {@link feign.Response}.
     *
     * @param request the Feign request that was blocked
     * @param routeMetadata the route metadata providing the bucket identifier and rate-limit policy
     */
    public RateLimitException(@NotNull Request request, @NotNull RouteDiscovery.Metadata routeMetadata) {
        super(
            null,
            "RateLimit",
            new ErrorContext(
                HttpStatus.TOO_MANY_REQUESTS,
                HttpMethod.of(request.httpMethod().name()),
                request.url(),
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

    /**
     * Constructs a client-enforced rate-limit exception for a {@link Proxy} whose pool refused a
     * client: the availability predicate rejects the one client of a proxy without a rotation;
     * every subnet {@link SubnetBucket} of a rotating proxy is saturated; or the one bucket a
     * rotating proxy chose was spent between the pool's saturation check and its selection.
     * <p>
     * Used at the proxy layer before any HTTP request is constructed, so the
     * synthetic context carries the bucket identifier as its url placeholder
     * and {@link HttpMethod#GET} as a neutral request method stand-in.
     * <p>
     * When a rotating proxy spreads its clients over several buckets, a refusal of the one bucket
     * it chose names that bucket's subnet rather than the source prefix. Other buckets may still
     * have budget, so a retry may be served by another bucket without waiting on this
     * exception's policy.
     * <p>
     * The policy is the one the refused client's {@link RateLimitManager} holds for the bucket it
     * spent, as {@link Client#findRateLimitedPolicy()} finds it: the policy a server's headers
     * last gave that bucket, reset instant included, or the one its route declares while no
     * server has named one. When the client reports no exhausted bucket and the availability
     * predicate refused it for another reason, the policy is the one {@link Client#getRateLimit()}
     * reads for the contract's type-level route, or the one that route declares while the client
     * holds no bucket for it. A caller backing off on this exception's
     * {@linkplain #rateLimit policy} waits on that bucket's window.
     *
     * @param bucketId the identifier of the refused bucket - a subnet CIDR string for a rotating
     *     proxy, or the bare type-level route for a proxy without a rotation
     * @param rateLimit the policy the refused client holds for the bucket it spent, or for the
     *     contract's type-level route when it reports none spent
     */
    public RateLimitException(@NotNull String bucketId, @NotNull RateLimit rateLimit) {
        super(
            null,
            "RateLimit",
            new ErrorContext(
                HttpStatus.TOO_MANY_REQUESTS,
                HttpMethod.GET,
                bucketId,
                Collections.emptyMap(),
                Collections.emptyMap(),
                new byte[0]
            ),
            false
        );
        this.serverEnforced = false;
        this.bucketId = bucketId;
        this.rateLimit = rateLimit;
    }

}
