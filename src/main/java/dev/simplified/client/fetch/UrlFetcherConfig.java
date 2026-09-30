package dev.simplified.client.fetch;

import com.google.gson.Gson;
import dev.simplified.annotations.AccessLevel;
import dev.simplified.annotations.Getter;
import dev.simplified.annotations.RequiredArgsConstructor;
import dev.simplified.client.ClientConfig;
import dev.simplified.client.cache.CacheKey;
import dev.simplified.client.cache.ResponseCache;
import dev.simplified.client.exception.UrlFetchException;
import dev.simplified.client.ratelimit.RateLimit;
import dev.simplified.client.ratelimit.RateLimitManager;
import dev.simplified.client.request.Timings;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.Inet6Address;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Immutable configuration bundle consumed by {@link UrlFetcher} during construction.
 * <p>
 * Mirrors {@link ClientConfig ClientConfig} in shape and idiom, omitting
 * the contract-target type and Feign-specific encoder/decoder factories that the standalone
 * fetcher does not need. New fields specific to ad-hoc URL fetching are the body cap, the
 * default {@link RateLimit} policy, the URL-to-bucket resolver, and optional shared
 * {@link ResponseCache} / {@link RateLimitManager} references that allow a fetcher to share
 * state with an existing client when desired.
 * <p>
 * Construction is via {@link #builder(Gson)}, which seeds a {@link Builder} with sensible
 * defaults: 5 MiB body cap, {@link RateLimit#UNLIMITED} default policy, {@link URI#getHost()}
 * bucket resolver, and a default {@code User-Agent} header. {@link #from(UrlFetcherConfig)}
 * or {@link #mutate()} produces a builder pre-populated from an existing instance.
 *
 * @see UrlFetcher
 * @see Timings
 * @see RateLimit
 * @see ResponseCache
 * @see RateLimitManager
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class UrlFetcherConfig {

    /**
     * Default body size cap, 5 MiB, matching the legacy dataflow {@code UrlFetcher} default.
     */
    public static final long DEFAULT_MAX_BODY_BYTES = 5L * 1024 * 1024;

    /**
     * Default {@code User-Agent} header value applied by the default builder.
     */
    public static final @NotNull String DEFAULT_USER_AGENT = "simplified-fetcher/0.1";

    /**
     * The {@link Gson} instance used to deserialize typed bodies in {@link UrlFetcher#get(URI, Class)}.
     */
    private final @NotNull Gson gson;

    /**
     * The timing configuration governing connection pool sizes, timeouts, keep-alive, and cache duration.
     */
    private final @NotNull Timings timings;

    /**
     * The maximum response body size in bytes for a fetch that names no cap of its own; a body
     * beyond it raises {@link UrlFetchException.BodyCapExceeded}. A fetch through
     * {@link UrlFetcher#get(URI, long)}, {@link UrlFetcher#get(URI, Class, long)} or
     * {@link UrlFetcher#bytes(URI, long)} is held to the cap it names instead.
     */
    private final long maxBodyBytes;

    /**
     * The default {@link RateLimit} policy applied to every resolved bucket.
     */
    private final @NotNull RateLimit defaultRateLimit;

    /**
     * Resolves the rate-limit bucket identifier from a request URL; default is {@link URI#getHost()}.
     */
    private final @NotNull Function<URI, String> bucketResolver;

    /**
     * Optional pre-existing {@link ResponseCache} to share with another client; if empty, the fetcher owns its own cache.
     */
    private final @NotNull Optional<ResponseCache> sharedCache;

    /**
     * Optional pre-existing {@link RateLimitManager} to share with another client; if empty, the fetcher owns its own manager.
     */
    private final @NotNull Optional<RateLimitManager> sharedRateLimits;

    /**
     * The optional IPv6 address used as the local address for outbound connections.
     */
    private final @NotNull Optional<Inet6Address> inet6Address;

    /**
     * The static query parameters appended to every outbound request. The response cache keys
     * each request by their {@linkplain CacheKey#queryFingerprints(Map) stand-in}, so fetchers
     * sharing a cache with different static queries never answer each other's requests.
     */
    private final @NotNull ConcurrentMap<String, String> queries;

    /**
     * The static headers added to each request the fetcher sends and to the follow-up of each
     * redirect it follows, including the default {@code User-Agent} and {@code Accept}.
     */
    private final @NotNull ConcurrentMap<String, String> headers;

    /**
     * The lazily-evaluated dynamic headers added, when their supplier yields a value, to the
     * same requests as the static headers.
     */
    private final @NotNull ConcurrentMap<String, Supplier<Optional<String>>> dynamicHeaders;

    /**
     * Returns a new {@link Builder} seeded with safe defaults and the given {@link Gson}.
     *
     * @param gson the Gson instance for typed body deserialization
     * @return a builder pre-populated with defaults
     */
    public static @NotNull Builder builder(@NotNull Gson gson) {
        return new Builder(gson);
    }

    /**
     * Returns a new {@link Builder} pre-populated with the values from the given options.
     *
     * @param existing the options to copy from
     * @return a pre-populated builder
     */
    public static @NotNull Builder from(@NotNull UrlFetcherConfig existing) {
        return new Builder(existing);
    }

    /**
     * Returns a {@link Builder} pre-populated with this instance's values for further modification.
     *
     * @return a pre-populated builder
     */
    public @NotNull Builder mutate() {
        return from(this);
    }

    /**
     * Fluent builder for constructing immutable {@link UrlFetcherConfig} instances.
     */
    public static final class Builder {

        private @NotNull Gson gson;
        private @NotNull Timings timings = Timings.createDefault();
        private long maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
        private @NotNull RateLimit defaultRateLimit = RateLimit.UNLIMITED;
        private @NotNull Function<URI, String> bucketResolver = URI::getHost;
        private @NotNull Optional<ResponseCache> sharedCache = Optional.empty();
        private @NotNull Optional<RateLimitManager> sharedRateLimits = Optional.empty();
        private @NotNull Optional<Inet6Address> inet6Address = Optional.empty();
        private final @NotNull ConcurrentMap<String, String> queries = Concurrent.newMap();
        private final @NotNull ConcurrentMap<String, String> headers = Concurrent.newMap();
        private final @NotNull ConcurrentMap<String, Supplier<Optional<String>>> dynamicHeaders = Concurrent.newMap();

        private Builder(@NotNull Gson gson) {
            this.gson = gson;
            this.headers.put("User-Agent", DEFAULT_USER_AGENT);
            this.headers.put("Accept", "*/*");
        }

        private Builder(@NotNull UrlFetcherConfig existing) {
            this.gson = existing.gson;
            this.timings = existing.timings;
            this.maxBodyBytes = existing.maxBodyBytes;
            this.defaultRateLimit = existing.defaultRateLimit;
            this.bucketResolver = existing.bucketResolver;
            this.sharedCache = existing.sharedCache;
            this.sharedRateLimits = existing.sharedRateLimits;
            this.inet6Address = existing.inet6Address;
            this.queries.putAll(existing.queries);
            this.headers.putAll(existing.headers);
            this.dynamicHeaders.putAll(existing.dynamicHeaders);
        }

        /**
         * Sets the {@link Gson} instance used for typed body deserialization.
         *
         * @param gson the Gson instance
         * @return this builder
         */
        public @NotNull Builder withGson(@NotNull Gson gson) {
            this.gson = gson;
            return this;
        }

        /**
         * Sets the {@link Timings} configuration.
         *
         * @param timings the timing parameters
         * @return this builder
         */
        public @NotNull Builder withTimings(@NotNull Timings timings) {
            this.timings = timings;
            return this;
        }

        /**
         * Sets the maximum response body size in bytes for a fetch that names no cap of its own.
         *
         * @param maxBodyBytes the cap in bytes
         * @return this builder
         * @throws IllegalArgumentException if {@code maxBodyBytes} is negative
         */
        public @NotNull Builder withMaxBodyBytes(long maxBodyBytes) {
            if (maxBodyBytes < 0)
                throw new IllegalArgumentException(String.format("Body cap must not be negative, got '%s'", maxBodyBytes));

            this.maxBodyBytes = maxBodyBytes;
            return this;
        }

        /**
         * Sets the default {@link RateLimit} policy applied to every resolved bucket.
         *
         * @param defaultRateLimit the policy
         * @return this builder
         */
        public @NotNull Builder withDefaultRateLimit(@NotNull RateLimit defaultRateLimit) {
            this.defaultRateLimit = defaultRateLimit;
            return this;
        }

        /**
         * Sets the function that resolves a rate-limit bucket identifier from a request URL.
         *
         * @param bucketResolver the URL-to-bucket-id resolver
         * @return this builder
         */
        public @NotNull Builder withBucketResolver(@NotNull Function<URI, String> bucketResolver) {
            this.bucketResolver = bucketResolver;
            return this;
        }

        /**
         * Sets a pre-existing {@link ResponseCache} to share with another client.
         *
         * @param sharedCache the shared cache, or {@code null} to clear and let the fetcher own its own
         * @return this builder
         */
        public @NotNull Builder withSharedCache(@Nullable ResponseCache sharedCache) {
            this.sharedCache = Optional.ofNullable(sharedCache);
            return this;
        }

        /**
         * Sets a pre-existing {@link RateLimitManager} to share with another client.
         *
         * @param sharedRateLimits the shared manager, or {@code null} to clear and let the fetcher own its own
         * @return this builder
         */
        public @NotNull Builder withSharedRateLimits(@Nullable RateLimitManager sharedRateLimits) {
            this.sharedRateLimits = Optional.ofNullable(sharedRateLimits);
            return this;
        }

        /**
         * Sets the IPv6 local address bound to outbound connections.
         *
         * @param inet6Address the IPv6 address, or {@code null} to bind to the system default
         * @return this builder
         */
        public @NotNull Builder withInet6Address(@Nullable Inet6Address inet6Address) {
            this.inet6Address = Optional.ofNullable(inet6Address);
            return this;
        }

        /**
         * Adds a single static query parameter.
         * <p>
         * The transport appends the parameter to each request the fetcher sends. The response
         * cache keys a request by the parameter too, through a
         * {@linkplain CacheKey#queryFingerprints(Map) stand-in} for its value, so a fetcher
         * {@linkplain #withSharedCache(ResponseCache) sharing a cache} with another configured
         * with a different value is never answered with the other's response.
         *
         * @param name the query parameter name
         * @param value the query parameter value
         * @return this builder
         */
        public @NotNull Builder withQuery(@NotNull String name, @NotNull String value) {
            this.queries.put(name, value);
            return this;
        }

        /**
         * Adds all entries from the given map as static query parameters.
         *
         * @param queries the query parameters to add
         * @return this builder
         */
        public @NotNull Builder withQueries(@NotNull Map<String, String> queries) {
            this.queries.putAll(queries);
            return this;
        }

        /**
         * Adds a single static header.
         * <p>
         * The header is added to each request the fetcher sends, where its response cache sees
         * it, and to the follow-up of each redirect it follows. A request carrying
         * {@code Authorization} or {@code Cookie} does not follow a redirect to another host or
         * port: the fetcher raises the {@code 3xx} as a {@link UrlFetchException.Redirection}.
         *
         * @param name the header name
         * @param value the header value
         * @return this builder
         */
        public @NotNull Builder withHeader(@NotNull String name, @NotNull String value) {
            this.headers.put(name, value);
            return this;
        }

        /**
         * Adds all entries from the given map as static headers, each as
         * {@link #withHeader(String, String)} describes.
         *
         * @param headers the headers to add
         * @return this builder
         */
        public @NotNull Builder withHeaders(@NotNull Map<String, String> headers) {
            this.headers.putAll(headers);
            return this;
        }

        /**
         * Adds a single dynamic header whose value is evaluated lazily on each request.
         * <p>
         * The supplier is invoked once per fetch, one the cache answers included; if it returns
         * {@link Optional#empty()}, the header is omitted for that request. The header reaches
         * the same requests, and affects redirects the same way, as a static header added by
         * {@link #withHeader(String, String)}.
         *
         * @param name the header name
         * @param valueSupplier the supplier producing the header value, or empty to omit
         * @return this builder
         */
        public @NotNull Builder withDynamicHeader(@NotNull String name, @NotNull Supplier<Optional<String>> valueSupplier) {
            this.dynamicHeaders.put(name, valueSupplier);
            return this;
        }

        /**
         * Adds all entries from the given map as dynamic headers, each as
         * {@link #withDynamicHeader(String, Supplier)} describes.
         *
         * @param dynamicHeaders the dynamic headers to add
         * @return this builder
         */
        public @NotNull Builder withDynamicHeaders(@NotNull Map<String, Supplier<Optional<String>>> dynamicHeaders) {
            this.dynamicHeaders.putAll(dynamicHeaders);
            return this;
        }

        /**
         * Constructs an immutable {@link UrlFetcherConfig} from the current builder state.
         *
         * @return a new immutable config
         */
        public @NotNull UrlFetcherConfig build() {
            Objects.requireNonNull(this.gson, "gson");
            return new UrlFetcherConfig(
                this.gson,
                this.timings,
                this.maxBodyBytes,
                this.defaultRateLimit,
                this.bucketResolver,
                this.sharedCache,
                this.sharedRateLimits,
                this.inet6Address,
                Concurrent.newUnmodifiableMap(this.queries),
                Concurrent.newUnmodifiableMap(this.headers),
                Concurrent.newUnmodifiableMap(this.dynamicHeaders)
            );
        }

    }

}
