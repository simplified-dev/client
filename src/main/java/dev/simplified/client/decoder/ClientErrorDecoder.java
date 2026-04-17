package dev.simplified.client.decoder;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.exception.ApiException;
import feign.Response;
import feign.codec.ErrorDecoder;
import org.jetbrains.annotations.NotNull;

/**
 * Specialized {@link ErrorDecoder} contract that decodes HTTP error responses into
 * strongly-typed {@link ApiException} instances.
 * <p>
 * Each {@link Client} subclass provides its own implementation via
 * {@link ClientConfig#errorDecoder}, allowing domain-specific
 * error response bodies to be parsed into meaningful exception types. The returned
 * {@link ApiException} is then wrapped by the internal error pipeline
 * ({@link InternalErrorDecoder}) which adds retry tracking and rate limit handling
 * before the exception surfaces to the caller.
 *
 * @see InternalErrorDecoder
 * @see ApiException
 * @see ClientConfig#errorDecoder
 */
public interface ClientErrorDecoder extends ErrorDecoder {

    /**
     * Decodes an HTTP error response into an {@link ApiException}.
     * <p>
     * Implementations should inspect the response status code and body to construct
     * an {@link ApiException} (or a subclass thereof) that carries all relevant error
     * details for the calling application.
     *
     * @param methodKey the Feign method key identifying the endpoint that produced the error
     * @param response the raw HTTP error response
     * @return a non-null {@link ApiException} representing the decoded error
     */
    @Override
    @NotNull ApiException decode(@NotNull String methodKey, @NotNull Response response);

}
