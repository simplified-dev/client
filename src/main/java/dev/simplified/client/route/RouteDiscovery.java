package dev.simplified.client.route;

import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Getter;
import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.interceptor.InternalRequestInterceptor;
import dev.simplified.client.interceptor.InternalResponseInterceptor;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.subnet.IPv6Prefix;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Route resolution engine that discovers and caches the {@link Route @Route} and
 * {@link DynamicRoute @DynamicRoute} annotations declared on a Feign endpoint interface.
 * <p>
 * On construction, the target class is scanned for a mandatory type-level route (the
 * default) and optional per-method route overrides. The resulting {@link Metadata} objects
 * pair each route string with its {@link RateLimit} policy and a precomputed
 * {@linkplain Metadata#bucketKey bucket key} composed against the optional
 * {@linkplain ClientConfig#subnetPrefix subnet prefix} carried on the supplied
 * {@link ClientConfig}, and are stored in an unmodifiable map for fast, lock-free lookups
 * at request time.
 * <p>
 * Two lookup strategies are provided:
 * <ul>
 *   <li>{@link #getMetadata(Method)} - exact method match, used by
 *       {@link InternalRequestInterceptor} before a request is sent.</li>
 *   <li>{@link #findMatchingMetadata(String)} - longest-prefix URL match, used by
 *       {@link InternalResponseInterceptor} when the originating
 *       method is not directly available.</li>
 * </ul>
 *
 * @see Route
 * @see DynamicRoute
 * @see DynamicRouteProvider
 * @see Client
 */
@Getter
public final class RouteDiscovery {

    /**
     * The type-level default route metadata, guaranteed to be present.
     */
    private final @NotNull Metadata defaultRoute;

    /**
     * Unmodifiable map of per-method route overrides discovered at construction time.
     */
    private final @NotNull ConcurrentMap<Method, Metadata> methodRoutes;

    /**
     * Scans the target Feign endpoint interface declared on the given options for route
     * annotations and caches the results.
     * <p>
     * The {@linkplain ClientConfig#target target class} must declare either a
     * {@link Route @Route} or a {@link DynamicRoute @DynamicRoute}-annotated custom annotation
     * at the type level; otherwise an {@link IllegalArgumentException} is thrown. Each declared
     * method is additionally inspected for method-level route overrides. The optional
     * {@linkplain ClientConfig#subnetPrefix subnet prefix} carried on the options is
     * baked into each {@link Metadata}'s precomputed bucket key, so subnet-rotated clients
     * resolve to a per-subnet rate-limit identifier without runtime composition.
     *
     * @param options the client configuration carrying the target class and optional subnet prefix
     * @throws IllegalArgumentException if no type-level route annotation is found on the target
     */
    public RouteDiscovery(@NotNull ClientConfig<?> options) {
        Class<?> target = options.getTarget();
        Optional<IPv6Prefix> subnetPrefix = options.getSubnetPrefix();

        Optional<Metadata> defaultRoute = extractRouteFromTarget(target, subnetPrefix);
        if (defaultRoute.isEmpty())
            throw new IllegalArgumentException("No @Route or @DynamicRoute found on type of " + target.getName());

        ConcurrentMap<Method, Metadata> methodRoutes = Concurrent.newMap();
        for (Method method : target.getDeclaredMethods())
            extractRouteFromTarget(method, subnetPrefix).ifPresent(info -> methodRoutes.put(method, info));

        this.defaultRoute = defaultRoute.get();
        this.methodRoutes = methodRoutes.toUnmodifiable();
    }

    /**
     * Extracts route metadata from a target, which may be either the endpoint interface or one
     * of its methods.
     * <p>
     * The extraction attempts two strategies in order, each against the target's own
     * annotations:
     * <ol>
     *   <li>A direct {@link Route @Route} annotation.</li>
     *   <li>A {@link DynamicRoute @DynamicRoute}-annotated custom annotation, whose designated
     *       method is invoked reflectively to obtain a {@link DynamicRouteProvider}.</li>
     * </ol>
     * A method without either yields nothing, so {@link #getMetadata(Method)} gives it the
     * type-level route; the type-level route never stands in for a method's own.
     *
     * @param target the endpoint interface or one of its methods
     * @param subnetPrefix the optional subnet prefix to bake into the resulting metadata's bucket key
     * @return an {@link Optional} containing the resolved {@link Metadata}, or empty if
     *         no route annotation is found
     */
    private static @NotNull Optional<Metadata> extractRouteFromTarget(
        @NotNull AnnotatedElement target,
        @NotNull Optional<IPv6Prefix> subnetPrefix
    ) {
        Route routeAnno = target.getAnnotation(Route.class);
        if (routeAnno != null) {
            String route = stripProtocol(routeAnno.value());
            RateLimit rateLimit = RateLimit.fromAnnotation(routeAnno.rateLimit());
            return Optional.of(new Metadata(route, rateLimit, subnetPrefix));
        }

        for (Annotation annotation : target.getAnnotations()) {
            DynamicRoute dynamicRoute = annotation.annotationType().getAnnotation(DynamicRoute.class);

            if (dynamicRoute == null)
                continue;

            try {
                // Get the method that returns the URL provider
                String methodName = dynamicRoute.methodName();
                Method valueMethod = annotation.annotationType().getMethod(methodName);
                Object value = valueMethod.invoke(annotation);

                if (value instanceof DynamicRouteProvider provider) {
                    return Optional.of(new Metadata(
                        stripProtocol(provider.getRoute()),
                        provider.getRateLimit(),
                        subnetPrefix
                    ));
                }
            } catch (Exception ignore) { }
        }

        return Optional.empty();
    }

    /**
     * Finds the {@link Metadata} whose route is the longest prefix of the given request URL.
     * <p>
     * This method is used when the originating {@link Method} object is not directly available
     * (e.g. in response interceptors). Both the type-level default and all method-level overrides
     * are considered. If no method-level route matches, the default route is returned.
     *
     * @param requestUrl the full request URL (protocol prefix is stripped internally)
     * @return the best-matching {@link Metadata}, never {@code null}
     */
    public @NotNull Metadata findMatchingMetadata(@NotNull String requestUrl) {
        int protocolOffset = protocolPrefixLength(requestUrl);
        Metadata defaultRoute = this.defaultRoute;

        // Seed with the default route (always the fallback)
        Metadata bestMatch = defaultRoute;
        int bestMatchLength = requestUrl.startsWith(defaultRoute.getRoute(), protocolOffset)
            ? defaultRoute.getRoute().length()
            : 0;

        // Find the longest prefix match among method-level route overrides. Using
        // startsWith(prefix, offset) avoids the substring allocation stripProtocol would
        // have otherwise paid on every call to this method.
        for (Metadata metadata : this.methodRoutes.values()) {
            String route = metadata.getRoute();

            if (requestUrl.startsWith(route, protocolOffset) && route.length() > bestMatchLength) {
                bestMatch = metadata;
                bestMatchLength = route.length();
            }
        }

        return bestMatch;
    }

    /**
     * Returns the {@link Metadata} associated with the given endpoint method.
     * <p>
     * If the method does not have an explicit route override, the type-level default
     * route is returned.
     *
     * @param method the Feign endpoint method to look up
     * @return the route metadata for the method, or the default if no override exists
     */
    public @NotNull Metadata getMetadata(@NotNull Method method) {
        return this.methodRoutes.getOrDefault(method, this.defaultRoute);
    }

    /**
     * Locates the {@link Metadata} whose route string equals the given identifier, scanning the
     * default route plus every method-level override.
     * <p>
     * Used by {@link Client}'s {@link DynamicRouteProvider} rate-limit query overloads to look up
     * the precomputed bucket key. Linear in the number of routes declared on the contract -
     * typically a handful, so the cost is negligible compared to building a separate index.
     *
     * @param routeString the route string to match (without protocol prefix)
     * @return an {@link Optional} containing the matching metadata, or empty if no route matches
     */
    public @NotNull Optional<Metadata> findByRoute(@NotNull String routeString) {
        if (this.defaultRoute.getRoute().equals(routeString))
            return Optional.of(this.defaultRoute);

        for (Metadata metadata : this.methodRoutes.values()) {
            if (metadata.getRoute().equals(routeString))
                return Optional.of(metadata);
        }

        return Optional.empty();
    }

    /**
     * Collects the unique bare hostnames advertised by every route this discovery knows about,
     * for use as DNS preresolve targets and pool-prewarm anchors. The port and path are stripped
     * so the result matches what {@link InetAddress#getAllByName(String)} expects.
     *
     * @return the set of unique hostnames; empty if every route is hostless
     */
    public @NotNull Set<String> collectAdvertisedHosts() {
        Set<String> hosts = new HashSet<>();
        addHost(hosts, this.defaultRoute);
        this.methodRoutes.values().forEach(metadata -> addHost(hosts, metadata));
        return hosts;
    }

    private static void addHost(@NotNull Set<String> sink, @NotNull Metadata metadata) {
        String route = metadata.getRoute();
        int slash = route.indexOf('/');
        String authority = slash < 0 ? route : route.substring(0, slash);
        int colon = authority.indexOf(':');
        String host = colon < 0 ? authority : authority.substring(0, colon);
        if (!host.isBlank()) sink.add(host);
    }

    /**
     * Returns the length of the URL scheme prefix (the {@code http://} or {@code https://}
     * literal) at the start of {@code url}, or {@code 0} if no prefix is present.
     *
     * @param url the URL to inspect
     * @return {@code 8} for {@code "https://"}, {@code 7} for {@code "http://"}, {@code 0} otherwise
     */
    private static int protocolPrefixLength(@NotNull String url) {
        if (url.startsWith("https://")) return 8;
        if (url.startsWith("http://")) return 7;
        return 0;
    }

    /**
     * Strips the {@code http://} or {@code https://} protocol prefix from a route string.
     *
     * @param route the route or URL string to strip
     * @return the route with any leading protocol prefix removed
     */
    private static @NotNull String stripProtocol(@NotNull String route) {
        if (route.startsWith("https://")) return route.substring(8);
        if (route.startsWith("http://")) return route.substring(7);
        return route;
    }

    /**
     * Immutable value object that pairs a route string with its {@link RateLimit} policy and a
     * precomputed bucket key for rate-limit lookups.
     * <p>
     * Instances are created during {@link RouteDiscovery} construction and cached for the lifetime
     * of the owning {@link Client}. The {@code fullUrl} and {@code bucketKey} fields are both
     * precomputed at construction so request-time lookups never re-build either string.
     *
     * @see RouteDiscovery
     */
    @Getter
    public static class Metadata {

        /**
         * The route string (host and optional base path) without a protocol prefix.
         */
        private final @NotNull String route;

        /**
         * The rate limit policy governing traffic through this route.
         */
        private final @NotNull RateLimit rateLimit;

        /**
         * The pre-computed full HTTPS URL for this route.
         */
        private final @NotNull String fullUrl;

        /**
         * The pre-computed rate-limit bucket key, equal to {@link #route} when no subnet prefix is
         * configured or {@code route + "@" + subnetPrefix} when the owning client is bound inside a
         * rotation subnet. Used directly by the request and response interceptors and by
         * {@link Client}'s rate-limit query API.
         */
        private final @NotNull String bucketKey;

        /**
         * The start of every {@linkplain #getQuotaKey(String) quota key}: the route's host and
         * port, without its path, followed by {@code #}.
         */
        @Getter(AccessLevel.NONE)
        private final @NotNull String quotaKeyPrefix;

        /**
         * The end of every {@linkplain #getQuotaKey(String) quota key}: {@code "@" + subnetPrefix}
         * as {@link #bucketKey} ends, or empty when no subnet prefix is configured.
         */
        @Getter(AccessLevel.NONE)
        private final @NotNull String quotaKeySuffix;

        /**
         * Constructs a new metadata entry for the given route, rate-limit policy, and optional
         * subnet prefix.
         *
         * @param route the route string without a protocol prefix
         * @param rateLimit the rate-limit policy governing traffic through this route
         * @param subnetPrefix the optional subnet prefix the owning client is bound inside
         */
        public Metadata(
            @NotNull String route,
            @NotNull RateLimit rateLimit,
            @NotNull Optional<IPv6Prefix> subnetPrefix
        ) {
            int slash = route.indexOf('/');

            this.route = route;
            this.rateLimit = rateLimit;
            this.fullUrl = "https://" + route;
            this.bucketKey = subnetPrefix.map(p -> route + "@" + p).orElse(route);
            this.quotaKeyPrefix = (slash < 0 ? route : route.substring(0, slash)) + "#";
            this.quotaKeySuffix = subnetPrefix.map(p -> "@" + p).orElse("");
        }

        /**
         * Composes the bucket key of a quota a server names for the requests it answers on this
         * route.
         * <p>
         * A server names a quota for its whole host, so the key drops the route's path: every
         * route on one host that names the same quota shares one bucket, and each quota a host
         * names gets its own. The key is the route's host and port, {@code #}, the quota, and the
         * subnet prefix {@link #bucketKey} carries, so subnet-rotated clients keep a quota bucket
         * per subnet.
         *
         * @param quota the name of the quota, as {@link RateLimit#quotaFromHeaders(Map)} reads it
         * @return the bucket key of the quota
         */
        public @NotNull String getQuotaKey(@NotNull String quota) {
            return this.quotaKeyPrefix + quota + this.quotaKeySuffix;
        }

    }

}
