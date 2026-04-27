package dev.simplified.client.response;

import dev.simplified.client.request.Request;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentList;
import dev.simplified.collection.ConcurrentMap;
import dev.simplified.collection.tuple.pair.Pair;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
 * Two default implementations are provided as nested classes: {@link Impl} carries the
 * full decoded response, while {@link Cached} extends {@code Impl} with behavioral
 * accessors that compute RFC 7234 cache semantics (freshness, age, revalidation
 * capability) from the inherited headers and {@link NetworkDetails}. {@code Cached} adds
 * no new fields - every cache-specific value is derived on demand.
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

    /**
     * Returns the raw response body bytes captured during decoding, if any.
     * <p>
     * Populated when the response was decoded from a buffered (non-streaming) body; used
     * by the HTTP response cache to replay cached responses through the decoder pipeline
     * on conditional-request cache hits. Returns {@link Optional#empty()} for streaming
     * responses, errors without a body, and exception subtypes such as
     * {@link dev.simplified.client.exception.ApiException}.
     *
     * @return the raw wire bytes for this response, or {@link Optional#empty()} if not
     *         captured
     */
    default @NotNull Optional<byte[]> getRawBody() {
        return Optional.empty();
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
        return headers.entrySet()
            .stream()
            .filter(entry -> !entry.getValue().isEmpty())
            .filter(entry -> !NetworkDetails.isInternalHeader(entry.getKey()))
            .map(entry -> Pair.of(
                entry.getKey(),
                (ConcurrentList<String>) Concurrent.newUnmodifiableList(entry.getValue())
            ))
            .collect(Concurrent.toUnmodifiableTreeMap(String.CASE_INSENSITIVE_ORDER));
    }

    /**
     * Default immutable implementation of {@link Response}, storing all response
     * components as final fields initialized via constructor injection.
     * <p>
     * All accessors are generated by Lombok's {@link Getter} annotation, except for
     * {@link #getRawBody()} which wraps the optional raw-body field in an {@link Optional}.
     *
     * @param <T> the deserialized type of the response body
     */
    @Getter
    @AllArgsConstructor
    class Impl<T> implements Response<T> {

        /** The deserialized response body. */
        private final @NotNull T body;

        /** The network timing and TLS metadata for this response. */
        private final @NotNull NetworkDetails details;

        /** The HTTP status code and classification for this response. */
        private final @NotNull HttpStatus status;

        /** The originating request that produced this response. */
        private final @NotNull Request request;

        /** The response headers, with internal instrumentation headers excluded. */
        private final @NotNull ConcurrentMap<String, ConcurrentList<String>> headers;

        /** The raw wire bytes of the response body, captured at decode time; {@code null} for streaming or error responses. */
        @Getter(AccessLevel.NONE)
        private final byte @Nullable [] rawBody;

        /**
         * Constructs a response without a captured raw body, delegating to the all-args
         * constructor with {@code rawBody = null}.
         *
         * @param body the deserialized response body
         * @param details the network timing and TLS metadata
         * @param status the HTTP status code and classification
         * @param request the originating request
         * @param headers the response headers with internal headers excluded
         */
        public Impl(
            @NotNull T body,
            @NotNull NetworkDetails details,
            @NotNull HttpStatus status,
            @NotNull Request request,
            @NotNull ConcurrentMap<String, ConcurrentList<String>> headers
        ) {
            this(body, details, status, request, headers, null);
        }

        @Override
        public @NotNull Optional<byte[]> getRawBody() {
            return Optional.ofNullable(this.rawBody);
        }

    }

    /**
     * A {@link Response.Impl} augmented with RFC 7234 cache semantics.
     * <p>
     * {@code Cached} holds <b>no additional state</b> of its own - every cache-specific value
     * is derived on demand from the inherited {@link NetworkDetails} (request / response
     * timings via {@link NetworkDetails#getRoundTrip()}) and the inherited response
     * headers ({@code Cache-Control}, {@code Expires}, {@code Date}, {@code Age},
     * {@code Vary}, {@code Last-Modified}). Used as the value type of
     * {@code ResponseCache}'s two-level map, where a cached entry is both a first-class
     * {@link Response} (status, headers, body, raw bytes) and a carrier of freshness
     * and revalidation logic.
     * <p>
     * Because directive parsing and header lookups are cheap (a handful of microseconds),
     * storing parsed fields on the entry would offer no meaningful performance benefit over
     * re-deriving them on each cache operation, while adding duplicated state and a risk of
     * drift from the authoritative headers. The inherited {@link Response.Impl} fields are
     * the single source of truth.
     *
     * @param <T> the deserialized type of the response body
     * @see <a href="https://datatracker.ietf.org/doc/html/rfc7234">RFC 7234 - HTTP/1.1 Caching</a>
     */
    final class Cached<T> extends Impl<T> {

        /**
         * Constructs a new cached response carrying the given fields. Typically invoked
         * via {@link #from(Impl)} to copy state from an existing decoded response; direct
         * construction is supported for tests and custom wiring.
         *
         * @param body the deserialized response body
         * @param details the network timing and TLS metadata (the round-trip bookends are
         *                used as the request/response time anchors for RFC 7234 §4.2.3
         *                age calculation)
         * @param status the HTTP status code and classification
         * @param request the originating request
         * @param headers the response headers with internal headers excluded
         * @param rawBody the raw wire bytes, or {@code null} if none were captured
         */
        public Cached(
            @NotNull T body,
            @NotNull NetworkDetails details,
            @NotNull HttpStatus status,
            @NotNull Request request,
            @NotNull ConcurrentMap<String, ConcurrentList<String>> headers,
            byte @Nullable [] rawBody
        ) {
            super(body, details, status, request, headers, rawBody);
        }

        /**
         * Builds a {@code Cached} view over an existing decoded response, copying all
         * inherited fields including the raw body.
         *
         * @param source the source response to wrap
         * @param <U> the deserialized body type
         * @return a new {@code Cached} instance carrying the same state as {@code source}
         */
        public static <U> @NotNull Cached<U> from(@NotNull Impl<U> source) {
            return new Cached<>(
                source.getBody(),
                source.getDetails(),
                source.getStatus(),
                source.getRequest(),
                source.getHeaders(),
                source.getRawBody().orElse(null)
            );
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
