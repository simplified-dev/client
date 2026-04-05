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
import dev.simplified.util.StringUtil;
import feign.FeignException;
import lombok.AccessLevel;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
 * Instances are created by the {@link ClientErrorDecoder}
 * pipeline during Feign request processing.  Subclasses such as
 * {@link RateLimitException} add domain-specific metadata on top of the base
 * HTTP error information.
 * <p>
 * The {@link #response} field holds a parsed {@link ApiErrorResponse} whose
 * concrete type is determined by each {@link Client} subclass's
 * error decoder.  When JSON deserialization of the error body fails, a fallback
 * implementation returning the raw exception message is used instead.
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

    /** The raw response body, if one was present in the HTTP response. */
    private final @NotNull Optional<String> body;

    /** Network-level timing and connection details captured during the request lifecycle. */
    private final @NotNull NetworkDetails details;

    /** The HTTP response headers returned by the remote server. */
    private final @NotNull ConcurrentMap<String, ConcurrentList<String>> headers;

    /** The original HTTP request that triggered this exception. */
    private final @NotNull Request request;

    /** The original Feign request, retained for retry reconstruction in {@link RetryableApiException}. */
    @Getter(AccessLevel.PACKAGE)
    private final @NotNull feign.Request feignRequest;

    /** The structured error response parsed from the response body. */
    protected @NotNull ApiErrorResponse response;

    /** The number of retry attempts made before this exception was surfaced. */
    private int retryAttempts = 0;

    /**
     * Constructs an {@code ApiException} from a raw Feign response.
     * <p>
     * Internally converts the method key and response into a {@link FeignException}
     * via {@link FeignException#errorStatus(String, feign.Response)} and delegates
     * to {@link #ApiException(FeignException, feign.Response, String)}.
     *
     * @param methodKey the Feign method key identifying the endpoint that failed
     * @param response the raw Feign HTTP response
     * @param name a short name classifying this error type
     */
    public ApiException(@NotNull String methodKey, @NotNull feign.Response response, @NotNull String name) {
        this(FeignException.errorStatus(methodKey, response), response, name);
    }

    /**
     * Constructs an {@code ApiException} from a {@link FeignException} and its
     * associated raw response.
     * <p>
     * Extracts the HTTP status, response body, headers, network details, and
     * original request from the Feign exception.  The {@link #response} field
     * is initialized with a fallback {@link ApiErrorResponse} that returns the
     * exception message; subclass error decoders typically replace it with a
     * properly deserialized instance.
     *
     * @param exception the Feign exception wrapping the HTTP error
     * @param response the raw Feign HTTP response used to capture {@link NetworkDetails}
     * @param name a short name classifying this error type
     */
    public ApiException(@NotNull FeignException exception, @NotNull feign.Response response, @NotNull String name) {
        super(exception.getMessage(), exception.getCause(), true, true);
        this.name = name;
        this.status = HttpStatus.of(exception.status());
        this.body = exception.responseBody().map(byteBuffer -> StringUtil.toEncodedString(byteBuffer.array(), StandardCharsets.UTF_8));
        this.details = new NetworkDetails(response);
        this.headers = Response.getHeaders(exception.responseHeaders());
        this.response = exception::getMessage;
        this.feignRequest = exception.request();
        this.request = new Request.Impl(
            HttpMethod.of(exception.request().httpMethod().name()),
            exception.request().url()
        );
    }

    /**
     * Constructs an {@code ApiException} from a {@link FeignException} and its
     * associated raw response, with a pre-read body.
     * <p>
     * Used internally for failures that occur during response processing (e.g.
     * deserialization errors on successful HTTP responses) where the exception
     * does not carry the response body itself. The body is provided as a pre-read
     * string because the response stream may already be consumed.
     *
     * @param exception the Feign exception wrapping the failure
     * @param response the raw Feign HTTP response
     * @param name a short name classifying this error type
     * @param body the pre-read response body text
     */
    ApiException(@NotNull FeignException exception, @NotNull feign.Response response, @NotNull String name, @NotNull String body) {
        super(exception.getMessage(), exception.getCause(), true, true);
        this.name = name;
        this.status = HttpStatus.of(exception.status());
        this.body = Optional.of(body);
        this.details = new NetworkDetails(response);
        this.headers = Response.getHeaders(response.headers());
        this.response = exception::getMessage;
        this.feignRequest = exception.request();
        this.request = new Request.Impl(
            HttpMethod.of(exception.request().httpMethod().name()),
            exception.request().url()
        );
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
