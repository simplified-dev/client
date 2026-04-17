package dev.simplified.client;

import com.google.gson.Gson;
import dev.simplified.client.decoder.ClientErrorDecoder;
import dev.simplified.client.decoder.GsonAwareErrorDecoder;
import dev.simplified.client.exception.ApiException;
import dev.simplified.client.request.Contract;
import dev.simplified.client.request.Timings;
import dev.simplified.collection.Concurrent;
import dev.simplified.collection.ConcurrentMap;
import feign.FeignException;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.gson.GsonDecoder;
import feign.gson.GsonEncoder;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.Inet6Address;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Immutable configuration bundle consumed by {@link Client} during construction.
 * <p>
 * A {@code ClientOptions} captures every customizable aspect of a client - the target endpoint
 * interface, Gson instance, headers, queries, dynamic headers, timing parameters, error decoder,
 * encoder/decoder factories, and optional IPv6 local address - in a single value object that can
 * be reused across multiple clients, mutated to derive variants, and tested in isolation.
 * <p>
 * Construction is via {@link #builder(Class, Gson)}, which seeds a {@link Builder} with sensible
 * defaults for every optional field. {@link #from(ClientConfig)} or {@link #mutate()} produces a
 * new builder pre-populated from an existing instance, enabling the
 * {@code base.mutate().withFoo(...).build()} idiom for derived configurations.
 * <p>
 * The encoder and decoder are expressed as {@link Function} factories rather than instances so
 * that each client constructed from the same options receives a freshly built codec that can
 * close over the configured {@link Gson}. Defaults are {@link GsonEncoder} and {@link GsonDecoder}.
 *
 * @param <C> the {@link Contract} interface type the resulting client will target
 * @see Client
 * @see Contract
 * @see Timings
 * @see ClientErrorDecoder
 */
@Getter
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ClientConfig<C extends Contract> {

    /** The contract interface class the resulting client will target. */
    private final @NotNull Class<C> target;

    /** The {@link Gson} instance used for request encoding and response decoding. */
    private final @NotNull Gson gson;

    /** The optional IPv6 address used as the local address for outbound connections. */
    private final @NotNull Optional<Inet6Address> inet6Address;

    /** The timing configuration governing connection pool sizes, timeouts, keep-alive, and cache duration. */
    private final @NotNull Timings timings;

    /** The error decoder that transforms HTTP error responses into typed {@link ApiException} instances. */
    private final @NotNull ClientErrorDecoder errorDecoder;

    /** The static query parameters appended to every outbound HTTP request. */
    private final @NotNull ConcurrentMap<String, String> queries;

    /** The static headers appended to every outbound HTTP request. */
    private final @NotNull ConcurrentMap<String, String> headers;

    /** The lazily-evaluated dynamic headers appended to every outbound HTTP request when present. */
    private final @NotNull ConcurrentMap<String, Supplier<Optional<String>>> dynamicHeaders;

    /** Factory producing the Feign {@link Encoder} for outbound request bodies, given the configured {@link Gson}. */
    private final @NotNull Function<Gson, Encoder> encoderFactory;

    /** Factory producing the Feign {@link Decoder} for inbound response bodies, given the configured {@link Gson}. */
    private final @NotNull Function<Gson, Decoder> decoderFactory;

    /**
     * Returns a new {@link Builder} seeded with safe defaults for the given contract type and
     * {@link Gson} instance.
     * <p>
     * Defaults: empty headers, queries, and dynamic headers; {@link Timings#createDefault()};
     * an error decoder that wraps the Feign error status into a generic {@link ApiException};
     * {@link GsonEncoder} and {@link GsonDecoder} factories; and no IPv6 local address binding.
     *
     * @param <C> the contract interface type
     * @param target the contract interface class
     * @param gson the Gson instance for serialization and deserialization
     * @return a builder pre-populated with defaults
     */
    public static <C extends Contract> @NotNull Builder<C> builder(@NotNull Class<C> target, @NotNull Gson gson) {
        return new Builder<>(target, gson);
    }

    /**
     * Returns a new {@link Builder} pre-populated with the values from the given options.
     * <p>
     * Mutating the returned builder has no effect on the source instance; the maps are copied
     * into fresh mutable {@link ConcurrentMap} instances. Use this to derive a variant of an
     * existing configuration without re-stating every field.
     *
     * @param <C> the contract interface type
     * @param existing the options to copy from
     * @return a pre-populated builder
     */
    public static <C extends Contract> @NotNull Builder<C> from(@NotNull ClientConfig<C> existing) {
        return new Builder<>(existing);
    }

    /**
     * Returns a {@link Builder} pre-populated with this instance's values for further modification.
     * <p>
     * Equivalent to {@link #from(ClientConfig) ClientOptions.from(this)}; provided as an instance
     * method to support the {@code options.mutate().withFoo(...).build()} idiom.
     *
     * @return a pre-populated builder
     */
    public @NotNull Builder<C> mutate() {
        return from(this);
    }

    /**
     * Fluent builder for constructing immutable {@link ClientConfig} instances.
     *
     * @param <C> the contract interface type
     */
    public static final class Builder<C extends Contract> {

        private final @NotNull Class<C> target;
        private @NotNull Gson gson;
        private @NotNull Optional<Inet6Address> inet6Address = Optional.empty();
        private @NotNull Timings timings = Timings.createDefault();
        private @NotNull ClientErrorDecoder errorDecoder = defaultErrorDecoder();
        private final @NotNull ConcurrentMap<String, String> queries = Concurrent.newMap();
        private final @NotNull ConcurrentMap<String, String> headers = Concurrent.newMap();
        private final @NotNull ConcurrentMap<String, Supplier<Optional<String>>> dynamicHeaders = Concurrent.newMap();
        private @NotNull Function<Gson, Encoder> encoderFactory = GsonEncoder::new;
        private @NotNull Function<Gson, Decoder> decoderFactory = GsonDecoder::new;

        private Builder(@NotNull Class<C> target, @NotNull Gson gson) {
            this.target = target;
            this.gson = gson;
        }

        private Builder(@NotNull ClientConfig<C> existing) {
            this.target = existing.target;
            this.gson = existing.gson;
            this.inet6Address = existing.inet6Address;
            this.timings = existing.timings;
            this.errorDecoder = existing.errorDecoder;
            this.queries.putAll(existing.queries);
            this.headers.putAll(existing.headers);
            this.dynamicHeaders.putAll(existing.dynamicHeaders);
            this.encoderFactory = existing.encoderFactory;
            this.decoderFactory = existing.decoderFactory;
        }

        /**
         * Sets the {@link Gson} instance used for serialization and deserialization.
         *
         * @param gson the Gson instance
         * @return this builder
         */
        public @NotNull Builder<C> withGson(@NotNull Gson gson) {
            this.gson = gson;
            return this;
        }

        /**
         * Sets the IPv6 local address bound to outbound connections.
         *
         * @param inet6Address the IPv6 address, or {@code null} to bind to the system default
         * @return this builder
         */
        public @NotNull Builder<C> withInet6Address(@Nullable Inet6Address inet6Address) {
            this.inet6Address = Optional.ofNullable(inet6Address);
            return this;
        }

        /**
         * Sets the IPv6 local address bound to outbound connections from an {@link Optional}.
         *
         * @param inet6Address the IPv6 address, or {@link Optional#empty()} to bind to the system default
         * @return this builder
         */
        public @NotNull Builder<C> withInet6Address(@NotNull Optional<Inet6Address> inet6Address) {
            this.inet6Address = inet6Address;
            return this;
        }

        /**
         * Sets the {@link Timings} configuration.
         *
         * @param timings the timing parameters
         * @return this builder
         */
        public @NotNull Builder<C> withTimings(@NotNull Timings timings) {
            this.timings = timings;
            return this;
        }

        /**
         * Sets the error decoder used to transform HTTP error responses into typed exceptions.
         *
         * @param errorDecoder the error decoder
         * @return this builder
         */
        public @NotNull Builder<C> withErrorDecoder(@NotNull ClientErrorDecoder errorDecoder) {
            this.errorDecoder = errorDecoder;
            return this;
        }

        /**
         * Sets the error decoder to a {@link GsonAwareErrorDecoder} that receives the builder's
         * configured {@link Gson} at decode time. Pairs naturally with a
         * {@link dev.simplified.client.exception.JsonApiException JsonApiException} subclass
         * and a constructor reference:
         *
         * <pre>{@code
         * .withErrorDecoder(FooApiException::new)
         * }</pre>
         *
         * <p>The decoder is adapted to the underlying {@link ClientErrorDecoder} contract by
         * closing over the current {@link Gson} instance.</p>
         *
         * @param errorDecoder the Gson-aware error decoder
         * @return this builder
         */
        public @NotNull Builder<C> withErrorDecoder(@NotNull GsonAwareErrorDecoder errorDecoder) {
            return this.withErrorDecoder((methodKey, response) -> errorDecoder.decode(this.gson, methodKey, response));
        }

        /**
         * Adds a single static query parameter.
         *
         * @param name the query parameter name
         * @param value the query parameter value
         * @return this builder
         */
        public @NotNull Builder<C> withQuery(@NotNull String name, @NotNull String value) {
            this.queries.put(name, value);
            return this;
        }

        /**
         * Adds all entries from the given map as static query parameters.
         *
         * @param queries the query parameters to add
         * @return this builder
         */
        public @NotNull Builder<C> withQueries(@NotNull Map<String, String> queries) {
            this.queries.putAll(queries);
            return this;
        }

        /**
         * Adds a single static header.
         *
         * @param name the header name
         * @param value the header value
         * @return this builder
         */
        public @NotNull Builder<C> withHeader(@NotNull String name, @NotNull String value) {
            this.headers.put(name, value);
            return this;
        }

        /**
         * Adds all entries from the given map as static headers.
         *
         * @param headers the headers to add
         * @return this builder
         */
        public @NotNull Builder<C> withHeaders(@NotNull Map<String, String> headers) {
            this.headers.putAll(headers);
            return this;
        }

        /**
         * Adds a single dynamic header whose value is evaluated lazily on each request.
         * <p>
         * The supplier is invoked at request time; if it returns {@link Optional#empty()},
         * the header is omitted for that request.
         *
         * @param name the header name
         * @param valueSupplier the supplier producing the header value, or empty to omit
         * @return this builder
         */
        public @NotNull Builder<C> withDynamicHeader(@NotNull String name, @NotNull Supplier<Optional<String>> valueSupplier) {
            this.dynamicHeaders.put(name, valueSupplier);
            return this;
        }

        /**
         * Adds all entries from the given map as dynamic headers.
         *
         * @param dynamicHeaders the dynamic headers to add
         * @return this builder
         */
        public @NotNull Builder<C> withDynamicHeaders(@NotNull Map<String, Supplier<Optional<String>>> dynamicHeaders) {
            this.dynamicHeaders.putAll(dynamicHeaders);
            return this;
        }

        /**
         * Sets the factory producing the Feign {@link Encoder} used for outbound request bodies.
         * <p>
         * The factory is invoked once per client constructed from the resulting options, with
         * the configured {@link Gson} as input. This allows the encoder to close over the Gson
         * instance while remaining decoupled from any specific encoder implementation.
         *
         * @param encoderFactory the factory mapping a Gson instance to an Encoder
         * @return this builder
         */
        public @NotNull Builder<C> withEncoderFactory(@NotNull Function<Gson, Encoder> encoderFactory) {
            this.encoderFactory = encoderFactory;
            return this;
        }

        /**
         * Sets the factory producing the Feign {@link Decoder} used for inbound response bodies.
         * <p>
         * The factory is invoked once per client constructed from the resulting options, with
         * the configured {@link Gson} as input.
         *
         * @param decoderFactory the factory mapping a Gson instance to a Decoder
         * @return this builder
         */
        public @NotNull Builder<C> withDecoderFactory(@NotNull Function<Gson, Decoder> decoderFactory) {
            this.decoderFactory = decoderFactory;
            return this;
        }

        /**
         * Constructs an immutable {@link ClientConfig} from the current builder state.
         * <p>
         * The mutable maps held by the builder are sealed into unmodifiable copies on the
         * resulting options instance, so the builder may continue to be mutated and rebuilt
         * without affecting prior builds.
         *
         * @return a new immutable {@code ClientOptions}
         */
        public @NotNull ClientConfig<C> build() {
            Objects.requireNonNull(this.target, "target");
            Objects.requireNonNull(this.gson, "gson");
            return new ClientConfig<>(
                this.target,
                this.gson,
                this.inet6Address,
                this.timings,
                this.errorDecoder,
                Concurrent.newUnmodifiableMap(this.queries),
                Concurrent.newUnmodifiableMap(this.headers),
                Concurrent.newUnmodifiableMap(this.dynamicHeaders),
                this.encoderFactory,
                this.decoderFactory
            );
        }

    }

    /**
     * Returns the default error decoder, which wraps the Feign error status into a generic
     * {@link ApiException} tagged with the literal source {@code "Client"}.
     *
     * @return the default error decoder
     */
    private static @NotNull ClientErrorDecoder defaultErrorDecoder() {
        return (methodKey, response) -> new ApiException(
            FeignException.errorStatus(
                methodKey,
                response,
                null,
                null
            ),
            response,
            "Client"
        );
    }

}
