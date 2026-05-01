package dev.simplified.client.exception;

import com.google.gson.Gson;
import dev.simplified.client.decoder.GsonAwareErrorDecoder;
import feign.FeignException;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * Abstract {@link ApiException} base that decodes the HTTP error body into an
 * {@link ApiErrorResponse} via a caller-supplied {@link Gson}.
 *
 * <p>Subclasses pass the concrete response class plus a fallback supplier to the superclass
 * constructor. The fallback is materialized when the body is absent or fails to deserialize.
 * Subclasses that want a typed {@code getResponse()} return type add a one-line covariant
 * override.</p>
 *
 * <p>Typical usage pairs this class with {@link GsonAwareErrorDecoder} so the {@link Gson}
 * argument flows from {@code ClientConfig} without a service-locator reach-back:</p>
 *
 * <pre>{@code
 * public final class FooApiException extends JsonApiException {
 *     public FooApiException(Gson gson, String methodKey, feign.Response response) {
 *         super(methodKey, response, "Foo", gson, FooErrorResponse.class, FooErrorResponse.Unknown::new);
 *     }
 *
 *     @Override public FooErrorResponse getResponse() { return (FooErrorResponse) super.getResponse(); }
 * }
 *
 * ClientConfig.builder(FooContract.class, gson)
 *     .withErrorDecoder(FooApiException::new)
 *     .build();
 * }</pre>
 *
 * <p>The class is intentionally non-generic - {@link Throwable} subclasses may not be
 * generic (JLS 8.1.2) - so the typed return type lives on the subclass.</p>
 *
 * @see ApiException
 * @see GsonAwareErrorDecoder
 */
public abstract class JsonApiException extends ApiException {

    /**
     * Constructs a new {@code JsonApiException} by decoding the Feign response body into
     * {@code responseType} via {@code gson}. Falls back to {@code fallback.get()} if the
     * body is absent or deserialization yields {@code null}.
     *
     * @param <E> the concrete error-response type
     * @param methodKey the Feign method key identifying the endpoint that failed
     * @param response the raw Feign HTTP response
     * @param name a short name classifying this error type (e.g. {@code "Hypixel"})
     * @param gson the Gson instance for deserialization
     * @param responseType the concrete error-response class to deserialize into
     * @param fallback supplier invoked when the body is absent or cannot be parsed
     */
    protected <E extends ApiErrorResponse> JsonApiException(
        @NotNull String methodKey,
        @NotNull feign.Response response,
        @NotNull String name,
        @NotNull Gson gson,
        @NotNull Class<E> responseType,
        @NotNull Supplier<E> fallback
    ) {
        super(FeignException.errorStatus(methodKey, response), response, name);
        super.response = this.getBody()
            .map(json -> this.fromJson(gson, json, responseType))
            .orElse(fallback.get());
    }

}
