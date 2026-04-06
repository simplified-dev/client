package dev.simplified.client.request;

import dev.simplified.client.Client;

/**
 * Marker interface for Feign-based HTTP API definitions.
 * <p>
 * Each concrete implementation of this interface declares one or more Feign
 * {@link feign.RequestLine}-annotated methods that describe the HTTP operations available on a
 * remote API - effectively the contract that the remote service exposes. The {@link Client} class
 * is parameterized on a subtype of {@code Contract}, which constrains the set of callable
 * operations and enables type-safe lookup via {@link Client#getContract()}.
 * <p>
 * Implementing classes typically reside alongside their owning client construction site and are
 * instantiated by the Feign builder during client construction.
 *
 * @see Client
 * @see AsyncAccess
 */
public interface Contract {

}
