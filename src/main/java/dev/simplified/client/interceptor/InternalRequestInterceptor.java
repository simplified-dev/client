package dev.simplified.client.interceptor;

import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.client.Client;
import dev.simplified.client.cache.CachingFeignClient;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.ratelimit.RateLimitingFeignClient;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.route.RouteDiscovery;
import feign.MethodMetadata;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.jetbrains.annotations.NotNull;

/**
 * Feign {@link RequestInterceptor} that applies route resolution to every outbound request.
 * <p>
 * For each {@link RequestTemplate}, this interceptor performs the following steps in order:
 * <ol>
 *   <li>Resolves the target {@link RouteDiscovery.Metadata} for the invoked endpoint method
 *       via {@link RouteDiscovery}.</li>
 *   <li>Numbers the request with {@link RateLimitManager#nextSequence()}, replacing any number
 *       an earlier attempt of the same template carried, so {@link InternalResponseInterceptor}
 *       can tell a late response from the response to a later request. The number rides on the
 *       request Feign builds as an {@linkplain NetworkDetails#isInternalHeader(String) internal
 *       header}, which the client's transport removes before the request leaves the client, so
 *       it never reaches the origin.</li>
 *   <li>Replaces the placeholder target URL on the template with the real HTTPS URL
 *       obtained from the route metadata.</li>
 * </ol>
 * <p>
 * HTTP cache concerns (conditional revalidation header attachment, fresh-hit
 * short-circuiting) live in
 * {@link CachingFeignClient CachingFeignClient}, which wraps
 * the underlying Feign client below this interceptor and handles {@code If-None-Match} /
 * {@code If-Modified-Since} attachment itself.
 * <p>
 * Client-side rate-limit enforcement lives in {@link RateLimitingFeignClient}, which wraps the
 * transport below {@link CachingFeignClient CachingFeignClient}, so a request the cache answers
 * is neither refused by the route's bucket nor counted against it; this interceptor runs
 * before the cache is consulted and so neither checks nor counts a request.
 * <p>
 * This class is instantiated internally by {@link Client} during Feign
 * builder configuration and is not intended for direct use by application code.
 *
 * @see InternalResponseInterceptor
 * @see RouteDiscovery
 * @see RateLimitManager
 * @see RateLimitingFeignClient
 * @see dev.simplified.client.cache.CachingFeignClient
 */
@RequiredArgsConstructor
public final class InternalRequestInterceptor implements RequestInterceptor {

    /**
     * The manager whose {@link RateLimitManager#nextSequence()} numbers each request.
     */
    private final @NotNull RateLimitManager rateLimitManager;

    /**
     * The discovery engine that maps endpoint methods to their route metadata, whose
     * {@linkplain RouteDiscovery.Metadata#getFullUrl() full URL} each request is sent to.
     */
    private final @NotNull RouteDiscovery routeDiscovery;

    /**
     * Internal header key used to carry the request's {@linkplain RateLimitManager#nextSequence()
     * sequence number} from request to response interceptor; the transport removes it from the
     * request it sends.
     */
    static final @NotNull String SEQUENCE_HEADER = NetworkDetails.REQUEST_SEQUENCE;

    /**
     * {@inheritDoc}
     */
    @Override
    public void apply(@NotNull RequestTemplate template) {
        MethodMetadata endpoint = template.methodMetadata();
        RouteDiscovery.Metadata routeMetadata = this.routeDiscovery.getMetadata(endpoint.method());

        template.removeHeader(SEQUENCE_HEADER);
        template.header(SEQUENCE_HEADER, Long.toString(this.rateLimitManager.nextSequence()));
        template.target(routeMetadata.getFullUrl());
    }

}
