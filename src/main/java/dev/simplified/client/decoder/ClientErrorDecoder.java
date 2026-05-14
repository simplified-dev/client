package dev.simplified.client.decoder;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.exception.ApiException;
import dev.simplified.client.exception.ErrorContext;
import org.jetbrains.annotations.NotNull;

/**
 * Functional interface that decodes a primitive {@link ErrorContext} into a strongly-typed
 * {@link ApiException}.
 * <p>
 * Each {@link Client} subclass provides its own implementation via
 * {@link ClientConfig#errorDecoder}, allowing domain-specific error response bodies to be
 * parsed into meaningful exception types. The returned {@link ApiException} is then handed
 * back to the internal error pipeline ({@link InternalErrorDecoder}) which adds retry
 * tracking and rate limit handling before the exception surfaces to the caller.
 * <p>
 * The contract is feign-free by design - {@link InternalErrorDecoder} adapts feign's
 * {@link feign.codec.ErrorDecoder} signature on behalf of domain decoders, so domain code
 * never imports {@link feign.Response}.
 *
 * @see InternalErrorDecoder
 * @see ApiException
 * @see ErrorContext
 * @see ClientConfig#errorDecoder
 */
@FunctionalInterface
public interface ClientErrorDecoder {

    /**
     * Decodes a primitive HTTP error context into an {@link ApiException}.
     * <p>
     * Implementations should inspect the context's status and body bytes to construct an
     * {@link ApiException} (or a subclass thereof) that carries all relevant error details
     * for the calling application.
     *
     * @param context the primitive HTTP context bundle prepared by {@link InternalErrorDecoder}
     * @return a non-null {@link ApiException} representing the decoded error
     */
    @NotNull ApiException decode(@NotNull ErrorContext context);

}
