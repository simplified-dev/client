package dev.simplified.client.decoder;

import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.cache.CachingFeignClient;
import dev.simplified.client.cache.ResponseCache;
import dev.simplified.client.exception.ApiDecodeException;
import dev.simplified.client.exception.BodyCapExceededException;
import dev.simplified.client.exception.ErrorContext;
import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.response.HttpStatus;
import dev.simplified.client.response.NetworkDetails;
import dev.simplified.client.response.Response;
import dev.simplified.client.util.BodyBuffering;
import feign.FeignException;
import feign.Util;
import feign.codec.Decoder;
import feign.codec.DefaultDecoder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Decorating Feign {@link Decoder} that intercepts every successful response to capture
 * network metadata, hand the decoded envelope to {@link ResponseCache} for observability
 * and storage, and optionally wrap the decoded body in a {@link Response} envelope.
 * <p>
 * Decoding is routed by the declared return type of the Feign endpoint method:
 * <ul>
 *   <li><b>{@link InputStream}</b> - the raw response body stream is returned directly, or in
 *       a {@link Response.StreamingImpl} envelope providing access to {@link NetworkDetails},
 *       status, and headers when the return type is {@code Response<InputStream>}. The caller
 *       owns the stream lifecycle and must close it to release the underlying HTTP connection
 *       back to the pool. In either case a {@link Response.StreamingImpl} envelope around the
 *       stream is passed to {@link ResponseCache#recordLastResponse(Response)} so observability
 *       callers see the latest exchange, and it is not offered to {@link ResponseCache#store}
 *       because streaming bodies are not replayable.</li>
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
 * {@link Response.Impl#getBody()}. The envelope, the captured bytes and the headers the cache
 * keys the request by - {@link CachingFeignClient#keyHeaders(feign.Response)}: the contract's
 * headers and the fingerprint of each of the client's configured values - are then offered to
 * {@link ResponseCache#store}, which applies the RFC 7234 §3 storage predicate and either stores
 * the entry, as the variant for those request headers, or drops it. The
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
 * If the declared return type is {@code Response<T>} (a parameterized type), the envelope is
 * offered to the cache and returned to the caller, and body decoding is deferred until the
 * caller invokes {@link Response#getBody()}, so a caller reading only the status or headers
 * never decodes. Otherwise the body is materialized eagerly here so the unwrapped object can be
 * returned to Feign, and the envelope is offered to the cache only once its body has decoded;
 * decode failures surface as {@link ApiDecodeException} on this synchronous path.
 * <p>
 * A body that fails to decode, live or replayed and eagerly or on a deferred
 * {@link Response#getBody()}, is {@linkplain ResponseCache#discard discarded} from the cache
 * with every variant that would replay the same bytes to the same request, so the next request
 * reaches the origin rather than failing on the same bytes until the entry expires. The
 * failure is recognised where the body is decoded, so a stored body is never decoded only to
 * test it.
 * <p>
 * A response whose status code {@link HttpStatus} has no constant for - a {@code 2xx} such as
 * {@code 218}, since Feign hands every other class to its error decoder - is decoded as the
 * {@code x00} code of its class, {@link HttpStatus#OK} for a {@code 2xx}, as
 * <a href="https://www.rfc-editor.org/rfc/rfc9110#section-15">RFC 9110 §15</a> asks of a
 * recipient that does not recognise a status code. Its envelope reports that constant and is
 * returned and recorded as the envelope of a response carrying it is, on every return type, so
 * a {@code void} or {@link InputStream} contract method answered with such a code returns
 * normally. It is not offered to {@link ResponseCache#store}, since its envelope reports a code
 * the origin did not send and a replay would answer with it.
 * <p>
 * A {@code void} return type decodes to {@code null}. Its body is closed undecoded, and an envelope
 * carrying the exchange's status, headers and {@link NetworkDetails} over an empty
 * {@code byte[]} body is passed to {@link ResponseCache#recordLastResponse(Response)} and never
 * offered to {@link ResponseCache#store}. The Feign builder hands this decoder a {@code void}
 * return type only under {@link feign.Feign.Builder#decodeVoid()}, which {@link Client} sets.
 * <p>
 * A decoder built with a {@linkplain ClientConfig#maxBodyBytes body cap} holds every body it
 * reads to it, a body replayed from the cache as well as one read off the wire: the read stops one
 * byte past the cap and aborts the exchange rather than draining the rest of the body, and a body
 * larger than the cap raises {@link BodyCapExceededException}, which is recorded as the last
 * response, before anything is decoded or offered to {@link ResponseCache#store}. A cached entry
 * whose body the cap refuses stays cached. The body of a {@code void} return type is read no
 * further than the cap either before it is closed, and the method returns normally, since it
 * answers with no body. A streaming body is handed to the caller unread and is not held to the
 * cap.
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
     * The maximum size in bytes of a body this decoder reads, beyond which it raises
     * {@link BodyCapExceededException}, or {@link ClientConfig#DEFAULT_MAX_BODY_BYTES} to read
     * every body whole.
     */
    private final long maxBodyBytes;

    /**
     * Constructs a new {@code InternalResponseDecoder} over the given inner decoder and response
     * cache, reading every body whole.
     *
     * @param delegate the inner decoder that performs JSON deserialization
     * @param responseCache the shared response cache used for observability and storage
     */
    public InternalResponseDecoder(@NotNull Decoder delegate, @NotNull ResponseCache responseCache) {
        this(delegate, responseCache, ClientConfig.DEFAULT_MAX_BODY_BYTES);
    }

    /**
     * Constructs a new {@code InternalResponseDecoder} over the given inner decoder and response
     * cache, holding every body it reads to a cap.
     *
     * @param delegate the inner decoder that performs JSON deserialization
     * @param responseCache the shared response cache used for observability and storage
     * @param maxBodyBytes the maximum size in bytes of a body the decoder reads, or
     *                     {@link ClientConfig#DEFAULT_MAX_BODY_BYTES} to read every body whole
     */
    public InternalResponseDecoder(@NotNull Decoder delegate, @NotNull ResponseCache responseCache, long maxBodyBytes) {
        this.delegate = delegate;
        this.responseCache = responseCache;
        this.maxBodyBytes = maxBodyBytes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object decode(@NotNull feign.Response received, @NotNull Type type) throws IOException, FeignException {
        // RFC 9110 §15: an unrecognised status code is read as the x00 code of its class
        int status = received.status();
        boolean recognised = HttpStatus.findByCode(status).isPresent();
        feign.Response feignResponse = recognised ? received : received.toBuilder().status(status - status % 100).build();

        if (type == void.class || type == Void.class) {
            this.discard(feignResponse);
            this.responseCache.recordLastResponse(new Response.Impl<>(feignResponse, () -> new byte[0]));
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
            Response.StreamingImpl<InputStream> streaming = new Response.StreamingImpl<>(feignResponse, feignResponse.body().asInputStream());
            this.responseCache.recordLastResponse(streaming);
            return shouldWrap ? streaming : streaming.getBody();
        }

        // Non-streaming: buffer the body, build a lazy envelope, then close the body
        try {
            byte[] bodyData;
            Supplier<Object> bodyDecoder;

            if (byte[].class.equals(bodyType)) {
                feign.Response source = this.maxBodyBytes != ClientConfig.DEFAULT_MAX_BODY_BYTES && feignResponse.body() != null
                    ? feignResponse.toBuilder().body(this.read(feignResponse)).build()
                    : feignResponse;
                Object eagerBody = this.binaryDecoder.decode(source, bodyType);
                bodyData = eagerBody instanceof byte[] raw ? raw : new byte[0];
                bodyDecoder = () -> eagerBody;
            } else {
                bodyData = this.read(feignResponse);
                Type finalBodyType = bodyType;
                byte[] capturedBody = bodyData;
                bodyDecoder = () -> {
                    try {
                        return this.decodeBody(feignResponse, capturedBody, finalBodyType);
                    } catch (RuntimeException ex) {
                        feign.Request request = feignResponse.request();
                        this.responseCache.discard(HttpMethod.of(request.httpMethod().name()), request.url(), CachingFeignClient.keyHeaders(feignResponse), capturedBody);
                        throw ex;
                    }
                };
            }

            Response.Impl<Object> response = new Response.Impl<>(feignResponse, bodyDecoder);
            this.responseCache.recordLastResponse(response);

            if (shouldWrap) {
                if (recognised)
                    this.responseCache.store(response, bodyData, CachingFeignClient.keyHeaders(feignResponse));

                return response;
            }

            Object body;

            try {
                body = response.getBody();
            } catch (ApiDecodeException ex) {
                this.responseCache.recordLastResponse(ex);
                throw ex;
            }

            if (recognised)
                this.responseCache.store(response, bodyData, CachingFeignClient.keyHeaders(feignResponse));

            return body;
        } finally {
            Util.ensureClosed(feignResponse.body());
        }
    }

    /**
     * Reads a response's body, holding it to the decoder's cap.
     * <p>
     * A body that passes the cap is read no further, its exchange
     * {@linkplain BodyBuffering#toByteArray(feign.Response.Body, Map, long) aborted} rather than
     * drained, and is refused with a {@link BodyCapExceededException} carrying the response's
     * headers, which is recorded as the last response.
     *
     * @param feignResponse the response whose body is read
     * @return the body
     * @throws BodyCapExceededException if the body is larger than the cap
     * @throws IOException if reading the body fails
     */
    private byte @NotNull [] read(@NotNull feign.Response feignResponse) throws IOException {
        byte[] body = BodyBuffering.toByteArray(feignResponse.body(), feignResponse.headers(), this.maxBodyBytes);

        if (body.length <= this.maxBodyBytes)
            return body;

        feign.Request request = feignResponse.request();
        BodyCapExceededException refused = new BodyCapExceededException(
            HttpMethod.of(request.httpMethod().name()),
            request.url(),
            feignResponse.headers(),
            request.headers(),
            this.maxBodyBytes
        );
        this.responseCache.recordLastResponse(refused);
        throw refused;
    }

    /**
     * Closes the body of a response a {@code void} contract method answers with, which nothing
     * reads, reading no further than one byte past the decoder's cap first.
     * <p>
     * A body within the cap is read to its end, as closing it would drain it, and one that passes
     * the cap has its exchange aborted rather than drained. A decoder without a cap closes the body
     * unread. A failure reading the body is ignored, since the body is closed whether or not it
     * could be read.
     *
     * @param feignResponse the response whose body is closed
     */
    private void discard(@NotNull feign.Response feignResponse) {
        feign.Response.Body body = feignResponse.body();

        try {
            if (body != null && this.maxBodyBytes != ClientConfig.DEFAULT_MAX_BODY_BYTES)
                BodyBuffering.toByteArray(body, feignResponse.headers(), this.maxBodyBytes);
        } catch (IOException ignored) {
            // The body is unwanted; it is closed below whether or not it could be read.
        } finally {
            Util.ensureClosed(body);
        }
    }

    /**
     * Decodes buffered body bytes through the inner decoder.
     *
     * @param feignResponse the response the bytes were read from, supplying status, headers and
     *                      request
     * @param body the buffered body bytes
     * @param bodyType the type to decode the body into
     * @return the decoded body
     * @throws UncheckedIOException if the inner decoder fails reading the bytes
     * @throws FeignException if the inner decoder raises one
     * @throws ApiDecodeException if the inner decoder fails in any other way
     */
    private @Nullable Object decodeBody(@NotNull feign.Response feignResponse, byte @NotNull [] body, @NotNull Type bodyType) {
        feign.Response synthetic = feignResponse.toBuilder().body(body).build();

        try {
            return this.delegate.decode(synthetic, bodyType);
        } catch (IOException ioex) {
            throw new UncheckedIOException(ioex);
        } catch (FeignException fex) {
            throw fex;
        } catch (Exception ex) {
            throw new ApiDecodeException(ex, ErrorContext.fromFeign(synthetic, body));
        }
    }

}
