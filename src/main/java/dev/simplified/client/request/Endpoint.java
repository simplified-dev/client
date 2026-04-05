package dev.simplified.client.request;

import dev.simplified.client.Client;

/**
 * Marker interface for Feign-based HTTP endpoint definitions.
 * <p>
 * Each concrete implementation of this interface declares one or more Feign
 * {@link feign.RequestLine}-annotated methods that describe the HTTP operations
 * available on a remote API. The {@link Client} class is parameterized on a
 * subtype of {@code Endpoint}, which constrains the set of callable operations
 * and enables type-safe lookup via {@link Client#getEndpoint()}.
 * <p>
 * Implementing classes typically reside alongside their owning {@link Client}
 * subclass and are instantiated by the Feign builder during client construction.
 *
 * @see Client
 * @see ReactiveEndpoint
 */
public interface Endpoint {

}
