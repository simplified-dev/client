package dev.simplified.client.decoder;

import dev.simplified.client.Client;
import dev.simplified.client.cache.CachingFeignClient;
import dev.simplified.client.cache.ResponseCache;
import dev.simplified.client.exception.ApiDecodeException;
import dev.simplified.client.exception.ApiException;
import dev.simplified.client.exception.ErrorContext;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import dev.simplified.client.util.BodyBuffering;
import feign.FeignException;
import feign.MethodMetadata;
import feign.RequestTemplate;
import feign.Util;
import feign.codec.DecodeException;
import feign.codec.Decoder;
import feign.codec.DefaultDecoder;
import feign.codec.ErrorDecoder;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.function.Supplier;

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
 *       the stream is wrapped in a {@link Response.StreamingImpl} envelope providing access
 *       to {@link NetworkDetails}, status, and headers. In either case the envelope is not
 *       offered to {@link ResponseCache#store} because streaming bodies are not replayable,
 *       but the envelope is still passed to
 *       {@link ResponseCache#recordLastResponse(Response)} so observability callers see the
 *       latest exchange.</li>
 *   <li><b>{@code byte[]}</b> - delegates to Feign's {@link DefaultDecoder} which reads
 *       the entire response body into a byte array. The response body is closed after
 *       decoding.</li>
 *   <li><b>All other types</b> - delegates to the inner {@link Decoder} (typically a
 *       {@link feign.gson.GsonDecoder}) for JSON deserialization. The response body is
 *       buffered into a {@code byte[]} captured by the {@link Supplier} closure that
 *       drives {@link Response.Impl#getBody()}; the synthetic anchor consumed by the
 *       inner decoder is rebuilt lazily on first body access. The original response body
 *       is closed after buffering.</li>
 * </ul>
 * <p>
 * For non-streaming types, a {@link Response.Impl} envelope is built around the original
 * anchor (used for status / headers / request) plus a body supplier that closes over the
 * captured bytes; the typed body is materialized on demand via the envelope's
 * {@link Response.Impl#getBody()}. The envelope, the captured bytes and the headers of the
 * request Feign sent for it - the contract's headers and the client's configured ones - are
 * then offered to {@link ResponseCache#store}, which applies the RFC 7234 §3 storage predicate
 * and either stores the entry, as the variant for those request headers, or drops it. The
 * envelope is always passed to {@link ResponseCache#recordLastResponse(Response)} regardless
 * of caching decisions so that {@link Client#getLastResponse() Client.getLastResponse()}
 * observes every outcome.
 * <p>
 * Responses replayed from the cache via
 * {@link CachingFeignClient CachingFeignClient} flow through
 * this decoder normally; {@code ResponseCache.store} detects the
 * {@link ResponseCache#CACHE_HIT_HEADER} marker and skips re-storing them to avoid TTL
 * extension on cache hits.
 * <p>
 * If the declared return type is {@code Response<T>} (a parameterized type), the full
 * envelope is returned to the caller and body decoding is deferred until the caller invokes
 * {@link Response#getBody()}. Otherwise the body is materialized eagerly here so the
 * unwrapped object can be returned to Feign; decode failures surface as
 * {@link ApiDecodeException} on this synchronous path.
 * <p>
 * A response whose status code {@link HttpStatus} has no constant for - a {@code 2xx} such as
 * {@code 218}, since Feign hands every other class to its error decoder - is not decoded. It
 * is handed to the {@link InternalErrorDecoder}, which raises it as it raises an error status,
 * so the contract method raises the client's {@link ApiException} carrying the code as
 * {@link ApiException#getStatusCode()} and {@link HttpStatus#UNKNOWN_ERROR} as
 * {@link ApiException#getStatus()}. The exception is recorded as the last response, as an
 * error status's is, and nothing is stored for it. A decoder built without an error decoder
 * hands such a response to Feign's {@link ErrorDecoder.Default}, as a Feign builder given no
 * error decoder raises an error status.
 * <p>
 * A {@code void} return type decodes to {@code null} and records nothing, and a response to it
 * whose status code {@link HttpStatus} has no constant for raises as it does for any other
 * return type. The Feign builder hands this decoder a {@code void} return type only under
 * {@link feign.Feign.Builder#decodeVoid()}, which {@link Client} sets.
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

    /**
     * The inner decoder that performs JSON deserialization (e.g. Gson).
     */
    private final @NotNull Decoder delegate;

    /**
     * The decoder for binary response types ({@code byte[]}).
     */
    private final @NotNull Decoder binaryDecoder = new DefaultDecoder();

    /**
     * The shared response cache used for observability and RFC 7234 storage.
     */
    private final @NotNull ResponseCache responseCache;

    /**
     * The error decoder that raises a response whose status code {@link HttpStatus} has no
     * constant for - the client's {@link InternalErrorDecoder}, or Feign's
     * {@link ErrorDecoder.Default} for a decoder built without one.
     */
    private final @NotNull ErrorDecoder errorDecoder;

    /**
     * Constructs a new {@code InternalResponseDecoder} that hands a response whose status code
     * {@link HttpStatus} has no constant for to Feign's {@link ErrorDecoder.Default}.
     *
     * @param delegate the inner decoder that performs JSON deserialization
     * @param responseCache the shared response cache used for observability and storage
     */
    public InternalResponseDecoder(@NotNull Decoder delegate, @NotNull ResponseCache responseCache) {
        this(delegate, responseCache, new ErrorDecoder.Default());
    }

    /**
     * Constructs a new {@code InternalResponseDecoder} that hands a response whose status code
     * {@link HttpStatus} has no constant for to {@code errorDecoder}.
     *
     * @param delegate the inner decoder that performs JSON deserialization
     * @param responseCache the shared response cache used for observability and storage
     * @param errorDecoder the error decoder that raises a response whose status code
     *                     {@link HttpStatus} has no constant for, the client's
     *                     {@link InternalErrorDecoder}
     */
    public InternalResponseDecoder(
        @NotNull Decoder delegate,
        @NotNull ResponseCache responseCache,
        @NotNull ErrorDecoder errorDecoder
    ) {
        this.delegate = delegate;
        this.responseCache = responseCache;
        this.errorDecoder = errorDecoder;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object decode(@NotNull feign.Response feignResponse, @NotNull Type type) throws IOException, FeignException {
        if (HttpStatus.findByCode(feignResponse.status()).isEmpty()) {
            Exception raised = this.errorDecoder.decode(methodKey(feignResponse.request()), feignResponse);

            if (raised instanceof RuntimeException unchecked)
                throw unchecked;

            throw new DecodeException(feignResponse.status(), raised.getMessage(), feignResponse.request(), raised);
        }

        if (type == void.class || type == Void.class) {
            Util.ensureClosed(feignResponse.body());
            return null;
        }

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
                Response.StreamingImpl<InputStream> wrapped = new Response.StreamingImpl<>(feignResponse, stream);
                this.responseCache.recordLastResponse(wrapped);
                return wrapped;
            }

            return stream;
        }

        // Non-streaming: buffer the body, build a lazy envelope, then close the body
        try {
            byte[] bodyData;
            Supplier<Object> bodyDecoder;

            if (byte[].class.equals(bodyType)) {
                Object eagerBody = this.binaryDecoder.decode(feignResponse, bodyType);
                bodyData = eagerBody instanceof byte[] raw ? raw : new byte[0];
                bodyDecoder = () -> eagerBody;
            } else {
                bodyData = BodyBuffering.toByteArray(feignResponse.body(), feignResponse.headers());
                Type finalBodyType = bodyType;
                byte[] capturedBody = bodyData;
                bodyDecoder = () -> {
                    feign.Response synthetic = feignResponse.toBuilder().body(capturedBody).build();
                    try {
                        return this.delegate.decode(synthetic, finalBodyType);
                    } catch (IOException ioex) {
                        throw new UncheckedIOException(ioex);
                    } catch (FeignException fex) {
                        throw fex;
                    } catch (Exception ex) {
                        throw new ApiDecodeException(ex, ErrorContext.fromFeign(synthetic, capturedBody));
                    }
                };
            }

            Response.Impl<Object> response = new Response.Impl<>(feignResponse, bodyDecoder);
            this.responseCache.recordLastResponse(response);
            this.responseCache.store(response, bodyData, feignResponse.request().headers());

            if (shouldWrap)
                return response;

            try {
                return response.getBody();
            } catch (ApiDecodeException ex) {
                this.responseCache.recordLastResponse(ex);
                throw ex;
            }
        } finally {
            Util.ensureClosed(feignResponse.body());
        }
    }

    /**
     * Names the contract method a request was built from, as Feign names it to an error
     * decoder.
     *
     * @param request the request
     * @return the Feign config key of the request's contract method, or the request's method and
     *         URL for a request built without one
     */
    private static @NotNull String methodKey(@NotNull feign.Request request) {
        RequestTemplate template = request.requestTemplate();
        MethodMetadata endpoint = template != null ? template.methodMetadata() : null;

        if (endpoint != null)
            return endpoint.configKey();

        return request.httpMethod() + " " + request.url();
    }

}
