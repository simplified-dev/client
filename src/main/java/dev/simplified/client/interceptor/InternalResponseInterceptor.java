package dev.simplified.client.interceptor;

import dev.simplified.client.exception.NotModifiedException;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.request.Request;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import dev.simplified.client.route.RouteDiscovery;
import dev.simplified.collection.ConcurrentList;
import feign.InvocationContext;
import feign.ResponseInterceptor;
import feign.Util;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Optional;

/**
 * Feign {@link ResponseInterceptor} that extracts server-advertised rate limit headers from
 * every HTTP response and feeds them back into the {@link RateLimitManager}.
 * <p>
 * When a response is received, this interceptor:
 * <ol>
 *   <li>Resolves the originating route by performing a longest-prefix URL match via
 *       {@link RouteDiscovery#findMatchingMetadata(String)}.</li>
 *   <li>Parses standard and common rate limit headers (e.g. {@code RateLimit-Limit},
 *       {@code X-RateLimit-Reset}) from the response using
 *       {@link RateLimit#fromHeaders(java.util.Map)}.</li>
 *   <li>Updates the corresponding bucket in the {@link RateLimitManager} with the
 *       server-supplied limits, ensuring that subsequent client-side checks reflect the
 *       most recent server policy.</li>
 *   <li>Delegates to the next interceptor in the chain.</li>
 * </ol>
 * <p>
 * This class is the server-side complement to {@link InternalRequestInterceptor}, which
 * enforces the client-side rate limit before each request. Together they form a closed
 * feedback loop that keeps client-tracked quotas synchronized with server-advertised quotas.
 * <p>
 * This class is instantiated internally by {@link dev.simplified.client.Client} during Feign
 * builder configuration and is not intended for direct use by application code.
 *
 * @see InternalRequestInterceptor
 * @see RateLimitManager
 * @see RouteDiscovery
 * @see RateLimit#fromHeaders(java.util.Map)
 */
@RequiredArgsConstructor
public final class InternalResponseInterceptor implements ResponseInterceptor {

    /** The manager responsible for tracking and updating per-route rate limits. */
    private final @NotNull RateLimitManager rateLimitManager;

    /** The discovery engine used to match response URLs back to their route metadata. */
    private final @NotNull RouteDiscovery routeDiscovery;

    /** The shared recent response list maintained by the owning {@link dev.simplified.client.Client}. */
    private final @NotNull ConcurrentList<Response<?>> recentResponses;

    /**
     * {@inheritDoc}
     */
    @Override
    public Object intercept(@NotNull InvocationContext invocationContext, @NotNull Chain chain) throws Exception {
        feign.Response response = invocationContext.response();
        String routeId = this.extractRouteId(response);

        RateLimit.fromHeaders(response.headers()).ifPresent(serverLimit ->
            this.rateLimitManager.updateRateLimit(routeId, serverLimit)
        );

        // Transparent cache-validation short-circuit: on 304 Not Modified, locate the cached
        // body from the most recent non-error response to the same (method, URL) and return a
        // synthesized Response envelope carrying that body with fresh 304 metadata. Streaming
        // endpoints and endpoints with no prior successful response fall through to the
        // default error-decoder path, which raises NotModifiedException so callers can handle
        // the cache miss explicitly.
        if (response.status() == HttpStatus.NOT_MODIFIED.getCode()) {
            Object cached = this.buildCachedResponse(invocationContext);

            if (cached != null)
                return cached;
        }

        return chain.next(invocationContext);
    }

    /**
     * Extracts the route identifier from the internal request header stashed by
     * {@link InternalRequestInterceptor}. Falls back to longest-prefix URL matching
     * if the header is absent.
     *
     * @param response the Feign response whose originating request carries the route header
     * @return the route identifier string
     */
    private @NotNull String extractRouteId(@NotNull feign.Response response) {
        Collection<String> values = response.request().headers().get(InternalRequestInterceptor.ROUTE_ID_HEADER);

        if (values != null && !values.isEmpty())
            return values.iterator().next();

        return this.routeDiscovery.findMatchingMetadata(response.request().url()).getRoute();
    }

    /**
     * Builds a transparent cache-hit response for a {@code 304 Not Modified} exchange by
     * locating the most recent non-error cached response for the same {@code (method, URL)}
     * and wrapping its body in a fresh {@link Response.Impl} envelope.
     * <p>
     * Returns {@code null} if no matching cached response exists, in which case the caller
     * should fall through to {@code chain.next(...)} so the default error-decoder path
     * raises a {@link NotModifiedException} for the caller to handle explicitly (the
     * standard cache-miss revalidation signal).
     * <p>
     * The synthesized envelope preserves the wire truth of the exchange:
     * {@link Response#getStatus()} returns {@link HttpStatus#NOT_MODIFIED},
     * {@link Response#getDetails()} and {@link Response#getHeaders()} come from the new
     * {@code 304} response (per RFC 7232 §4.1 the server may include updated cache-control
     * headers), and {@link Response#getRequest()} reflects the request that produced the
     * cache hit. Only the body object is borrowed from the cached entry. The synthesized
     * response is appended to {@link #recentResponses} so that
     * {@code Client#getLastResponse()} observes the latest exchange.
     * <p>
     * On the short-circuit path the caller does not run the normal decoder pipeline, so the
     * empty {@code 304} body stream is released here via
     * {@link Util#ensureClosed(java.io.Closeable)} to return the underlying HTTP connection
     * to the pool.
     * <p>
     * The return type follows {@code InternalResponseDecoder}'s conventions: if the declared
     * endpoint return type is {@code Response<T>} the full envelope is returned, otherwise
     * only the cached body is returned and the envelope stays in {@code recentResponses} for
     * observability.
     *
     * @param invocationContext the Feign invocation context carrying the raw {@code 304}
     *                          response and the declared endpoint return type
     * @return the synthesized cache-hit response or raw body to hand back to the caller, or
     *         {@code null} if no cached entry matches and the caller should fall through
     */
    private @Nullable Object buildCachedResponse(@NotNull InvocationContext invocationContext) {
        feign.Response feignResponse = invocationContext.response();
        HttpMethod method = HttpMethod.of(feignResponse.request().httpMethod().name());
        String url = feignResponse.request().url();

        Optional<Response<?>> cachedOpt = this.recentResponses.stream()
            .filter(response -> !response.isError())
            .filter(response -> response.getRequest().getMethod() == method)
            .filter(response -> response.getRequest().getUrl().equals(url))
            .reduce((first, second) -> second);

        if (cachedOpt.isEmpty())
            return null;

        Response<?> cached = cachedOpt.get();

        @SuppressWarnings({ "unchecked", "rawtypes" })
        Response<?> synthesized = new Response.Impl(
            cached.getBody(),
            new NetworkDetails(feignResponse),
            HttpStatus.NOT_MODIFIED,
            new Request.Impl(method, url),
            Response.getHeaders(feignResponse.headers())
        );

        this.recentResponses.add(synthesized);

        // InvocationContext#proceed's finally block closes the response body on the normal
        // path; the short-circuit bypasses that, so release the (usually empty) 304 body here
        // to return the connection to the pool.
        Util.ensureClosed(feignResponse.body());

        Type returnType = invocationContext.returnType();

        if (returnType instanceof ParameterizedType parameterized && parameterized.getRawType().equals(Response.class))
            return synthesized;

        return synthesized.getBody();
    }

}
