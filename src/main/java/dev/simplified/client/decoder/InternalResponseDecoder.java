package dev.simplified.client.decoder;

import dev.simplified.client.Client;
import dev.simplified.client.cache.ResponseCache;
import dev.simplified.client.exception.ApiDecodeException;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.request.Request;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import feign.FeignException;
import feign.Util;
import feign.codec.Decoder;
import feign.codec.DefaultDecoder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;

/**
 * Decorating Feign {@link Decoder} that intercepts every successful response to capture
 * network metadata, hand the decoded envelope to {@link ResponseCache} for observability
 * and storage, and optionally wrap the decoded body in a {@link Response} envelope.
 * <p>
 * Decoding is routed by the declared return type of the Feign endpoint method:
 * <ul>
 *   <li><b>{@link InputStream}</b> - the raw response body stream is returned directly.
 *       The caller owns the stream lifecycle and must close it to release the underlying
 *       HTTP connection back to the pool. If the return type is {@code Response<InputStream>},
 *       the stream is wrapped in a {@link Response} envelope providing access to
 *       {@link NetworkDetails}, {@link HttpStatus}, and headers. In either case the
 *       envelope is not offered to {@link ResponseCache#store(Response.Impl)} because
 *       streaming bodies are not replayable, but the envelope is still passed to
 *       {@link ResponseCache#recordLastResponse(Response)} so observability callers see
 *       the latest exchange.</li>
 *   <li><b>{@code byte[]}</b> - delegates to Feign's {@link DefaultDecoder} which reads
 *       the entire response body into a byte array. The response body is closed after
 *       decoding.</li>
 *   <li><b>All other types</b> - delegates to the inner {@link Decoder} (typically a
 *       {@link feign.gson.GsonDecoder}) for JSON deserialization. The response body is
 *       closed after decoding.</li>
 * </ul>
 * <p>
 * For non-streaming types, a {@link Response.Impl} envelope is built carrying the decoded
 * body, {@link HttpStatus}, {@link NetworkDetails}, request information, response headers,
 * and the raw wire bytes. The envelope is then offered to
 * {@link ResponseCache#store(Response.Impl)}, which applies the RFC 7234 §3 storage
 * predicate and either caches the envelope or drops it. The envelope is always passed to
 * {@link ResponseCache#recordLastResponse(Response)} regardless of caching decisions so
 * that {@link dev.simplified.client.Client#getLastResponse() Client.getLastResponse()}
 * observes every outcome.
 * <p>
 * Responses replayed from the cache via
 * {@link dev.simplified.client.cache.CachingFeignClient CachingFeignClient} flow through
 * this decoder normally; {@code ResponseCache.store} detects the
 * {@link ResponseCache#CACHE_HIT_HEADER} marker and skips re-storing them to avoid TTL
 * extension on cache hits.
 * <p>
 * If the declared return type is {@code Response<T>} (a parameterized type), the full
 * envelope is returned to the caller. Otherwise only the unwrapped body object is
 * returned, keeping the {@link Response} metadata available solely through
 * {@link Client#getLastResponse()}.
 * <p>
 * This decoder requires {@link feign.Feign.Builder#doNotCloseAfterDecode()} to be set
 * on the Feign builder so that {@link InputStream} responses are not prematurely closed
 * by Feign's default post-decode cleanup. For non-streaming types, this decoder closes
 * the response body itself in a {@code finally} block.
 * <p>
 * This class is instantiated internally by {@link Client} during Feign
 * builder configuration and is not intended for direct use by application code.
 *
 * @see Client#getLastResponse()
 * @see ResponseCache
 * @see NetworkDetails
 * @see Response
 */
public final class InternalResponseDecoder implements Decoder {

    /** The inner decoder that performs JSON deserialization (e.g. Gson). */
    private final @NotNull Decoder delegate;

    /** The decoder for binary response types ({@code byte[]}). */
    private final @NotNull Decoder binaryDecoder = new DefaultDecoder();

    /** The shared response cache used for observability and RFC 7234 storage. */
    private final @NotNull ResponseCache responseCache;

    /**
     * Constructs a new internal response decoder.
     *
     * @param delegate the inner decoder to which JSON-to-object conversion is delegated
     * @param responseCache the shared response cache for observability and storage
     */
    public InternalResponseDecoder(@NotNull Decoder delegate, @NotNull ResponseCache responseCache) {
        this.delegate = delegate;
        this.responseCache = responseCache;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object decode(@NotNull feign.Response feignResponse, @NotNull Type type) throws IOException, FeignException {
        Type bodyType = type;
        boolean shouldWrap = false;

        if (type instanceof ParameterizedType parameterizedType) {
            if (parameterizedType.getRawType().equals(Response.class)) {
                shouldWrap = true;
                bodyType = parameterizedType.getActualTypeArguments()[0];
            }
        }

        // Streaming: caller owns lifecycle, not cached
        if (InputStream.class.equals(bodyType)) {
            InputStream stream = feignResponse.body().asInputStream();

            if (shouldWrap) {
                Response<?> wrapped = this.buildResponse(feignResponse, stream, null);
                this.responseCache.recordLastResponse(wrapped);
                return wrapped;
            }

            return stream;
        }

        // Non-streaming: decode body, then close it
        try {
            Object decodedBody;
            byte[] bodyData = null;

            if (byte[].class.equals(bodyType)) {
                decodedBody = this.binaryDecoder.decode(feignResponse, bodyType);

                if (decodedBody instanceof byte[] raw)
                    bodyData = raw;
            } else {
                bodyData = Util.toByteArray(feignResponse.body().asInputStream());
                feign.Response bufferedResponse = feignResponse.toBuilder().body(bodyData).build();

                try {
                    decodedBody = this.delegate.decode(bufferedResponse, bodyType);
                } catch (Exception ex) {
                    ApiDecodeException error = new ApiDecodeException(ex, feignResponse, new String(bodyData, StandardCharsets.UTF_8));
                    this.responseCache.recordLastResponse(error);
                    throw error;
                }
            }

            Response.Impl<?> response = this.buildResponse(feignResponse, decodedBody, bodyData);
            this.responseCache.recordLastResponse(response);
            this.responseCache.store(response);
            return shouldWrap ? response : decodedBody;
        } finally {
            Util.ensureClosed(feignResponse.body());
        }
    }

    /**
     * Constructs a {@link Response.Impl} envelope from a raw Feign response, a decoded body,
     * and optionally the raw wire bytes.
     *
     * @param feignResponse the raw Feign HTTP response containing status, headers, and request info
     * @param body the deserialized body object produced by the delegate decoder
     * @param rawBody the raw response bytes captured at decode time, or {@code null} for
     *                streaming responses
     * @return a new {@link Response.Impl} instance bundling the body with network and HTTP metadata
     */
    private @NotNull Response.Impl<?> buildResponse(@NotNull feign.Response feignResponse, Object body, byte @Nullable [] rawBody) {
        return new Response.Impl<>(
            body,
            new NetworkDetails(feignResponse),
            HttpStatus.of(feignResponse.status()),
            new Request.Impl(
                HttpMethod.of(feignResponse.request().httpMethod().name()),
                feignResponse.request().url()
            ),
            Response.getHeaders(feignResponse.headers()),
            rawBody
        );
    }

}
