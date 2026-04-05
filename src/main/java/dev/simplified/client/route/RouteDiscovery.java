package dev.simplified.client.route;

import dev.simplified.client.Client;
import dev.simplified.client.interceptor.InternalRequestInterceptor;
import dev.simplified.client.interceptor.InternalResponseInterceptor;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.collection.concurrent.Concurrent;
import dev.simplified.collection.concurrent.ConcurrentMap;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Route resolution engine that discovers and caches the {@link Route @Route} and
 * {@link DynamicRoute @DynamicRoute} annotations declared on a Feign endpoint interface.
 * <p>
 * On construction, the target class is scanned for a mandatory type-level route (the
 * default) and optional per-method route overrides. The resulting {@link Metadata} objects
 * pair each route string with its {@link RateLimit} policy and are stored in an
 * unmodifiable map for fast, lock-free lookups at request time.
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

    /** The type-level default route metadata, guaranteed to be present. */
    private final @NotNull Metadata defaultRoute;

    /** Unmodifiable map of per-method route overrides discovered at construction time. */
    private final @NotNull ConcurrentMap<Method, Metadata> methodRoutes;

    /**
     * Scans the given Feign endpoint interface for route annotations and caches the results.
     * <p>
     * The target class must declare either a {@link Route @Route} or a
     * {@link DynamicRoute @DynamicRoute}-annotated custom annotation at the type level;
     * otherwise an {@link IllegalArgumentException} is thrown. Each declared method is
     * additionally inspected for method-level route overrides.
     *
     * @param target the Feign endpoint interface class to scan
     * @throws IllegalArgumentException if no type-level route annotation is found on {@code target}
     */
    public RouteDiscovery(@NotNull Class<?> target) {
        Optional<Metadata> defaultRoute = extractRouteFromTarget(target);

        if (defaultRoute.isEmpty())
            throw new IllegalArgumentException("No @Route or @DynamicRoute found on type of " + target.getName());

        ConcurrentMap<Method, Metadata> methodRoutes = Concurrent.newMap();

        for (Method method : target.getDeclaredMethods())
            extractRouteFromTarget(method).ifPresent(info -> methodRoutes.put(method, info));

        this.defaultRoute = defaultRoute.get();
        this.methodRoutes = methodRoutes.toUnmodifiableMap();
    }

    /**
     * Extracts route metadata from a target, which may be either a {@link Class} or a
     * {@link Method}.
     * <p>
     * The extraction attempts two strategies in order:
     * <ol>
     *   <li>A direct {@link Route @Route} annotation on the class (for a class target)
     *       or on the declaring class (for a method target).</li>
     *   <li>A {@link DynamicRoute @DynamicRoute}-annotated custom annotation on the target,
     *       whose designated method is invoked reflectively to obtain a
     *       {@link DynamicRouteProvider}.</li>
     * </ol>
     *
     * @param target a {@link Class} or {@link Method} to inspect for route annotations
     * @return an {@link Optional} containing the resolved {@link Metadata}, or empty if
     *         no route annotation is found
     */
    private static @NotNull Optional<Metadata> extractRouteFromTarget(@NotNull Object target) {
        Class<?> targetClass;
        if (target instanceof Class<?> clazz)
            targetClass = clazz;
        else if (target instanceof Method method)
            targetClass = method.getDeclaringClass();
        else
            return Optional.empty();

        Route routeAnno = targetClass.getAnnotation(Route.class);
        if (routeAnno != null) {
            String route = stripProtocol(routeAnno.value());
            RateLimit rateLimit = RateLimit.fromAnnotation(routeAnno.rateLimit());
            return Optional.of(new Metadata(route, rateLimit));
        }

        Annotation[] annotations = (target instanceof Method method)
            ? method.getAnnotations()
            : targetClass.getAnnotations();

        for (Annotation annotation : annotations) {
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
                        provider.getRateLimit()
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
        String stripped = stripProtocol(requestUrl);
        Metadata defaultRoute = this.defaultRoute;

        // Seed with the default route (always the fallback)
        Metadata bestMatch = defaultRoute;
        int bestMatchLength = stripped.startsWith(defaultRoute.getRoute()) ? defaultRoute.getRoute().length() : 0;

        // Find the longest prefix match among method-level route overrides
        for (Metadata metadata : this.methodRoutes.values()) {
            String route = metadata.getRoute();

            if (stripped.startsWith(route) && route.length() > bestMatchLength) {
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
        return this.getMethodRoutes().getOrDefault(method, this.getDefaultRoute());
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
     * Immutable value object that pairs a route string with its {@link RateLimit} policy.
     * <p>
     * Instances are created during {@link RouteDiscovery} construction and cached for the
     * lifetime of the owning {@link dev.simplified.client.Client}.
     *
     * @see RouteDiscovery
     */
    @Getter
    public static class Metadata {

        /** The route string (host and optional base path) without a protocol prefix. */
        private final @NotNull String route;

        /** The rate limit policy governing traffic through this route. */
        private final @NotNull RateLimit rateLimit;

        /** The pre-computed full HTTPS URL for this route. */
        private final @NotNull String fullUrl;

        /**
         * Constructs a new metadata entry for the given route and rate-limit policy.
         *
         * @param route the route string without a protocol prefix
         * @param rateLimit the rate-limit policy governing traffic through this route
         */
        public Metadata(@NotNull String route, @NotNull RateLimit rateLimit) {
            this.route = route;
            this.rateLimit = rateLimit;
            this.fullUrl = "https://" + route;
        }

    }

}
