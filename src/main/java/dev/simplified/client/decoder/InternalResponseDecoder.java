package dev.simplified.client.decoder;

import dev.simplified.client.Client;
import dev.simplified.client.cache.ResponseCache;
import dev.simplified.client.exception.ApiDecodeException;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import feign.FeignException;
import feign.Util;
import feign.codec.Decoder;
import feign.codec.DefaultDecoder;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.function.Function;

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
 *       offered to {@link ResponseCache#store(Response.Impl)} because streaming bodies are
 *       not replayable, but the envelope is still passed to
 *       {@link ResponseCache#recordLastResponse(Response)} so observability callers see
 *       the latest exchange.</li>
 *   <li><b>{@code byte[]}</b> - delegates to Feign's {@link DefaultDecoder} which reads
 *       the entire response body into a byte array. The response body is closed after
 *       decoding.</li>
 *   <li><b>All other types</b> - delegates to the inner {@link Decoder} (typically a
 *       {@link feign.gson.GsonDecoder}) for JSON deserialization. The response body is
 *       buffered into a {@code byte[]} that backs both the lazy decode driving
 *       {@link Response.Impl#getBody()} and the {@link Response.Impl#getRawBody()} bytes
 *       view, and is closed after decoding.</li>
 * </ul>
 * <p>
 * For non-streaming types, a {@link Response.Impl} envelope is built carrying the buffered
 * anchor and a body decoder closure; the typed body is materialized on demand via the
 * envelope's {@link Response.Impl#getBody()}. The envelope is then offered to
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
 * envelope is returned to the caller and body decoding is deferred until the caller invokes
 * {@link Response#getBody()}. Otherwise the body is materialized eagerly here so the
 * unwrapped object can be returned to Feign; decode failures surface as
 * {@link ApiDecodeException} on this synchronous path.
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
                Response.StreamingImpl<InputStream> wrapped = new Response.StreamingImpl<>(feignResponse, stream);
                this.responseCache.recordLastResponse(wrapped);
                return wrapped;
            }

            return stream;
        }

        // Non-streaming: buffer the body, build a lazy envelope, then close the body
        try {
            feign.Response bufferedResponse;
            Function<feign.Response, Object> bodyDecoder;

            if (byte[].class.equals(bodyType)) {
                Object eagerBody = this.binaryDecoder.decode(feignResponse, bodyType);
                byte[] bodyData = eagerBody instanceof byte[] raw ? raw : new byte[0];
                bufferedResponse = feignResponse.toBuilder().body(bodyData).build();
                bodyDecoder = ignored -> eagerBody;
            } else {
                byte[] bodyData = Util.toByteArray(feignResponse.body().asInputStream());
                feignResponse.body().asInputStream();
                bufferedResponse = feignResponse.toBuilder().body(bodyData).build();
                Type finalBodyType = bodyType;
                bodyDecoder = anchor -> {
                    try {
                        return this.delegate.decode(anchor, finalBodyType);
                    } catch (IOException ioex) {
                        throw new UncheckedIOException(ioex);
                    } catch (FeignException fex) {
                        throw fex;
                    } catch (Exception ex) {
                        throw new ApiDecodeException(ex, anchor);
                    }
                };
            }

            Response.Impl<Object> response = new Response.Impl<>(bufferedResponse, bodyDecoder);
            this.responseCache.recordLastResponse(response);
            this.responseCache.store(response);

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

}
