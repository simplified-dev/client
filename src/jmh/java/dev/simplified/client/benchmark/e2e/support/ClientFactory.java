package dev.simplified.client.benchmark.e2e.support;

import com.google.gson.Gson;
import dev.simplified.client.Client;
import dev.simplified.client.ClientConfig;
import org.jetbrains.annotations.NotNull;

import javax.net.ssl.SSLContext;

/**
 * Builds a fully configured {@link Client} for benchmark scenarios, wired to a
 * {@link CannedFeignClient} so the socket stack and Apache layer are excluded from
 * the measured region. Pre-seeds the canned transport with every fixture path the
 * benchmark contract advertises.
 */
public final class ClientFactory {

    private ClientFactory() { }

    public static @NotNull Client<BenchmarkContract> buildWithCanned(@NotNull CannedFeignClient transport) {
        return Client.create(
            ClientConfig.builder(BenchmarkContract.class, new Gson())
                .withCustomFeignClient(transport)
                .build()
        );
    }

    public static @NotNull Client<LoopbackContract> buildWithLoopback(@NotNull SSLContext clientSslContext) {
        return Client.create(
            ClientConfig.builder(LoopbackContract.class, new Gson())
                .withSslContext(clientSslContext)
                .build()
        );
    }

    public static @NotNull CannedFeignClient defaultCannedTransport() {
        return new CannedFeignClient()
            .registerJson("/small", 200, FixtureBodies.SMALL_JSON)
            .registerJson("/large", 200, FixtureBodies.LARGE_JSON)
            .registerCacheable("/cacheable", FixtureBodies.SMALL_JSON)
            .registerJson("/error", 503, FixtureBodies.ERROR_JSON)
            .registerJson("/rate-limited", 200, FixtureBodies.SMALL_JSON)
            .registerJson("/stream", 200, FixtureBodies.STREAM_BODY)
            .registerJson("/resource", 200, FixtureBodies.SMALL_JSON)
            .registerJson("/type-a", 200, FixtureBodies.TYPE_A_JSON)
            .registerJson("/type-b", 200, FixtureBodies.TYPE_B_JSON)
            .registerJson("/type-c", 200, FixtureBodies.TYPE_C_JSON)
            .registerJson("/type-d", 200, FixtureBodies.TYPE_D_JSON);
    }

}
