package dev.simplified.client.exception;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import dev.simplified.client.Client;
import dev.simplified.client.decoder.ClientErrorDecoder;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.request.Request;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.util.Lazy;
import feign.FeignException;
import feign.Util;
import lombok.AccessLevel;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Thrown when an HTTP request to a remote API fails.
 * <p>
 * {@code ApiException} extends {@link RuntimeException} and additionally implements
 * {@link Response}, making the full HTTP context (status, headers, body, network
 * details, and original request) available for inspection alongside the exception
 * itself.  This dual nature allows callers to both catch the exception in standard
 * {@code try/catch} blocks and interrogate it as if it were a normal response
 * object.
 * <p>
 * Instances are created by the {@link ClientErrorDecoder} pipeline during Feign
 * request processing.  Subclasses such as {@link RateLimitException} add
 * domain-specific metadata on top of the base HTTP error information.
 * <p>
 * The exception is anchored on a buffered {@link feign.Response}; the body, headers,
 * network details, and originating request are all derived lazily from that anchor
 * via memoizing {@link Lazy} holders. Callers that only inspect {@link #getStatus()}
 * pay zero allocation cost beyond the eager fields ({@code anchor}, {@code name},
 * {@code feignRequest}, {@code status}).
 * <p>
 * The {@link #response} field holds a parsed {@link ApiErrorResponse} whose
 * concrete type is determined by each {@link Client} subclass's error decoder.
 * When JSON deserialization of the error body fails, a fallback implementation
 * returning the raw exception message is used instead.
 *
 * @see RateLimitException
 * @see RetryableApiException
 * @see ClientErrorDecoder
 */
@Getter
public class ApiException extends RuntimeException implements Response<Optional<String>> {

    /** Constant flag indicating this response represents an error. */
    private final boolean error = true;

    /** The short name identifying the type of API error (e.g. {@code "Client"}, {@code "RateLimit"}). */
    private final @NotNull String name;

    /** The HTTP status code and message associated with the failed request. */
    private final @NotNull HttpStatus status;

    /** The buffered Feign response that anchors every lazily-derived field. */
    private final @NotNull feign.Response anchor;

    /** The original Feign request, retained for retry reconstruction in {@link RetryableApiException}. */
    @Getter(AccessLevel.PACKAGE)
    private final @NotNull feign.Request feignRequest;

    /** Memoized UTF-8 view of the response body bytes, or {@link Optional#empty()} if the body was absent. */
    @Getter(AccessLevel.NONE)
    private final @NotNull Lazy<Optional<String>> body;

    /** Memoized network timing and TLS metadata derived from {@link #anchor}. */
    private final @NotNull Lazy<NetworkDetails> details;

    /** Memoized response headers (internal instrumentation headers excluded) derived from {@link #anchor}. */
    private final @NotNull Lazy<ConcurrentMap<String, ConcurrentList<String>>> headers;

    /** Memoized originating request derived from {@link #feignRequest}. */
    private final @NotNull Lazy<Request> request;

    /** The structured error response parsed from the response body. */
    protected @NotNull ApiErrorResponse response;

    /** The number of retry attempts made before this exception was surfaced. */
    private int retryAttempts = 0;

    /**
     * Constructs an {@code ApiException} from a {@link FeignException} and its associated
     * buffered anchor, capturing a writable stack trace.
     * <p>
     * The {@link #response} field is initialized with a fallback {@link ApiErrorResponse}
     * that returns the exception message; subclass error decoders typically replace it
     * with a properly deserialized instance. The body, headers, network details, and
     * originating request are derived lazily from the anchor on first access.
     *
     * @param source the Feign exception wrapping the HTTP error
     * @param anchor the buffered Feign HTTP response - must carry a {@code byte[]}-backed
     *               body so the constructor can capture the bytes for the lazy
     *               {@link #getBody()} reader without contending over a consumed stream
     * @param name a short name classifying this error type
     */
    public ApiException(@NotNull FeignException source, @NotNull feign.Response anchor, @NotNull String name) {
        this(source, anchor, name, true);
    }

    /**
     * Constructs an {@code ApiException} from a {@link FeignException} and its associated
     * buffered anchor, with control over whether a stack trace is captured.
     * <p>
     * Subclasses representing expected, high-frequency error conditions (e.g. rate
     * limiting) pass {@code false} for {@code writableStackTrace} to avoid the
     * per-instance cost of {@link Throwable#fillInStackTrace()}. The HTTP context
     * (status, headers, body, request URL, {@link NetworkDetails}) carried by
     * {@code ApiException} is sufficient for diagnosis in those cases.
     *
     * @param source the Feign exception wrapping the HTTP error
     * @param anchor the buffered Feign HTTP response used as the single source of truth
     *               for body, headers, network details, and request derivation
     * @param name a short name classifying this error type
     * @param writableStackTrace whether this exception should capture a stack trace
     */
    protected ApiException(@NotNull FeignException source, @NotNull feign.Response anchor, @NotNull String name, boolean writableStackTrace) {
        super(source.getMessage(), source.getCause(), true, writableStackTrace);
        this.name = name;
        this.anchor = anchor;
        this.feignRequest = source.request();
        this.status = HttpStatus.of(source.status());
        byte[] bodyBytes = readAnchorBytes(anchor);
        this.body = Lazy.of(() -> bodyBytes.length == 0
            ? Optional.empty()
            : Optional.of(new String(bodyBytes, StandardCharsets.UTF_8)));
        this.details = Lazy.of(() -> new NetworkDetails(this.anchor));
        this.headers = Lazy.of(() -> Response.getHeaders(this.anchor.headers()));
        this.request = Lazy.of(() -> new Request.Impl(
            HttpMethod.of(this.feignRequest.httpMethod().name()),
            this.feignRequest.url()
        ));
        this.response = source::getMessage;
    }

    /**
     * Constructs an {@code ApiException} from a Feign method key and a buffered anchor.
     * <p>
     * Wraps the anchor in a {@link FeignException} via
     * {@link FeignException#errorStatus(String, feign.Response)} and delegates to the
     * canonical {@link #ApiException(FeignException, feign.Response, String) public
     * constructor}.
     *
     * @param methodKey the Feign method key identifying the endpoint that failed
     * @param anchor the buffered Feign HTTP response
     * @param name a short name classifying this error type
     * @return a new {@code ApiException} anchored on {@code anchor}
     */
    public static @NotNull ApiException fromMethodKey(@NotNull String methodKey, @NotNull feign.Response anchor, @NotNull String name) {
        return new ApiException(FeignException.errorStatus(methodKey, anchor), anchor, name);
    }

    @Override
    public @NotNull Optional<String> getBody() {
        return this.body.get();
    }

    @Override
    public @NotNull NetworkDetails getDetails() {
        return this.details.get();
    }

    @Override
    public @NotNull ConcurrentMap<String, ConcurrentList<String>> getHeaders() {
        return this.headers.get();
    }

    @Override
    public @NotNull Request getRequest() {
        return this.request.get();
    }

    /**
     * Reads the buffered anchor body into a {@code byte[]} once at construction time so the
     * lazy {@link #body} initializer can decode UTF-8 from a captured array rather than
     * re-reading the anchor.
     *
     * @param anchor the buffered Feign response whose body bytes are captured
     * @return the captured body bytes, or an empty array if the body is {@code null} or
     *         cannot be read
     */
    private static byte @NotNull [] readAnchorBytes(@NotNull feign.Response anchor) {
        feign.Response.Body raw = anchor.body();

        if (raw == null)
            return new byte[0];

        try {
            return Util.toByteArray(raw.asInputStream());
        } catch (IOException ex) {
            return new byte[0];
        }
    }

    /**
     * Deserializes a JSON string into an instance of the specified class.
     * <p>
     * Returns {@code null} if the input is {@code null} or if a
     * {@link JsonSyntaxException} is thrown during deserialization, allowing
     * subclass error decoders to attempt parsing without risking an unhandled
     * exception.
     *
     * @param gson the Gson instance to use for deserialization
     * @param json the JSON string to deserialize, may be {@code null}
     * @param classOfT the target class to deserialize into
     * @param <T> the type of the desired object
     * @return the deserialized object, or {@code null} if deserialization fails
     */
    protected final @Nullable <T> T fromJson(@NotNull Gson gson, @Nullable String json, @NotNull Class<T> classOfT) throws JsonSyntaxException {
        try {
            return gson.fromJson(json, classOfT);
        } catch (JsonSyntaxException jsex) {
            return null;
        }
    }

}
