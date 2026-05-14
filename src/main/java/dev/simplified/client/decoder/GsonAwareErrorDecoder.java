package dev.simplified.client.decoder;

import com.google.gson.Gson;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.exception.ApiException;
import dev.simplified.client.exception.ErrorContext;
import dev.simplified.client.exception.JsonApiException;
import org.jetbrains.annotations.NotNull;

/**
 * User-facing ergonomic variant of {@link ClientErrorDecoder} that receives the
 * owning {@link ClientConfig}'s {@link Gson} instance at decode time.
 *
 * <p>{@link ClientErrorDecoder} carries no codec parameter; use this interface when the
 * decoder needs Gson to deserialize the error body - typically via a
 * {@link JsonApiException} subclass - and pass it through the
 * {@link ClientConfig.Builder#withErrorDecoder(GsonAwareErrorDecoder)} overload, which
 * closes over the builder's configured {@code Gson} and adapts to the underlying
 * {@link ClientErrorDecoder} contract.</p>
 *
 * <p>The idiomatic usage is a constructor reference:</p>
 * <pre>{@code
 * ClientConfig.builder(MyContract.class, gson)
 *     .withErrorDecoder(MyApiException::new)
 *     .build();
 * }</pre>
 *
 * @see ClientErrorDecoder
 * @see JsonApiException
 * @see ClientConfig.Builder#withErrorDecoder(GsonAwareErrorDecoder)
 */
@FunctionalInterface
public interface GsonAwareErrorDecoder {

    /**
     * Decodes a primitive HTTP error context into an {@link ApiException}, with access to
     * the owning client's configured {@link Gson} for deserializing the error body.
     *
     * @param gson the Gson instance configured on the owning {@link ClientConfig}
     * @param context the primitive HTTP context bundle prepared by {@link InternalErrorDecoder}
     * @return a non-null {@link ApiException} representing the decoded error
     */
    @NotNull ApiException decode(@NotNull Gson gson, @NotNull ErrorContext context);

}
