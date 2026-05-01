package dev.simplified.client.exception;

import com.google.gson.Gson;
import dev.simplified.client.decoder.GsonAwareErrorDecoder;
import dev.simplified.util.Lazy;
import lombok.AccessLevel;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;

/**
 * Abstract {@link ApiException} base that lazily decodes the HTTP error body into an
 * {@link ApiErrorResponse} via a caller-supplied {@link Gson}.
 *
 * <p>Subclasses delegate to the 3-arg superclass constructor and follow up with a
 * {@link #resolve(Gson, Class)} call that wires the lazy decoder. The Gson parse is
 * deferred until the first {@link #getResponse()} call. When the body is absent or
 * deserialization fails, a fresh instance of the response type is constructed
 * reflectively via its no-arg constructor; sensible defaults come from the type's
 * field initializers - no fallback {@link java.util.function.Supplier} is required.</p>
 *
 * <p>Typical usage pairs this class with {@link GsonAwareErrorDecoder} so the {@link Gson}
 * argument flows from {@code ClientConfig} without a service-locator reach-back:</p>
 *
 * <pre>{@code
 * public final class FooApiException extends JsonApiException {
 *     public FooApiException(Gson gson, String methodKey, feign.Response response) {
 *         super(methodKey, response, "Foo");
 *         this.resolve(gson, FooErrorResponse.class);
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
     * Memoized typed error response. Initialized to read through to the parent's
     * {@link ApiException#response} field so that {@link #getResponse()} returns the
     * parent's fallback message-bearing stub when {@link #resolve(Gson, Class)} is
     * never invoked. Subclasses replace this Lazy in their constructor body via
     * {@link #resolve(Gson, Class)} to install the deferred Gson parse.
     */
    @Getter(AccessLevel.NONE)
    private @NotNull Lazy<? extends ApiErrorResponse> typedResponse = Lazy.of(() -> this.response);

    /**
     * Constructs a new {@code JsonApiException} from a Feign method key and buffered
     * anchor. Subclasses invoke {@link #resolve(Gson, Class)} from their constructor
     * body to wire the lazy typed response decoder.
     *
     * @param methodKey the Feign method key identifying the endpoint that failed
     * @param response the buffered Feign HTTP response
     * @param name a short name classifying this error type (e.g. {@code "Hypixel"})
     */
    protected JsonApiException(@NotNull String methodKey, @NotNull feign.Response response, @NotNull String name) {
        super(methodKey, response, name);
    }

    /**
     * Wires the lazy typed response decoder. Subclasses call this from their constructor
     * body after {@code super(...)} returns. The Gson parse runs at most once, on the
     * first {@link #getResponse()} call. If parsing fails or the body is absent, a fresh
     * {@code type} instance is constructed via its no-arg constructor as the fallback;
     * sensible default values come from the type's field initializers.
     *
     * @param gson the Gson instance used to deserialize the body
     * @param type the concrete error-response class to deserialize into
     * @param <E> the concrete error-response type
     */
    protected final <E extends ApiErrorResponse> void resolve(@NotNull Gson gson, @NotNull Class<E> type) {
        this.typedResponse = Lazy.of(() -> this.fromJson(gson, type).orElseGet(() -> newDefaultInstance(type)));
    }

    @Override
    public @NotNull ApiErrorResponse getResponse() {
        return this.typedResponse.get();
    }

    /**
     * Constructs a fresh instance of {@code type} via its declared no-arg constructor,
     * mirroring how Gson itself instantiates target types during deserialization. The
     * constructor's accessibility is widened reflectively so vendor classes may keep
     * their {@code @NoArgsConstructor(access = AccessLevel.PRIVATE)} declaration.
     *
     * @param type the concrete error-response class to instantiate
     * @param <E> the concrete error-response type
     * @return a fresh fallback instance with field-initializer defaults applied
     * @throws IllegalStateException if {@code type} has no declared no-arg constructor
     *                               or the constructor invocation throws
     */
    private static <E extends ApiErrorResponse> @NotNull E newDefaultInstance(@NotNull Class<E> type) {
        try {
            Constructor<E> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(
                "Type '" + type.getName() + "' requires a no-arg constructor for fallback instantiation",
                ex
            );
        }
    }

}
