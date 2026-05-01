package dev.simplified.client.response;

import dev.simplified.client.request.HttpMethod;
import dev.simplified.client.request.Request;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.util.Lazy;
import lombok.AccessLevel;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Top-level interface representing a fully-resolved HTTP response, providing typed access
 * to the deserialized body, status code, headers, network details, and originating request.
 * <p>
 * Implementations carry the complete response lifecycle: the {@link Request} that initiated
 * the call, the {@link HttpStatus} returned by the server, the response headers (with
 * internal instrumentation headers stripped), and a {@link NetworkDetails} snapshot
 * capturing timing and TLS metadata. Convenience methods such as
 * {@link #getCloudflareCacheStatus()} and {@link #isError()} provide quick access to
 * commonly needed response characteristics.
 * <p>
 * Three default implementations are provided as nested classes: {@link Impl} carries a
 * lazily-decoded body driven by a caller-supplied {@link Supplier}, {@link StreamingImpl}
 * wraps a streaming body whose decode runs eagerly while metadata stays lazy, and
 * {@link CachedImpl} extends {@code Impl} with behavioral accessors that compute RFC 7234 cache
 * semantics (freshness, age, revalidation capability) from the inherited headers and
 * {@link NetworkDetails}. {@code Cached} adds no new fields - every cache-specific value is
 * derived on demand.
 *
 * @param <T> the deserialized type of the response body
 * @see Request
 * @see HttpStatus
 * @see NetworkDetails
 */
public interface Response<T> {

    /**
     * Retrieves the Cloudflare CDN cache status from the {@code CF-Cache-Status} response
     * header.
     * <p>
     * Looks up the {@link CloudflareCacheStatus#HEADER_KEY} in the response headers and
     * resolves its value to a {@link CloudflareCacheStatus} constant. If the header is
     * missing or contains an unrecognized value, {@link CloudflareCacheStatus#UNKNOWN}
     * is returned.
     *
     * @return the {@link CloudflareCacheStatus} indicating the caching disposition of this response
     */
    default @NotNull CloudflareCacheStatus getCloudflareCacheStatus() {
        return this.getHeaders()
            .getOptional(CloudflareCacheStatus.HEADER_KEY)
            .flatMap(ConcurrentList::findFirst)
            .map(CloudflareCacheStatus::of)
            .orElse(CloudflareCacheStatus.UNKNOWN);
    }

    /**
     * Retrieves the parsed {@link ETag} from the {@code ETag} response header.
     * <p>
     * Looks up {@link ETag#HEADER_KEY} in the response headers and delegates the string
     * value to {@link ETag#parse(String)}, which handles strong and weak ({@code W/})
     * validators and rejects malformed input. Header lookup is case-insensitive because
     * {@link #getHeaders(Map)} collects into a map ordered by
     * {@link String#CASE_INSENSITIVE_ORDER}.
     *
     * @return the parsed entity tag if the response carries a valid {@code ETag} header,
     *         otherwise {@link Optional#empty()}
     * @see ETag
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc7232#section-2.3">RFC 7232 Section 2.3</a>
     */
    default @NotNull Optional<ETag> getETag() {
        return this.getHeaders()
            .getOptional(ETag.HEADER_KEY)
            .flatMap(ConcurrentList::findFirst)
            .flatMap(ETag::parse);
    }

    /** The deserialized body of the HTTP response, decoded into the parameterized type {@code T}. */
    @NotNull T getBody();

    /** The network-level timing and TLS metadata captured during the request/response cycle. */
    @NotNull NetworkDetails getDetails();

    /**
     * The response headers as an unmodifiable concurrent map, excluding internal
     * instrumentation headers (those prefixed with {@code X-Internal-}).
     */
    @NotNull ConcurrentMap<String, ConcurrentList<String>> getHeaders();

    /** The originating {@link Request} that produced this response. */
    @NotNull Request getRequest();

    /** The {@link HttpStatus} of the response, indicating the numeric code and its classification. */
    @NotNull HttpStatus getStatus();

    /**
     * Determines whether this response represents an error condition.
     * <p>
     * Delegates to {@link HttpState#isError()} on the state associated with this
     * response's {@link HttpStatus}.
     *
     * @return {@code true} if the response status belongs to an error state;
     *         {@code false} otherwise
     */
    default boolean isError() {
        return this.getStatus().getState().isError();
    }

    /**
     * Converts a standard {@link Map} of header names to value collections into an
     * unmodifiable {@link ConcurrentMap}, filtering out empty entries and internal
     * instrumentation headers.
     * <p>
     * Internal headers (identified by {@link NetworkDetails#isInternalHeader(String)})
     * are excluded because they carry interceptor-injected timing data that is not part
     * of the actual HTTP response. The returned map uses
     * {@link String#CASE_INSENSITIVE_ORDER} for key comparison, matching HTTP's
     * case-insensitive header semantics so lookups like {@code getOptional("etag")} and
     * {@code getOptional("ETag")} both succeed.
     *
     * @param headers the raw response headers map to convert
     * @return an unmodifiable case-insensitive {@link ConcurrentMap} of header names to
     *         unmodifiable {@link ConcurrentList} value lists, with internal headers removed
     */
    static @NotNull ConcurrentMap<String, ConcurrentList<String>> getHeaders(@NotNull Map<String, Collection<String>> headers) {
        TreeMap<String, ConcurrentList<String>> sorted = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        for (Map.Entry<String, Collection<String>> entry : headers.entrySet()) {
            Collection<String> values = entry.getValue();

            if (values.isEmpty())
                continue;

            String key = entry.getKey();

            if (NetworkDetails.isInternalHeader(key))
                continue;

            sorted.put(key, Concurrent.newUnmodifiableList(values));
        }

        return Concurrent.newUnmodifiableTreeMap(String.CASE_INSENSITIVE_ORDER, sorted);
    }

    /**
     * Lazy buffered-body implementation of {@link Response} anchored on a
     * {@link feign.Response} that supplies metadata (status, headers, request) while the
     * decoded body is materialized on demand by a caller-supplied {@link Supplier}.
     * <p>
     * The decoded body, network details, headers, and originating request are all derived
     * on demand via memoizing {@link Lazy} holders. Callers that only read
     * {@link #getStatus()} pay zero decode cost; callers that read {@link #getBody()} drive
     * the supplied {@link Supplier} exactly once to materialize the typed body. The body
     * supplier closes over any bytes it needs, so the anchor's body need not be readable
     * after construction.
     *
     * @param <T> the deserialized type of the response body
     */
    @Getter
    class Impl<T> implements Response<T> {

        /** The buffered Feign response that anchors every lazily-derived field. */
        private final @NotNull feign.Response anchor;

        /** The HTTP status code and classification, computed eagerly from {@link #anchor}. */
        private final @NotNull HttpStatus status;

        /** The decoder driving {@link #body} on first access; closes over the call site's body bytes and codec. */
        @Getter(AccessLevel.NONE)
        private final @NotNull Supplier<T> bodyDecoder;

        /** Memoized decoded body, materialized on the first call to {@link #getBody()}. */
        @Getter(AccessLevel.NONE)
        private final @NotNull Lazy<T> body;

        /** Memoized network timing and TLS metadata derived from {@link #anchor}. */
        private final @NotNull Lazy<NetworkDetails> details;

        /** Memoized response headers (internal instrumentation headers excluded) derived from {@link #anchor}. */
        private final @NotNull Lazy<ConcurrentMap<String, ConcurrentList<String>>> headers;

        /** Memoized originating request derived from {@link #anchor}. */
        private final @NotNull Lazy<Request> request;

        /**
         * Constructs a lazy buffered response.
         *
         * @param anchor the Feign response supplying metadata (status, headers, request) for
         *               this envelope; its body need not be readable
         * @param bodyDecoder the supplier that materializes the typed body on first access,
         *                    typically closing over previously-buffered body bytes
         */
        public Impl(@NotNull feign.Response anchor, @NotNull Supplier<T> bodyDecoder) {
            this.anchor = anchor;
            this.bodyDecoder = bodyDecoder;
            this.status = HttpStatus.of(anchor.status());
            this.body = Lazy.of(this.bodyDecoder);
            this.details = Lazy.of(() -> new NetworkDetails(this.anchor));
            this.headers = Lazy.of(() -> Response.getHeaders(this.anchor.headers()));
            this.request = Lazy.of(() -> new Request.Impl(
                HttpMethod.of(this.anchor.request().httpMethod().name()),
                this.anchor.request().url()
            ));
        }

        @Override
        public @NotNull T getBody() {
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
         * Builds a new {@code Impl} that swaps the underlying anchor for {@code newAnchor}
         * while preserving the original body decoder.
         * <p>
         * Used by the response cache to rebuild a sanitized envelope (e.g. with hop-by-hop
         * headers stripped, or with {@code 304}-merged headers) without forcing the lazy
         * decoder to run. The returned envelope drops every memoized field on the source
         * instance; subsequent reads start fresh against {@code newAnchor}. The shared
         * {@link Supplier} reference means body bytes captured in its closure follow the
         * helper through.
         *
         * @param newAnchor the replacement anchor whose headers, status, and request drive
         *                  the returned envelope
         * @return a new {@code Impl} sharing the source decoder but anchored on {@code newAnchor}
         */
        public @NotNull Impl<T> withAnchor(@NotNull feign.Response newAnchor) {
            return new Impl<>(newAnchor, this.bodyDecoder);
        }

    }

    /**
     * Streaming-body implementation of {@link Response} that holds an eagerly-decoded body
     * alongside lazy metadata.
     * <p>
     * Streaming endpoints (e.g. {@link java.io.InputStream}-returning Feign methods) cannot
     * defer body materialization because their wire body is consumed as a live stream rather
     * than a buffered {@code byte[]}. The body is therefore stored as a direct field; the
     * status, headers, and originating request are still derived lazily from the anchor so
     * that callers reading only {@link #getStatus()} skip the headers / request allocations.
     * Streaming responses are not cacheable - the cache layer never receives a streaming
     * envelope.
     *
     * @param <T> the deserialized type of the streaming body (typically {@link java.io.InputStream})
     */
    @Getter
    final class StreamingImpl<T> implements Response<T> {

        /** The Feign response that anchors the lazily-derived metadata fields. */
        private final @NotNull feign.Response anchor;

        /** The HTTP status code and classification, computed eagerly from {@link #anchor}. */
        private final @NotNull HttpStatus status;

        /** The eagerly-supplied streaming body. */
        private final @NotNull T body;

        /** Memoized network timing and TLS metadata derived from {@link #anchor}. */
        private final @NotNull Lazy<NetworkDetails> details;

        /** Memoized response headers (internal instrumentation headers excluded) derived from {@link #anchor}. */
        private final @NotNull Lazy<ConcurrentMap<String, ConcurrentList<String>>> headers;

        /** Memoized originating request derived from {@link #anchor}. */
        private final @NotNull Lazy<Request> request;

        /**
         * Constructs a streaming response.
         *
         * @param anchor the Feign response carrying the live stream and metadata headers
         * @param body the already-resolved streaming body (caller owns the lifecycle)
         */
        public StreamingImpl(@NotNull feign.Response anchor, @NotNull T body) {
            this.anchor = anchor;
            this.body = body;
            this.status = HttpStatus.of(anchor.status());
            this.details = Lazy.of(() -> new NetworkDetails(this.anchor));
            this.headers = Lazy.of(() -> Response.getHeaders(this.anchor.headers()));
            this.request = Lazy.of(() -> new Request.Impl(
                HttpMethod.of(this.anchor.request().httpMethod().name()),
                this.anchor.request().url()
            ));
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

    }

    /**
     * A {@link Response.Impl} augmented with RFC 7234 cache semantics.
     * <p>
     * {@code Cached} holds <b>no additional state</b> of its own - every cache-specific value
     * is derived on demand from the inherited {@link NetworkDetails} (request / response
     * timings via {@link NetworkDetails#getRoundTrip()}) and the inherited response
     * headers ({@code Cache-Control}, {@code Expires}, {@code Date}, {@code Age},
     * {@code Vary}, {@code Last-Modified}). Used as the response side of
     * {@code ResponseCache}'s entry tuple, where the cached entry is both a first-class
     * {@link Response} (status, headers, body) and a carrier of freshness and revalidation
     * logic; the body bytes for replay live alongside in the cache's storage tuple.
     * <p>
     * Because directive parsing and header lookups are cheap (a handful of microseconds),
     * storing parsed fields on the entry would offer no meaningful performance benefit over
     * re-deriving them on each cache operation, while adding duplicated state and a risk of
     * drift from the authoritative headers. The inherited {@link Response.Impl} fields are
     * the single source of truth.
     * <p>
     * Streaming responses cannot be cached - {@code Cached} extends only {@link Impl} and
     * never {@link StreamingImpl}, mirroring the storage contract that requires buffered
     * body bytes alongside the entry.
     *
     * @param <T> the deserialized type of the response body
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc7234">RFC 7234 - HTTP/1.1 Caching</a>
     */
    final class CachedImpl<T> extends Impl<T> {

        /**
         * Constructs a cached view over an anchor and body supplier, using the same fields
         * that drive {@link Impl}.
         *
         * @param anchor the Feign response supplying cached headers and metadata
         * @param bodyDecoder the supplier that materializes the typed body on demand
         */
        private CachedImpl(@NotNull feign.Response anchor, @NotNull Supplier<T> bodyDecoder) {
            super(anchor, bodyDecoder);
        }

        /**
         * Builds a {@code Cached} view over an existing decoded response, reusing its anchor
         * and decoder rather than copying any materialized state.
         * <p>
         * Any laziness in {@code source} is preserved - a cached entry that was never
         * {@link #getBody()}-ed defers its decode until the next reader, including the cache
         * replay path.
         *
         * @param source the source response to wrap
         * @param <U> the deserialized body type
         * @return a new {@code Cached} instance sharing {@code source}'s anchor and decoder
         */
        public static <U> @NotNull CachedImpl<U> from(@NotNull Impl<U> source) {
            return new CachedImpl<>(source.anchor, source.bodyDecoder);
        }

        /**
         * Parsed {@code Cache-Control} directives for this response, re-computed from the
         * inherited headers on each call.
         *
         * @return the parsed directives, or {@link CacheControl#EMPTY} if the header is
         *         absent
         */
        public @NotNull CacheControl cacheControl() {
            return CacheControl.parseFromHeaders(this.getHeaders());
        }

        /**
         * Computes this response's freshness lifetime per
         * <a href="https://datatracker.ietf.org/doc/html/rfc7234#section-4.2.1">RFC 7234
         * Section 4.2.1</a>.
         * <p>
         * Priority order: {@code s-maxage} > {@code max-age} > ({@code Expires} -
         * {@code Date}) > {@link Duration#ZERO}. Heuristic freshness (§4.2.2) is
         * deliberately not implemented; responses with no explicit freshness information
         * are treated as stale on arrival and always force revalidation.
         *
         * @return the freshness lifetime, or {@link Duration#ZERO} if no freshness
         *         information is present
         */
        public @NotNull Duration freshnessLifetime() {
            CacheControl cc = this.cacheControl();

            if (cc.sMaxAge().isPresent())
                return Duration.ofSeconds(cc.sMaxAge().getAsLong());

            if (cc.maxAge().isPresent())
                return Duration.ofSeconds(cc.maxAge().getAsLong());

            Optional<Instant> expires = HttpDates.parseFromHeaders(this.getHeaders(), "Expires");
            Optional<Instant> date = HttpDates.parseFromHeaders(this.getHeaders(), "Date");

            if (expires.isPresent() && date.isPresent()) {
                Duration delta = Duration.between(date.get(), expires.get());
                return delta.isNegative() ? Duration.ZERO : delta;
            }

            return Duration.ZERO;
        }

        /**
         * Computes this response's current age per
         * <a href="https://datatracker.ietf.org/doc/html/rfc7234#section-4.2.3">RFC 7234
         * Section 4.2.3</a>, anchored on the inherited {@link NetworkDetails#getRoundTrip()}
         * bookends.
         * <p>
         * The formula honours an upstream {@code Age} response header (injected by CDNs
         * like Cloudflare), the server-reported {@code Date}, and the local
         * request/response timestamps, selecting the conservative maximum of apparent and
         * corrected age as the initial age.
         *
         * @param now the reference instant for the age computation
         * @return the response's current age as a {@link Duration}
         */
        public @NotNull Duration currentAge(@NotNull Instant now) {
            Instant requestTime = this.getDetails().getRoundTrip().startedAt();
            Instant responseTime = this.getDetails().getRoundTrip().completedAt();
            Instant dateValue = HttpDates.parseFromHeaders(this.getHeaders(), "Date").orElse(responseTime);
            long ageValueSeconds = this.ageHeaderSeconds();

            long apparentAge = Math.max(0L, Duration.between(dateValue, responseTime).getSeconds());
            long responseDelay = Math.max(0L, Duration.between(requestTime, responseTime).getSeconds());
            long correctedAgeValue = ageValueSeconds + responseDelay;
            long initialAge = Math.max(apparentAge, correctedAgeValue);
            long residentTime = Math.max(0L, Duration.between(responseTime, now).getSeconds());

            return Duration.ofSeconds(initialAge + residentTime);
        }

        /**
         * Determines whether this cached response is currently fresh per RFC 7234 §4.2.
         *
         * @param now the reference instant
         * @return {@code true} if {@link #currentAge(Instant)} is strictly less than
         *         {@link #freshnessLifetime()}
         */
        public boolean isFresh(@NotNull Instant now) {
            return this.currentAge(now).compareTo(this.freshnessLifetime()) < 0;
        }

        /**
         * Determines whether this cached response may be served as a stale replacement on
         * an origin error, per
         * <a href="https://datatracker.ietf.org/doc/html/rfc5861#section-4">RFC 5861
         * Section 4</a>.
         * <p>
         * The boundary is measured from the end of the freshness lifetime, not from
         * {@code now}: a response with {@code max-age=60, stale-if-error=120} may serve
         * stale for 120 seconds after the 60-second freshness window ends.
         *
         * @param now the reference instant
         * @return {@code true} if {@code now} is within the stale-if-error window
         */
        public boolean canServeStaleOnError(@NotNull Instant now) {
            OptionalLong sie = this.staleIfError();

            if (sie.isEmpty())
                return false;

            Instant responseTime = this.getDetails().getRoundTrip().completedAt();
            Instant deadline = responseTime
                .plus(this.freshnessLifetime())
                .plusSeconds(sie.getAsLong());

            return !now.isAfter(deadline);
        }

        /**
         * Determines whether this cached entry carries a validator usable for conditional
         * revalidation.
         *
         * @return {@code true} if an {@link ETag} or {@code Last-Modified} header is
         *         present
         */
        public boolean canRevalidate() {
            return this.getETag().isPresent() || this.lastModified().isPresent();
        }

        /**
         * Indicates whether this entry must be revalidated before reuse once stale.
         *
         * @return {@code true} if {@code Cache-Control: must-revalidate},
         *         {@code proxy-revalidate}, or {@code no-cache} is set
         */
        public boolean mustRevalidate() {
            CacheControl cc = this.cacheControl();
            return cc.mustRevalidate() || cc.noCache();
        }

        /**
         * Returns the {@code stale-if-error} window in seconds, if present.
         *
         * @return the {@code stale-if-error} window, or {@link OptionalLong#empty()}
         */
        public @NotNull OptionalLong staleIfError() {
            return this.cacheControl().staleIfError();
        }

        /**
         * Returns the lowercased set of header names listed in the {@code Vary} response
         * header, or an empty set if the header is absent.
         *
         * @return the set of Vary header names
         */
        public @NotNull Set<String> varyHeaderNames() {
            return this.getHeaders()
                .getOptional("Vary")
                .map(list -> list.stream()
                    .flatMap(v -> Arrays.stream(v.split(",")))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet()))
                .orElseGet(Collections::emptySet);
        }

        /**
         * Parses the {@code Last-Modified} response header.
         *
         * @return the parsed last-modified instant, or {@link Optional#empty()}
         */
        public @NotNull Optional<Instant> lastModified() {
            return HttpDates.parseFromHeaders(this.getHeaders(), "Last-Modified");
        }

        /**
         * Reads the {@code Age} response header as a non-negative number of seconds,
         * returning {@code 0} if absent or malformed.
         *
         * @return the age value in seconds
         */
        private long ageHeaderSeconds() {
            return this.getHeaders()
                .getOptional("Age")
                .flatMap(ConcurrentList::findFirst)
                .map(value -> {
                    try {
                        return Math.max(0L, Long.parseLong(value.trim()));
                    } catch (NumberFormatException e) {
                        return 0L;
                    }
                })
                .orElse(0L);
        }

    }

}
