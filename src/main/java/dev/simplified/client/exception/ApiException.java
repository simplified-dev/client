package dev.simplified.client.exception;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import dev.simplified.client.Client;
import dev.simplified.client.decoder.ClientErrorDecoder;
import dev.simplified.client.request.Request;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.util.Lazy;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Thrown when an HTTP request to a remote API fails.
 * <p>
 * {@code ApiException} extends {@link RuntimeException} and additionally implements
 * {@link Response}, making the full HTTP context (status, headers, body, network
 * details, and original request) available for inspection alongside the exception
 * itself. This dual nature allows callers to both catch the exception in standard
 * {@code try/catch} blocks and interrogate it as if it were a normal response
 * object.
 * <p>
 * Instances are created by the {@link ClientErrorDecoder} pipeline during Feign
 * request processing. Subclasses such as {@link RateLimitException} add
 * domain-specific metadata on top of the base HTTP error information.
 * <p>
 * The exception stores a single immutable {@link ErrorContext} record carrying only
 * non-feign primitives; {@link #getBody()}, {@link #getHeaders()}, and
 * {@link #getRequest()} are memoized via {@link Lazy} holders that close over the
 * context. Callers that only inspect {@link #getStatus()} or {@link #getDetails()}
 * pay zero allocation cost beyond the context reference.
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
public class ApiException extends RuntimeException implements Response<Optional<byte[]>> {

    /** Constant flag indicating this response represents an error. */
    private final boolean error = true;

    /** The short name identifying the type of API error (e.g. {@code "Client"}, {@code "RateLimit"}). */
    private final @NotNull String name;

    /** The primitive HTTP context bundle carrying status, network details, request method/url, headers, and body bytes. */
    private final @NotNull ErrorContext context;

    /** Memoized response body bytes, or {@link Optional#empty()} if the body was absent. */
    private final @NotNull Lazy<Optional<byte[]>> body;

    /** Memoized response headers (internal instrumentation headers excluded) derived from {@link #context}. */
    private final @NotNull Lazy<ConcurrentMap<String, ConcurrentList<String>>> headers;

    /** Memoized originating request derived from {@link #context}. */
    private final @NotNull Lazy<Request> request;

    /** The structured error response parsed from the response body. */
    protected @NotNull ApiErrorResponse response;

    /** The number of retry attempts made before this exception was surfaced. */
    private int retryAttempts = 0;

    /**
     * Constructs an {@code ApiException} with a writable stack trace.
     * <p>
     * The {@link #response} field is initialized with a fallback {@link ApiErrorResponse}
     * that returns the synthesized exception message; subclass error decoders typically
     * replace it with a properly deserialized instance. The body, headers, and originating
     * request are derived lazily from {@code context} on first access; the status and
     * network details delegate to the eagerly-built record fields.
     *
     * @param cause the underlying cause of the failure, or {@code null} if none
     * @param name a short name classifying this error type
     * @param context the primitive HTTP context bundle carrying status, headers, body, and request metadata
     */
    public ApiException(@Nullable Throwable cause, @NotNull String name, @NotNull ErrorContext context) {
        this(cause, name, context, true);
    }

    /**
     * Constructs an {@code ApiException} with control over whether a stack trace is captured.
     * <p>
     * Subclasses representing expected, high-frequency error conditions (e.g. rate
     * limiting) pass {@code false} for {@code writableStackTrace} to avoid the
     * per-instance cost of {@link Throwable#fillInStackTrace()}. The HTTP context
     * (status, headers, body, request URL, {@link NetworkDetails}) carried by
     * {@code ApiException} is sufficient for diagnosis in those cases. The detail
     * message is synthesized from {@code context} via
     * {@link #synthesizeMessage(ErrorContext)}.
     *
     * @param cause the underlying cause of the failure, or {@code null} if none
     * @param name a short name classifying this error type
     * @param context the primitive HTTP context bundle carrying status, headers, body, and request metadata
     * @param writableStackTrace whether this exception should capture a stack trace
     */
    protected ApiException(@Nullable Throwable cause, @NotNull String name, @NotNull ErrorContext context, boolean writableStackTrace) {
        this(cause, name, context, synthesizeMessage(context), writableStackTrace);
    }

    /**
     * Constructs an {@code ApiException} with a caller-supplied detail message.
     * <p>
     * Subclasses such as {@link UrlFetchException} that want their
     * own message format (e.g. {@code "Rate limit exceeded for bucket 'X' on URL 'Y'"})
     * delegate here instead of the synthesized-message constructor.
     *
     * @param cause the underlying cause of the failure, or {@code null} if none
     * @param name a short name classifying this error type
     * @param context the primitive HTTP context bundle carrying status, headers, body, and request metadata
     * @param message the pre-formatted detail message
     * @param writableStackTrace whether this exception should capture a stack trace
     */
    protected ApiException(
        @Nullable Throwable cause,
        @NotNull String name,
        @NotNull ErrorContext context,
        @NotNull String message,
        boolean writableStackTrace
    ) {
        super(message, cause, true, writableStackTrace);
        this.name = name;
        this.context = context;
        this.body = Lazy.of(() -> {
            byte[] bytes = context.bodyBytes();
            return bytes.length == 0 ? Optional.empty() : Optional.of(bytes);
        });
        this.headers = Lazy.of(() -> Response.getHeaders(context.responseHeaders()));
        this.request = Lazy.of(() -> new Request.Impl(context.requestMethod(), context.requestUrl()));
        this.response = super::getMessage;
    }

    @Override
    public @NotNull HttpStatus getStatus() {
        return this.context.status();
    }

    @Override
    public @NotNull NetworkDetails getDetails() {
        return this.context.details();
    }

    @Override
    public @NotNull Optional<byte[]> getBody() {
        return this.body.get();
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
     * Synthesizes the exception message from the request method, URL, and status.
     * <p>
     * Produces a diagnostic line of the form
     * {@code "GET https://api.example.com/v1/resource failed with status 404 Not Found"},
     * substituting the actual endpoint hit for the prior Feign contract-method format.
     *
     * @param context the primitive context whose fields drive the message
     * @return the synthesized message text
     */
    private static @NotNull String synthesizeMessage(@NotNull ErrorContext context) {
        return "%s %s failed with status %d %s".formatted(
            context.requestMethod(),
            context.requestUrl(),
            context.status().getCode(),
            context.status().getMessage()
        );
    }

    /**
     * Deserializes the response body bytes into an instance of the specified class via the
     * supplied {@link Gson}.
     * <p>
     * Reads the bytes from {@link #getBody()}, decodes them as UTF-8 into a streaming
     * {@link Reader}, and hands the reader to {@code gson} so the parse runs directly off
     * the captured byte array without an intermediate {@link String} allocation. Returns
     * {@link Optional#empty()} when the body is absent, when {@code gson} returns
     * {@code null}, or when deserialization throws a {@link JsonSyntaxException} or
     * {@link IOException} - allowing subclass error decoders to fall back to a stub
     * response without surfacing the parse failure.
     *
     * @param gson the Gson instance to use for deserialization
     * @param classOfT the target class to deserialize into
     * @param <T> the type of the desired object
     * @return the deserialized object, or {@link Optional#empty()} if the body is absent
     *         or deserialization fails
     */
    protected final @NotNull <T> Optional<T> fromJson(@NotNull Gson gson, @NotNull Class<T> classOfT) {
        return this.getBody().flatMap(bytes -> {
            try (Reader reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
                return Optional.ofNullable(gson.fromJson(reader, classOfT));
            } catch (IOException | JsonSyntaxException ex) {
                return Optional.empty();
            }
        });
    }

}
