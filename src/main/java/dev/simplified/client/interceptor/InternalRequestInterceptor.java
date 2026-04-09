package dev.simplified.client.interceptor;

import dev.simplified.client.Client;
import dev.simplified.client.exception.RateLimitException;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.ETag;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import dev.simplified.client.route.RouteDiscovery;
import dev.simplified.collection.ConcurrentList;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Feign {@link RequestInterceptor} that applies route resolution and client-side rate limit
 * enforcement to every outbound request.
 * <p>
 * For each {@link RequestTemplate}, this interceptor performs the following steps in order:
 * <ol>
 *   <li>Resolves the target {@link RouteDiscovery.Metadata} for the invoked endpoint method
 *       via {@link RouteDiscovery}.</li>
 *   <li>Checks whether the route is currently rate-limited using {@link RateLimitManager}.
 *       If the limit has been reached, a {@link RateLimitException} is thrown to abort the
 *       request before it leaves the client.</li>
 *   <li>Records the request in the rate limit tracker so future calls can be evaluated
 *       against the configured quota.</li>
 *   <li>Replaces the placeholder target URL on the template with the real HTTPS URL
 *       obtained from the route metadata.</li>
 * </ol>
 * <p>
 * This class is instantiated internally by {@link Client} during Feign
 * builder configuration and is not intended for direct use by application code.
 *
 * @see InternalResponseInterceptor
 * @see RouteDiscovery
 * @see RateLimitManager
 * @see RateLimitException
 */
@RequiredArgsConstructor
public final class InternalRequestInterceptor implements RequestInterceptor {

    /** The manager responsible for tracking and enforcing per-route rate limits. */
    private final @NotNull RateLimitManager rateLimitManager;

    /** The discovery engine that maps endpoint methods to their route metadata. */
    private final @NotNull RouteDiscovery routeDiscovery;

    /** The shared recent response list maintained by the owning {@link Client}. */
    private final @NotNull ConcurrentList<Response<?>> recentResponses;

    /** Internal header key used to carry the resolved route identifier from request to response interceptor. */
    static final @NotNull String ROUTE_ID_HEADER = NetworkDetails.INTERNAL_HEADER_PREFIX + "Route-Id";

    /**
     * {@inheritDoc}
     */
    @Override
    public void apply(@NotNull RequestTemplate template) {
        Method method = template.methodMetadata().method();
        RouteDiscovery.Metadata routeMetadata = this.routeDiscovery.getMetadata(method);

        if (this.rateLimitManager.isRateLimited(routeMetadata.getRoute(), routeMetadata.getRateLimit()))
            throw new RateLimitException(template, routeMetadata);

        this.rateLimitManager.trackRequest(routeMetadata.getRoute(), routeMetadata.getRateLimit());

        template.header(ROUTE_ID_HEADER, routeMetadata.getRoute());
        template.target(routeMetadata.getFullUrl());

        this.attachIfNoneMatch(template);
    }

    /**
     * Attaches an {@code If-None-Match} header to the outbound request when a cached
     * response for the same {@code (HttpMethod, URL)} carries an {@link ETag}, enabling
     * transparent cache revalidation on {@code GET}/{@code HEAD} requests.
     * <p>
     * Only {@link HttpMethod#GET} and {@link HttpMethod#HEAD} are eligible - auto-attaching
     * on mutating methods ({@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE}) would
     * silently convert every mutating call into optimistic-concurrency enforcement and
     * produce surprising {@code 412} responses on stale caches. Callers who need
     * {@code If-Match} semantics set it explicitly via Feign's {@code @Headers} annotation
     * or a user-provided {@link RequestInterceptor}.
     * <p>
     * Caller-provided {@code If-None-Match} headers always win - this helper checks
     * {@link RequestTemplate#headers()} (a {@link java.util.TreeMap} keyed by
     * {@link String#CASE_INSENSITIVE_ORDER}) and returns early if the header is already
     * present in any casing. URL matching against the cache is exact-string: both the
     * cached URL and {@code template.url()} are produced by the same {@link RequestTemplate}
     * resolution pipeline for the same {@link RouteDiscovery} metadata, so byte-identical
     * equality is a reliable signal. This helper must be called <b>after</b>
     * {@link RequestTemplate#target(String)} has been set to the real base URL, otherwise
     * {@link RequestTemplate#url()} still contains the placeholder from client construction.
     *
     * @param template the outbound Feign request template, with target already set
     */
    private void attachIfNoneMatch(@NotNull RequestTemplate template) {
        HttpMethod method = HttpMethod.of(template.method());

        if (method != HttpMethod.GET && method != HttpMethod.HEAD)
            return;

        if (template.headers().containsKey(ETag.IF_NONE_MATCH_HEADER))
            return;

        String url = template.url();

        this.recentResponses.stream()
            .filter(response -> !response.isError())
            .filter(response -> response.getRequest().getMethod() == method)
            .filter(response -> response.getRequest().getUrl().equals(url))
            .map(Response::getETag)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .reduce((first, second) -> second)
            .ifPresent(etag -> template.header(ETag.IF_NONE_MATCH_HEADER, etag.toHeaderValue()));
    }

}
