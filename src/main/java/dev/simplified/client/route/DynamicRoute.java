package dev.simplified.client.route;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation that marks a custom annotation type as a dynamic routing annotation.
 * <p>
 * A dynamic routing annotation declares a method whose return type implements
 * {@link DynamicRouteProvider}. At runtime, {@link RouteDiscovery} reflectively invokes
 * that method to obtain the route and rate limit for the annotated endpoint method.
 * This mechanism allows a single enum or class to represent multiple route targets
 * while keeping the annotation syntax clean and domain-specific.
 * <p>
 * Example of a custom dynamic routing annotation:
 * <pre>{@code
 * @DynamicRoute
 * @Target(ElementType.METHOD)
 * @Retention(RetentionPolicy.RUNTIME)
 * public @interface MojangDomain {
 *     MojangClient.Domain value(); // enum that implements DynamicRouteProvider
 * }
 * }</pre>
 *
 * @see DynamicRouteProvider
 * @see RouteDiscovery
 * @see Route
 */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DynamicRoute {

    /**
     * The name of the method on the annotated annotation that yields a {@link DynamicRouteProvider}
     * instance, defaulting to {@code "value"}.
     */
    @NotNull String methodName() default "value";

}
