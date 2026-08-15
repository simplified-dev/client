package dev.simplified.client.exception;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.Lazy;
import dev.simplified.client.Client;
import dev.simplified.client.decoder.ClientErrorDecoder;
import dev.simplified.client.request.Request;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Supplier;

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
 * non-feign primitives. {@link #getStatus()}, {@link #getBody()}, and {@link #getRequest()}
 * resolve via eagerly-populated fields, the underlying allocations being cheaper than
 * deferring them would be. {@link #getDetails()} and {@link #getHeaders()} are computed on
 * first read and memoized, because both do non-trivial work
 * ({@code Instant} parsing for {@link NetworkDetails}, {@code TreeMap} construction +
 * filter + {@code Concurrent} wrappers for the cleaned headers) that the dominant
 * status-only access pattern can skip entirely.
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

    /**
     * Constant flag indicating this response represents an error.
     */
    private final boolean error = true;

    /**
     * The short name identifying the type of API error (e.g. {@code "Client"}, {@code "RateLimit"}).
     */
    private final @NotNull String name;

    /**
     * The primitive HTTP context bundle carrying status, network details, request method/url, headers, and body bytes.
     */
    private final @NotNull ErrorContext context;

    /**
     * The response body bytes, or {@link Optional#empty()} if the body was absent.
     */
    private final @NotNull Optional<byte[]> body;

    /**
     * The originating request, eagerly wrapped around {@code context}'s method and URL.
     */
    private final @NotNull Request request;

    /**
     * Network timing and TLS metadata, built from {@code context}'s header maps on first read.
     */
    @Lazy
    private final @NotNull NetworkDetails details;

    /**
     * Response headers, internal instrumentation entries excluded, derived from {@link #context} on first read.
     */
    @Lazy
    private final @NotNull ConcurrentMap<String, ConcurrentList<String>> headers;

    /**
     * The structured error response parsed from the response body.
     */
    protected @NotNull ApiErrorResponse response;

    /**
     * The number of retry attempts made before this exception was surfaced.
     */
    private int retryAttempts = 0;

    /**
     * Constructs an {@code ApiException} with a writable stack trace.
     * <p>
     * The {@link #response} field is initialized with a fallback {@link ApiErrorResponse}
     * that returns the synthesized exception message; subclass error decoders typically
     * replace it with a properly deserialized instance. The status, body, and originating
     * request are populated eagerly from {@code context}; the headers and network details
     * are deferred until first access.
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
        this(
            cause,
            name,
            context,
            () -> new NetworkDetails(context.responseHeaders(), context.requestHeaders()),
            message,
            writableStackTrace
        );
    }

    /**
     * Constructs an {@code ApiException} with a caller-supplied detail message and a pre-built
     * {@link NetworkDetails}, bypassing the header-map lazy build.
     * <p>
     * Used when the caller obtains timing metadata from a non-feign source - typically Apache's
     * {@link HttpContext} via the standalone {@code UrlFetcher} - so
     * the timing data is preserved without round-tripping through the request-headers map.
     * The supplied {@code NetworkDetails} shares the deferred lookup path with the header-map
     * case, so the first {@link #getDetails()} call returns it directly with no parsing.
     *
     * @param cause the underlying cause of the failure, or {@code null} if none
     * @param name a short name classifying this error type
     * @param context the primitive HTTP context bundle carrying status, headers, body, and request metadata
     * @param prebuiltDetails a pre-resolved network timing snapshot
     * @param message the pre-formatted detail message
     * @param writableStackTrace whether this exception should capture a stack trace
     */
    protected ApiException(
        @Nullable Throwable cause,
        @NotNull String name,
        @NotNull ErrorContext context,
        @NotNull NetworkDetails prebuiltDetails,
        @NotNull String message,
        boolean writableStackTrace
    ) {
        this(cause, name, context, () -> prebuiltDetails, message, writableStackTrace);
    }

    /**
     * Canonical constructor that all sibling forms delegate to.
     *
     * @param cause the underlying cause of the failure, or {@code null} if none
     * @param name a short name classifying this error type
     * @param context the primitive HTTP context bundle
     * @param details supplies the value behind {@link #getDetails()} on its first read
     * @param message the pre-formatted detail message
     * @param writableStackTrace whether this exception should capture a stack trace
     */
    private ApiException(
        @Nullable Throwable cause,
        @NotNull String name,
        @NotNull ErrorContext context,
        @NotNull Supplier<NetworkDetails> details,
        @NotNull String message,
        boolean writableStackTrace
    ) {
        super(message, cause, true, writableStackTrace);
        this.name = name;
        this.context = context;
        byte[] bytes = context.bodyBytes();
        this.body = bytes.length == 0 ? Optional.empty() : Optional.of(bytes);
        this.request = new Request.Impl(context.requestMethod(), context.requestUrl());
        this.details = details.get();
        this.headers = Response.getHeaders(context.responseHeaders());
        this.response = super::getMessage;
    }

    @Override
    public @NotNull HttpStatus getStatus() {
        return this.context.status();
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
