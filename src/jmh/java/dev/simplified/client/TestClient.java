package dev.simplified.client;

import dev.simplified.client.factory.ApacheClientFactory;
import dev.simplified.client.request.Contract;
import feign.hc5.ApacheHttp5Client;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.jetbrains.annotations.NotNull;

import javax.net.ssl.SSLContext;

/**
 * Bench-only entry points that build a {@link Client} with a non-production transport. Lives
 * in the same package as {@link Client} so it can call the package-private
 * {@link Client#Client(ClientConfig, feign.Client)} constructor without forcing production
 * surface to expose an override hook.
 *
 * <p>Two factories are provided:</p>
 * <ul>
 *   <li>{@link #withCustomTransport(ClientConfig, feign.Client)} - swaps the Apache HC5 transport
 *       for a caller-supplied {@link feign.Client}, typically a canned in-process stand-in that
 *       short-circuits the socket stack. Used by JMH benchmarks that want to measure the library
 *       hot path without TCP / TLS / kernel involvement.</li>
 *   <li>{@link #withSslContext(ClientConfig, SSLContext)} - builds a real Apache HC5 transport
 *       that trusts a caller-supplied {@link SSLContext} and skips hostname verification, for
 *       loopback HTTPS rigs serving self-signed certificates.</li>
 * </ul>
 *
 * <p><b>Never reachable from production code.</b> The class lives in {@code src/jmh}; the main
 * sourceset cannot see it.</p>
 */
public final class TestClient {

    private TestClient() { }

    /**
     * Builds a {@link Client} that uses the supplied Feign transport in place of the production
     * Apache HC5 pool.
     *
     * @param <C> the contract interface type
     * @param options the immutable configuration bundle (unchanged from production usage)
     * @param transport the Feign transport stand-in
     * @return a fully initialized client wired to the supplied transport
     */
    public static <C extends Contract> @NotNull Client<C> withCustomTransport(
        @NotNull ClientConfig<C> options,
        @NotNull feign.Client transport
    ) {
        return new Client<>(options, transport);
    }

    /**
     * Builds a {@link Client} backed by a real Apache HC5 pool that trusts the supplied
     * {@link SSLContext} and skips hostname verification. Equivalent to the production
     * {@link Client#create(ClientConfig)} path but with a non-default TLS strategy.
     *
     * @param <C> the contract interface type
     * @param options the immutable configuration bundle
     * @param sslContext the SSL context whose trust manager should be honored
     * @return a fully initialized client wired to a trust-all Apache HC5 pool
     */
    public static <C extends Contract> @NotNull Client<C> withSslContext(
        @NotNull ClientConfig<C> options,
        @NotNull SSLContext sslContext
    ) {
        TlsSocketStrategy trustAll = new DefaultClientTlsStrategy(sslContext, NoopHostnameVerifier.INSTANCE);
        feign.Client transport = new ApacheHttp5Client(ApacheClientFactory.configure(
            options.getTimings(),
            options.getQueries(),
            options.getHeaders(),
            options.getDynamicHeaders(),
            options.getInet6Address(),
            trustAll
        ).build());
        return new Client<>(options, transport);
    }

}
